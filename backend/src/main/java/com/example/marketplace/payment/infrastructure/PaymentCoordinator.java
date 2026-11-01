package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.payment.api.PaymentProvider;
import com.example.marketplace.payment.api.PaymentProvider.Result;
import com.example.marketplace.payment.domain.PaymentRules;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.inventory.api.Reservations;
import com.example.marketplace.promotion.api.CouponReservations;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.identity.api.SellerMembership;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
public final class PaymentCoordinator implements Payments {
 @FunctionalInterface public interface Checkpoint { void after(String stage) throws Exception; }
 private final NamedParameterJdbcTemplate jdbc;private final Orders orders;private final Reservations inventory;private final CouponReservations coupons;
 private final PaymentProvider provider;private final Jobs jobs;private final Outbox outbox;private final TransactionTemplate tx;private final Clock clock;private final Checkpoint checkpoint;
 private final AccessPolicy access;private final SellerMembership members;private final PurchaseSessions sessions;
 private PaymentProviderConfiguration.Settings sandboxSettings=new PaymentProviderConfiguration.Settings("","","","","","");
 @Autowired private com.example.marketplace.order.api.AftercareSettlements aftercareSettlements;
 @Autowired void sandboxSettings(PaymentProviderConfiguration.Settings settings) { this.sandboxSettings=settings; }
 public record SandboxCheckout(String clientKey,String orderId,String amount,String customerKey) { }
 public record Confirmation(String paymentKey,String orderId,String amount) {
  @Override public String toString() { return "TossConfirmation[redacted]"; }
 }
 @Autowired public PaymentCoordinator(NamedParameterJdbcTemplate jdbc,Orders orders,Reservations inventory,CouponReservations coupons,PaymentProvider provider,Jobs jobs,Outbox outbox,PlatformTransactionManager manager,AccessPolicy access,SellerMembership members,PurchaseSessions sessions) {
  this(jdbc,orders,inventory,coupons,provider,jobs,outbox,manager,access,members,sessions,Clock.systemUTC(),stage->{ });
 }
 public PaymentCoordinator(NamedParameterJdbcTemplate jdbc,Orders orders,Reservations inventory,CouponReservations coupons,PaymentProvider provider,Jobs jobs,Outbox outbox,PlatformTransactionManager manager,AccessPolicy access,SellerMembership members,PurchaseSessions sessions,Clock clock,Checkpoint checkpoint) {
  this.jdbc=jdbc;this.orders=orders;this.inventory=inventory;this.coupons=coupons;this.provider=provider;this.jobs=jobs;this.outbox=outbox;this.tx=new TransactionTemplate(manager);this.access=access;this.members=members;this.sessions=sessions;this.clock=clock;this.checkpoint=checkpoint;
 }
 private record Attempt(UUID id,UUID orderId,String number,String state,long amount,String mode,String compensation,boolean review,Instant started,int count,int round,UUID job) {
  PaymentProvider.Request request() { return new PaymentProvider.Request(id,orderId,amount,mode); }
 }
 private Attempt attempt(String number) {
  var rows=jdbc.query("SELECT * FROM marketplace.payment_attempt WHERE order_number=:n",Map.of("n",number),(r,i)->new Attempt(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),number,r.getString("state"),r.getLong("amount"),r.getString("mode"),r.getString("compensation"),r.getBoolean("review_required"),r.getTimestamp("round_started_at").toInstant(),r.getInt("query_count"),r.getInt("round"),r.getObject("next_job",UUID.class)));
  return rows.isEmpty()?null:rows.getFirst();
 }
 private Orders.Snapshot authorized(String number,Authority a) { if(a==null||a.browser()==null) { throw new OrderRejected(401); }return orders.authorize(number,a.browser(),a.member(),a.version(),a.guestToken()); }
 private void outside() { if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("PAYMENT_COORDINATOR_REQUIRES_OUTSIDE_TRANSACTION"); } }
 @Override public View approve(String number,Authority authority) {
  return approve(number,authority,null);
 }
 public SandboxCheckout sandbox(String number,Authority authority) {
  return tx.execute(status->{var order=authorized(number,authority);if(!order.mode().equals("SANDBOX")||!order.state().equals("PENDING_PAYMENT")||order.quote().total()<=0||!clock.instant().isBefore(order.expiresAt())||attempt(number)!=null) { throw new OrderRejected(409); }if(!sandboxSettings.ready()) { throw new OrderRejected(503); }validate(order,true);authorized(number,authority);return new SandboxCheckout(sandboxSettings.clientKey(),order.id().toString(),Long.toString(order.quote().total()),order.id().toString());});
 }
 public View confirm(String number,Authority authority,Confirmation confirmation) {
  if(confirmation==null||confirmation.paymentKey()==null||confirmation.paymentKey().isBlank()||confirmation.paymentKey().length()>200||confirmation.paymentKey().chars().anyMatch(c->c<32||c==127)||confirmation.orderId()==null||confirmation.amount()==null||!confirmation.amount().matches("[1-9][0-9]*")) { throw new OrderRejected(400); }
  return approve(number,authority,confirmation);
 }
 private View approve(String number,Authority authority,Confirmation confirmation) {
  outside();var fresh=new java.util.concurrent.atomic.AtomicBoolean();
  Attempt prepared=tx.execute(status->{var order=authorized(number,authority);
   if(confirmation!=null) {
    if(!order.mode().equals("SANDBOX")) { throw new OrderRejected(409); }
    if(!order.id().toString().equals(confirmation.orderId())||!Long.toString(order.quote().total()).equals(confirmation.amount())) { throw new OrderRejected(400); }
    if(!sandboxSettings.ready()) { throw new OrderRejected(503); }
   }else if(order.mode().equals("SANDBOX")&&order.quote().total()>0) { throw new OrderRejected(409); }
   var prior=attempt(number);if(prior!=null) {
    if(confirmation!=null&&!confirmation.paymentKey().equals(jdbc.queryForObject("SELECT provider_payment_key FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",prior.id()),String.class))) { throw new OrderRejected(400); }checkReview(prior);return prior;
   }
   if(!order.state().equals("PENDING_PAYMENT")||!clock.instant().isBefore(order.expiresAt())) { throw new OrderRejected(409); }
   validate(order,true);
   // Recheck deadlines after the last reservation wait. Authority/order rows are already held by this transaction.
   authorized(number,authority);UUID id=UUID.randomUUID();
   jdbc.update("INSERT INTO marketplace.payment_attempt(id,order_id,order_number,state,amount,mode,started_at,round_started_at) VALUES(:id,:order,:n,'CREATED',:amount,:mode,:now,:now)",Map.of("id",id,"order",order.id(),"n",number,"amount",order.quote().total(),"mode",order.mode(),"now",Timestamp.from(clock.instant())));
   if(confirmation!=null&&jdbc.update("INSERT INTO marketplace.payment_toss_binding(attempt_id,provider_payment_key,provider_order_id,amount) VALUES(:id,:key,:order,:amount) ON CONFLICT DO NOTHING",Map.of("id",id,"key",confirmation.paymentKey(),"order",order.id().toString(),"amount",order.quote().total()))!=1) { throw new OrderRejected(400); }
   event(id,"CREATED",null,null,"created");jdbc.update("UPDATE marketplace.payment_attempt SET state='PROCESSING' WHERE id=:id",Map.of("id",id));event(id,"PROCESSING",null,null,"processing");
   var a=attempt(number);schedule(a,"PAYMENT_QUERY",clock.instant().plusSeconds(60));after("PREPARE");
   if(a.amount()==0) { applyLocked(order,Result.SUCCEEDED);return attempt(number); }
   fresh.set(true);return a;
  });
  after("PREPARED");
  if(fresh.get()) { Result result=provider.approve(prepared.request());after("PROVIDER");apply(number,result); }
  return tx.execute(status->{orders.lock(number);return view(number);});
 }
 public void webhook(byte[] payload,String time,String signature,String transmissionId) {
  outside();if(sandboxSettings.webhookSecurityKey().isBlank()) { throw new OrderRejected(503); }
  if(!new TossWebhookVerifier(sandboxSettings.webhookSecurityKey(),clock).authentic(payload,time,signature)||transmissionId==null||!transmissionId.matches("[A-Za-z0-9_-]{1,200}")) { throw new OrderRejected(403); }
  try {
   var root=new tools.jackson.databind.ObjectMapper().readTree(payload);var data=root.path("data");
   if(!root.path("eventType").asText().equals("PAYMENT_STATUS_CHANGED")||!root.path("createdAt").isString()||!sandboxSettings.mid().equals(data.path("mId").asText())||!sandboxSettings.apiVersion().equals(data.path("version").asText())||!data.path("totalAmount").isIntegralNumber()||!data.path("totalAmount").canConvertToLong()||data.path("totalAmount").asLong()<=0) { throw new OrderRejected(409); }
   var rows=jdbc.queryForList("SELECT a.order_number FROM marketplace.payment_toss_binding b JOIN marketplace.payment_attempt a ON a.id=b.attempt_id WHERE b.provider_order_id=:order AND b.provider_payment_key=:key AND a.mode='SANDBOX' AND a.amount=b.amount AND b.amount=:amount",Map.of("order",data.path("orderId").asText(),"key",data.path("paymentKey").asText(),"amount",data.path("totalAmount").asLong()));
   if(rows.size()!=1) { throw new OrderRejected(409); }String number=(String)rows.getFirst().get("order_number");var a=attempt(number);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
   if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt WHERE attempt_id=:id AND payload_sha256=:digest",Map.of("id",a.id(),"digest",digest),Integer.class)>0) { return; }
   String status=data.path("status").asText();Result hint=status.equals("DONE")?Result.SUCCEEDED:List.of("ABORTED","EXPIRED","CANCELED").contains(status)?Result.FAILED:Result.UNKNOWN;
   if(hint==Result.UNKNOWN||provider.query(a.request())!=hint) { throw new OrderRejected(409); }
   applyWebhook(number,a.id(),digest,hint);
  }catch(OrderRejected rejected) { throw rejected; }catch(Exception invalid) { throw new OrderRejected(409); }
 }
 private void receipt(Attempt a,String digest) { jdbc.update("INSERT INTO marketplace.payment_webhook_receipt(attempt_id,payload_sha256) VALUES(:id,:digest) ON CONFLICT DO NOTHING",Map.of("id",a.id(),"digest",digest)); }
 private void applyWebhook(String number,UUID attemptId,String digest,Result result) {
  try { tx.executeWithoutResult(status->{var order=orders.lock(number);var a=attempt(number);if(a==null||!a.id().equals(attemptId)) { throw new OrderRejected(409); }if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt WHERE attempt_id=:id AND payload_sha256=:digest",Map.of("id",a.id(),"digest",digest),Integer.class)>0) { return; }applyLocked(order,result);receipt(a,digest);}); }
  catch(com.example.marketplace.inventory.api.InventoryException|com.example.marketplace.promotion.api.PromotionRejected conflict) { if(result!=Result.SUCCEEDED) { throw conflict; }tx.executeWithoutResult(status->{orders.lock(number);var a=attempt(number);compensationLocked(a,"RESERVATION_CONFLICT");receipt(a,digest);}); }
  catch(OrderRejected conflict) { if(result!=Result.SUCCEEDED||conflict.status()!=409) { throw conflict; }tx.executeWithoutResult(status->{orders.lock(number);var a=attempt(number);compensationLocked(a,"RESERVATION_CONFLICT");receipt(a,digest);}); }
 }
 @Override public View get(String number,Authority authority) { return tx.execute(status->{authorized(number,authority);var a=attempt(number);if(a!=null) { checkReview(a); }return view(number);}); }
 private View view(String number) { var order=orders.find(number);var a=attempt(number);return new View(number,order.state(),a==null?"NONE":a.state(),a==null?"NONE":a.compensation(),a!=null&&a.review(),Long.toString(order.quote().total()),order.mode(),order.mode().equals("SANDBOX")?order.id().toString():null); }
 private Orders.ReservationIds validate(Orders.Snapshot order,boolean preparing) {
  var ids=orders.reservations(order.orderNumber());var items=order.sellers().stream().flatMap(s->s.items().stream()).toList();
  if(ids.inventory().size()!=items.size()||ids.coupons().size()!=order.quote().stores().stream().filter(s->s.coupon()!=null).count()) { throw new OrderRejected(409); }
  var cs=ids.coupons().isEmpty()?List.<CouponReservations.Reservation>of():coupons.inspect(order.checkoutId(),ids.coupons());
  var stocks=inventory.inspect(order.checkoutId(),ids.inventory());
  if(stocks.size()!=items.size()||stocks.stream().anyMatch(r->items.stream().noneMatch(i->i.id().equals(r.orderItemId())&&i.skuId().equals(r.skuId())&&i.quantity()==r.quantity()))||cs.stream().anyMatch(r->order.sellers().stream().noneMatch(s->s.id().equals(r.sellerOrderRef())&&s.storeId().equals(r.storeId()))||order.quote().stores().stream().noneMatch(s->s.storeId().equals(r.storeId())&&s.coupon()!=null&&s.coupon().id().equals(r.issuedCouponId())&&java.util.Objects.equals(order.memberId(),r.memberId())))) { throw new OrderRejected(409); }
  if(preparing&&(stocks.stream().anyMatch(r->!r.expiresAt().equals(order.expiresAt()))||cs.stream().anyMatch(r->!r.expiresAt().equals(order.expiresAt())))) { throw new OrderRejected(409); }
  if(preparing&&(stocks.stream().anyMatch(r->!r.status().equals("RESERVED")||!clock.instant().isBefore(r.expiresAt()))||cs.stream().anyMatch(r->!r.state().equals("RESERVED")||!clock.instant().isBefore(r.expiresAt())))) { throw new OrderRejected(409); }
  return ids;
 }
 public void apply(String number,Result result) {
  outside();try { tx.executeWithoutResult(status->applyLocked(orders.lock(number),result)); }
  catch(com.example.marketplace.inventory.api.InventoryException|com.example.marketplace.promotion.api.PromotionRejected conflict) {
   if(result!=Result.SUCCEEDED) { throw conflict; }compensate(number,"RESERVATION_CONFLICT");
  }catch(OrderRejected conflict) { if(result!=Result.SUCCEEDED||conflict.status()!=409) { throw conflict; }compensate(number,"RESERVATION_CONFLICT"); }
 }
 private void applyLocked(Orders.Snapshot order,Result result) {
  var a=attempt(order.orderNumber());if(a==null) { throw new OrderRejected(404); }
  if(result==Result.SUCCEEDED) {
   if(order.state().equals("CANCELED")&&a.state().equals("SUCCEEDED")&&a.compensation().equals("NONE")&&aftercareSettlements!=null&&aftercareSettlements.customerCancellationSettled(order.id(),a.id())) { return; }
   if(order.state().equals("CLOSED")) { if(a.state().equals("SUCCEEDED")&&a.compensation().equals("NONE")) { return; }review(a,"CLOSED_PAYMENT_CONFLICT");return; }
   if(order.state().equals("PAID")||!a.compensation().equals("NONE")) { return; }
   if(!order.state().equals("PENDING_PAYMENT")||!clock.instant().isBefore(order.expiresAt())||a.state().equals("FAILED")) {
    if(a.amount()==0) { throw new OrderRejected(409); }
    compensationLocked(a,"LATE_SUCCESS");return;
   }
   var ids=validate(order,false);
   if(!ids.coupons().isEmpty()&&coupons.commit(order.checkoutId(),ids.coupons()).stream().anyMatch(r->!r.state().equals("COMMITTED"))) { throw new OrderRejected(409); }after("COUPON");
   if(inventory.commit(order.checkoutId(),ids.inventory()).stream().anyMatch(r->!r.status().equals("COMMITTED"))) { throw new OrderRejected(409); }after("INVENTORY");
   orders.transition(order.orderNumber(),"PAID");after("ORDER");setState(a,"SUCCEEDED");event(a.id(),"SUCCEEDED",null,null,"succeeded");after("AUDIT");
   append(a,"PAYMENT_SETTLED","settled",clock.instant());after("OUTBOX");
  }else if(PaymentRules.uncertain(a.state())) {
   if(result==Result.FAILED) { release(order);orders.transition(order.orderNumber(),clock.instant().isBefore(order.expiresAt())?"CANCELED":"EXPIRED");setState(a,"FAILED");event(a.id(),"FAILED",null,null,"failed");append(a,"PAYMENT_SETTLED","failed",clock.instant()); }
   else { setState(a,"UNKNOWN");event(a.id(),"UNKNOWN",null,null,"unknown"); }
  }
 }
 private void setState(Attempt a,String state) { jdbc.update("UPDATE marketplace.payment_attempt SET state=:s,review_required=false,review_reason=null,version=version+1 WHERE id=:id",Map.of("id",a.id(),"s",state)); }
 private void release(Orders.Snapshot order) {
  var ids=validate(order,false);var cs=ids.coupons().isEmpty()?List.<CouponReservations.Reservation>of():coupons.inspect(order.checkoutId(),ids.coupons());
  var pending=cs.stream().filter(r->r.state().equals("RESERVED")).map(CouponReservations.Reservation::id).toList();
  if(cs.stream().anyMatch(r->!List.of("RESERVED","RELEASED","EXPIRED").contains(r.state()))) { throw new OrderRejected(409); }
  if(!pending.isEmpty()&&coupons.release(order.checkoutId(),pending).stream().anyMatch(r->!r.state().equals("RELEASED"))) { throw new OrderRejected(409); }
  var stocks=inventory.release(order.checkoutId(),ids.inventory());if(stocks.stream().anyMatch(r->!r.status().equals("RELEASED"))) { throw new OrderRejected(409); }
 }
 private void compensate(String number,String reason) { tx.executeWithoutResult(status->{orders.lock(number);compensationLocked(attempt(number),reason);}); }
 private void compensationLocked(Attempt a,String reason) {
  if(!a.compensation().equals("NONE")) { return; }
  jdbc.update("UPDATE marketplace.payment_attempt SET state='SUCCEEDED',compensation='PENDING',query_count=0,round=round+1,round_started_at=:now,review_required=false,review_reason=null WHERE id=:id",Map.of("id",a.id(),"now",Timestamp.from(clock.instant())));
  event(a.id(),"COMPENSATION_PENDING",null,reason,"compensation");schedule(attempt(a.number()),"PAYMENT_COMPENSATE",clock.instant());
 }
 public void expire(String number) {
  tx.executeWithoutResult(status->{var order=orders.lock(number);if(!order.state().equals("PENDING_PAYMENT")||clock.instant().isBefore(order.expiresAt())) { return; }var a=attempt(number);
   if(a!=null&&(PaymentRules.uncertain(a.state())||!a.compensation().equals("NONE"))) { checkReview(a);return; }
   if(a!=null&&a.state().equals("SUCCEEDED")) { return; }release(order);orders.transition(number,"EXPIRED");
  });
 }
 public void query(String number,int round,int expectedCount,boolean compensation) {
  outside();Attempt a=tx.execute(status->{orders.lock(number);var current=attempt(number);if(current==null||current.round()!=round||current.count()!=expectedCount||checkReview(current)) { return null; }
   if(compensation?List.of("NONE","CANCELED","FAILED").contains(current.compensation()):!PaymentRules.uncertain(current.state())) { return null; }return current;});
  if(a==null) { return; }
  Result result=compensation?(a.compensation().equals("PENDING")?provider.cancel(a.request()):provider.queryCancellation(a.request())):provider.query(a.request());after(compensation?"CANCEL_PROVIDER":"QUERY_PROVIDER");
  if(!compensation) { apply(number,result); }
  tx.executeWithoutResult(status->{var order=orders.lock(number);var current=attempt(number);if(current.round()!=round||current.count()!=expectedCount) { return; }
   if(compensation&&!current.compensation().equals("CANCELED")) {
    if(result==Result.SUCCEEDED) { release(order);if(order.state().equals("PENDING_PAYMENT")) { orders.transition(number,"CANCELED"); }jdbc.update("UPDATE marketplace.payment_attempt SET compensation='CANCELED',review_required=false,review_reason=null WHERE id=:id",Map.of("id",a.id()));event(a.id(),"COMPENSATED",null,null,"compensated");append(a,"PAYMENT_SETTLED","compensated",clock.instant()); }
    else { jdbc.update("UPDATE marketplace.payment_attempt SET compensation=:s WHERE id=:id",Map.of("id",a.id(),"s",result==Result.FAILED?"FAILED":"UNKNOWN"));if(result==Result.FAILED) { review(a,"COMPENSATION_FAILED"); } }
   }
   jdbc.update("UPDATE marketplace.payment_attempt SET query_count=query_count+1 WHERE id=:id",Map.of("id",a.id()));current=attempt(number);
   boolean pending=compensation?current.compensation().equals("UNKNOWN"):PaymentRules.uncertain(current.state());
   if(pending&&!checkReview(current)) { schedule(current,compensation?"PAYMENT_COMPENSATE":"PAYMENT_QUERY",clock.instant().plusSeconds(60)); }
   after("QUERY_RESULT");
  });
 }
 private boolean checkReview(Attempt a) {
  boolean pending=PaymentRules.uncertain(a.state())||List.of("PENDING","UNKNOWN","FAILED").contains(a.compensation());if(!pending) { return false; }
  if(a.review()) { return true; }
  if(PaymentRules.review(a.count(),a.started(),clock.instant())) { review(a,"QUERY_LIMIT");return true; }
  if(a.job()!=null&&jobs.find(a.job()).map(j->j.status().equals("FAILED")).orElse(false)) { review(a,"DELIVERY_EXHAUSTED");return true; }return false;
 }
 private void review(Attempt a,String reason) { jdbc.update("UPDATE marketplace.payment_attempt SET review_required=true,review_reason=:r WHERE id=:id",Map.of("id",a.id(),"r",reason));event(a.id(),"REVIEW_REQUIRED",null,reason,"review:"+a.round()); }
 private void operator(Authority a) { if(a==null||a.member()==null) { throw new OrderRejected(401); }members.lockActiveBuyer(a.member());if(!sessions.lockCurrent(a.browser(),a.member(),a.version())) { throw new OrderRejected(401); }access.require(a.member(),AccessArea.SYSTEM); }
 public List<View> reviewQueue(Authority authority) { return tx.execute(status->{operator(authority);return jdbc.queryForList("SELECT order_number FROM marketplace.payment_attempt ORDER BY order_number",Map.of(),String.class).stream().map(number->{orders.lock(number);checkReview(attempt(number));return view(number);}).filter(View::reviewRequired).toList();}); }
 @Override public View reconcile(String number,Authority authority,String reason) {
  if(reason==null||reason.isBlank()||reason.length()>300) { throw new OrderRejected(400); }
  return tx.execute(status->{operator(authority);orders.lock(number);var a=attempt(number);if(a==null||!checkReview(a)) { throw new OrderRejected(409); }
   jdbc.update("UPDATE marketplace.payment_attempt SET review_required=false,review_reason=null,query_count=0,round=round+1,round_started_at=:now,compensation=CASE WHEN compensation='FAILED' THEN 'UNKNOWN' ELSE compensation END WHERE id=:id",Map.of("id",a.id(),"now",Timestamp.from(clock.instant())));
   var next=attempt(number);event(a.id(),"RECONCILE_REQUESTED",authority.member(),reason,"reconcile:"+next.round());schedule(next,next.compensation().equals("NONE")?"PAYMENT_QUERY":"PAYMENT_COMPENSATE",clock.instant());return view(number);
  });
 }
 private void schedule(Attempt a,String type,Instant at) { UUID job=append(a,type,type+":"+a.round()+":"+a.count(),at);jdbc.update("UPDATE marketplace.payment_attempt SET next_job=:job WHERE id=:id",Map.of("id",a.id(),"job",job)); }
 private UUID append(Attempt a,String type,String key,Instant at) { UUID id=UUID.nameUUIDFromBytes((a.id()+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));return outbox.append(new Outbox.Event(id,new Jobs.Command(type,"payment:"+a.id()+":"+key,a.id().toString(),a.number()+":"+a.round()+":"+a.count(),at))); }
 private void event(UUID id,String action,UUID actor,String reason,String key) { var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("attempt",id).addValue("action",action).addValue("actor",actor).addValue("reason",reason).addValue("key",id+":"+key);jdbc.update("INSERT INTO marketplace.payment_event(id,attempt_id,action,actor,reason,event_key) VALUES(:id,:attempt,:action,:actor,:reason,:key) ON CONFLICT(event_key) DO NOTHING",p); }
 private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException failure) { throw failure; }catch(Exception failure) { throw new IllegalStateException("PAYMENT_CHECKPOINT",failure); } }
}
