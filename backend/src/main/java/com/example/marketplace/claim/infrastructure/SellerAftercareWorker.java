package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.domain.AftercareShippingRules;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seller-wide original shipping decision, independent of any terminal ITEM hold. */
@Service @Profile("db")
public final class SellerAftercareWorker {
 public static final String TYPE="SELLER_AFTERCARE_REEVALUATE.v1";
 private final NamedParameterJdbcTemplate jdbc;private final AftercareSettlements orders;private final FinancialIntents intents;
 private final Refunds refunds;private final Jobs jobs;private final JobLeases leases;private final TransactionTemplate tx;
 private final com.example.marketplace.order.api.ReturnApplications applications;private final com.example.marketplace.order.api.OrderUnitHolds holds;private final ReturnCommands commands;private final com.example.marketplace.order.api.Orders snapshots;private final com.example.marketplace.promotion.api.CouponReservations coupons;private final com.example.marketplace.platform.api.Outbox outbox;
 private final ExchangeRefundCompleter exchangeRefunds;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public SellerAftercareWorker(NamedParameterJdbcTemplate jdbc,AftercareSettlements orders,FinancialIntents intents,Refunds refunds,Jobs jobs,JobLeases leases,com.example.marketplace.order.api.ReturnApplications applications,com.example.marketplace.order.api.OrderUnitHolds holds,ReturnCommands commands,com.example.marketplace.order.api.Orders snapshots,com.example.marketplace.promotion.api.CouponReservations coupons,com.example.marketplace.platform.api.Outbox outbox,PlatformTransactionManager manager,ExchangeRefundCompleter exchangeRefunds) {
  this.jdbc=jdbc;this.orders=orders;this.intents=intents;this.refunds=refunds;this.jobs=jobs;this.leases=leases;this.applications=applications;this.holds=holds;this.commands=commands;this.snapshots=snapshots;this.coupons=coupons;this.outbox=outbox;tx=new TransactionTemplate(manager);this.exchangeRefunds=exchangeRefunds;
 }
 private record Route(UUID seller,String kind,UUID source,long version) { String payload() { return kind+"|"+source+(kind.equals("SHIPPING_RESULT")?"|"+version:""); }String key() { return "seller-aftercare:"+seller+":"+switch(kind) { case "SHIPPING_RESULT"->"shipping-result:"+source+":"+version;case "CANCEL_ITEM"->"cancel-item:"+source;case "CONFIRMATION"->"confirmation:"+source;case "RETURN_READY"->"return-ready:"+source;case "EXCHANGE_REFUND_READY"->"exchange-refund-ready:"+source;default->throw new OrderRejected(409); }; } }
 private Route route(Jobs.Job job) {
  try { if(job==null||job.ownerToken()==null||!TYPE.equals(job.command().type())) { throw new IllegalArgumentException(); }String[] parts=job.command().payload().split("\\|",-1);if(!(parts.length==2&&List.of("RETURN_READY","EXCHANGE_REFUND_READY","CANCEL_ITEM","CONFIRMATION").contains(parts[0])||parts.length==3&&parts[0].equals("SHIPPING_RESULT"))) { throw new IllegalArgumentException(); }var route=new Route(UUID.fromString(job.command().causeKey()),parts[0],UUID.fromString(parts[1]),parts.length==3?Long.parseLong(parts[2]):0);if(route.kind().equals("SHIPPING_RESULT")&&route.version()<1||!route.payload().equals(job.command().payload())) { throw new IllegalArgumentException(); }return route; }
  catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
 }
 private void storedJob(Jobs.Job job,Route route) {
  var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));var command=actual.command();
  if(!actual.status().equals("RUNNING")||!job.ownerToken().equals(actual.ownerToken())||!command.equals(job.command())||!command.type().equals(TYPE)||!command.causeKey().equals(route.seller().toString())||!command.payload().equals(route.payload())) { throw new OrderRejected(409); }
  if(route.key().equals(command.dedupeKey())) { return; }
  if(route.kind().equals("EXCHANGE_REFUND_READY")){var delivery=ExchangeRefundRecoveryDeliveries.require(jdbc,actual,ExchangeRefundRecoveryDeliveries.Stage.SELLER_REEVALUATE);if(!delivery.exchangeId().equals(route.source())||!delivery.sellerOrderId().equals(route.seller())){throw new OrderRejected(409);}return;}
  if(!route.kind().equals("RETURN_READY")) { throw new OrderRejected(409); }
  var delivery=ReturnRecoveryDeliveries.require(jdbc,actual,ReturnRecoveryDeliveries.Stage.SELLER_REEVALUATE);
  if(!route.kind().equals("RETURN_READY")||!delivery.returnId().equals(route.source())||!delivery.sellerOrderId().equals(route.seller())) { throw new OrderRejected(409); }
 }
 public JobHandler.Result reevaluate(Jobs.Job job) {
  var route=route(job);storedJob(job,route);
  return tx.execute(t->{
   // The order port locks its parent and complete original unit set before any claim evidence.
   var view=orders.lockAftercare(route.seller());
   if(route.kind().equals("RETURN_READY")) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_item_ready WHERE return_id=:source AND seller_order_id=:seller AND order_id=:order",Map.of("source",route.source(),"seller",route.seller(),"order",view.orderId()),Integer.class)!=1) { throw new OrderRejected(409); } }
   else if(route.kind().equals("EXCHANGE_REFUND_READY")) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_item_ready WHERE exchange_id=:source AND seller_order_id=:seller AND order_id=:order",Map.of("source",route.source(),"seller",route.seller(),"order",view.orderId()),Integer.class)!=1) { throw new OrderRejected(409); } }
   else if(route.kind().equals("SHIPPING_RESULT")) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_result WHERE attempt_id=:source AND seller_order_id=:seller AND result_version=:version",Map.of("source",route.source(),"seller",route.seller(),"version",route.version()),Integer.class)!=1) { throw new OrderRejected(409); } }
   else if(route.kind().equals("CANCEL_ITEM")) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim c JOIN marketplace.claim_refund_effect e ON e.claim_id=c.id AND e.purpose='ITEM' WHERE c.id=:id AND c.seller_order_id=:seller AND c.order_id=:order",Map.of("id",route.source(),"seller",route.seller(),"order",view.orderId()),Integer.class)!=1) { throw new OrderRejected(409); } }
   else { var fact=orders.lockConfirmation(route.source());if(!fact.orderId().equals(view.orderId())||fact.units().stream().noneMatch(u->u.sellerOrderId().equals(view.sellerOrderId()))) { throw new OrderRejected(409); } }
   var evidence=ReturnAftercareEvidence.units(jdbc,view);var evaluation=AftercareShippingRules.evaluate(view.originalShipping(),evidence);
   if(evaluation.state()==AftercareShippingRules.State.RETAINED&&jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",view.sellerOrderId()),Integer.class)!=0) { throw new OrderRejected(409); }
   String fingerprint=ReturnAftercareEvidence.fingerprint(view,evidence,evaluation);
   var p=new MapSqlParameterSource("seller",route.seller()).addValue("order",view.orderId()).addValue("hash",fingerprint);
   var prior=jdbc.queryForList("SELECT id FROM marketplace.claim_seller_shipping_decision WHERE seller_order_id=:seller AND fingerprint=:hash",p,UUID.class);
   UUID decision;if(prior.isEmpty()) {
    decision=UUID.randomUUID();long revision=jdbc.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM marketplace.claim_seller_shipping_decision WHERE seller_order_id=:seller",p,Long.class);
    var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class);
    p.addValue("id",decision).addValue("revision",revision).addValue("state",evaluation.state().name()).addValue("reason",evaluation.reason().name()).addValue("amount",evaluation.amount()).addValue("units",mapper.writeValueAsString(evidence)).addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("at",at);
    jdbc.update("INSERT INTO marketplace.claim_seller_shipping_decision(id,seller_order_id,order_id,revision,state,reason,amount,fingerprint,units,job_id,owner_token,occurred_at) VALUES(:id,:seller,:order,:revision,:state,:reason,:amount,:hash,CAST(:units AS jsonb),:job,:owner,:at)",p);
   }else { decision=prior.getFirst(); }
   jdbc.update("INSERT INTO marketplace.claim_seller_shipping_current(seller_order_id,decision_id) VALUES(:seller,:decision) ON CONFLICT(seller_order_id) DO UPDATE SET decision_id=EXCLUDED.decision_id",p.addValue("decision",decision));
   long decisionRevision=jdbc.queryForObject("SELECT revision FROM marketplace.claim_seller_shipping_decision WHERE id=:id",Map.of("id",decision),Long.class);
   orders.recordShippingDecision(new AftercareSettlements.ShippingDecision(decision,view.orderId(),view.sellerOrderId(),decisionRevision,evaluation.state().name(),evaluation.reason().name(),view.originalShipping(),fingerprint,view.units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList()));
   if(evaluation.state()==AftercareShippingRules.State.REFUND_REQUIRED) { SellerShippingReservations.reserve(jdbc,intents,refunds,view,evidence,null); }
   completeReady(view,decision,evaluation,job);
   exchangeRefunds.completeReady(view,decision,evaluation,job);
   restoreCouponIfWhole(view,evaluation);
   orders.evaluate(view.orderId());
   storedJob(job,route);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
  });
 }
 private static UUID named(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)); }
 private void completeReady(AftercareSettlements.AftercareView view,UUID decision,AftercareShippingRules.Evaluation evaluation,Jobs.Job job) {
  if(evaluation.state()==AftercareShippingRules.State.REFUND_REQUIRED&&jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id AND state='SUCCEEDED'",Map.of("id",view.sellerOrderId()),Integer.class)!=1) { return; }
  var pending=jdbc.queryForList("SELECT x.return_id FROM marketplace.claim_return_item_ready x LEFT JOIN marketplace.claim_return_completion c ON c.return_id=x.return_id WHERE x.seller_order_id=:seller AND c.return_id IS NULL ORDER BY x.return_id::text",Map.of("seller",view.sellerOrderId()),UUID.class);
  for(UUID id:pending) {
   var recorded=applications.lockRecorded(id);var app=recorded.application();var intent=intents.lock(id);var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",id));var ready=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_item_ready WHERE return_id=:id",Map.of("id",id));
   if(recorded.holdState()!=com.example.marketplace.order.api.ReturnApplications.RecordedHoldState.ACTIVE||!recorded.orderState().equals("PAID")||!row.get("state").equals("REFUND_PENDING")||!intent.kind().equals("RETURN")||!app.orderId().equals(view.orderId())||!app.sellerOrderId().equals(view.sellerOrderId())||!intent.holdId().equals(app.holdId())||!intent.causeId().equals(app.causeId())) { throw new OrderRejected(409); }
   var actual=refunds.get((UUID)ready.get("attempt_id"));if(!actual.state().equals("SUCCEEDED")||!actual.purpose().equals("ITEM")||!actual.claimId().equals(id)||actual.version()!=((Number)ready.get("result_version")).longValue()) { throw new OrderRejected(409); }
   holds.completeRefund(view.orderId(),app.holdId(),app.causeId(),actual.resultReference());
   orders.recordReturnCompletion(new AftercareSettlements.ReturnCompletion(id,view.orderId(),view.sellerOrderId(),app.holdId(),app.causeId(),intent.units(),actual.paymentId(),actual.id(),actual.resultReference()));
   long revision=Math.incrementExact(((Number)row.get("version")).longValue());var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class);String hash=ReturnFeeQuoting.hash(id+"|"+ready+"|"+decision+"|"+revision);
   var p=new MapSqlParameterSource("id",id).addValue("decision",decision).addValue("revision",revision).addValue("hash",hash).addValue("at",at).addValue("event",UUID.randomUUID()).addValue("key","complete-"+id).addValue("job",job.id()).addValue("owner",job.ownerToken());
   jdbc.update("UPDATE marketplace.claim_return_request SET state='COMPLETED',version=:revision WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,'COMPLETED',:revision,'RECORDED_WORKER','RETURN_COMPLETED',:at)",p);
   String receipt=mapper.writeValueAsString(commands.progress(jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id))));p.addValue("receipt",receipt);
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,'COMPLETED',:revision,CAST(:receipt AS jsonb))",p);
   jdbc.update("INSERT INTO marketplace.claim_return_completion(return_id,decision_id,workflow_revision,fingerprint,receipt,job_id,owner_token,occurred_at) VALUES(:id,:decision,:revision,:hash,CAST(:receipt AS jsonb),:job,:owner,:at)",p);
   String key="return-command:"+id+":"+revision;outbox.append(new com.example.marketplace.platform.api.Outbox.Event(named(key),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),at.toInstant())));
  }
 }
 private void restoreCouponIfWhole(AftercareSettlements.AftercareView original,AftercareShippingRules.Evaluation evaluation) {
  if(evaluation.state()==AftercareShippingRules.State.PENDING) { return; }var view=orders.lockAftercare(original.sellerOrderId());
  if(view.units().stream().anyMatch(u->!u.state().equals("REFUNDED")||u.cancellationCauseId()==null&&u.returnCompletionId()==null&&u.exchangeRefundCompletionId()==null)) { return; }
  if(evaluation.state()==AftercareShippingRules.State.REFUND_REQUIRED) {
   var obligation=jdbc.queryForMap("SELECT * FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",view.sellerOrderId()));var recorded=orders.lockShippingSettlement(view.sellerOrderId());
   if(recorded.isEmpty()) { if(obligation.get("state").equals("SUCCEEDED")) { throw new OrderRejected(409); }return; }
   UUID origin=(UUID)obligation.get("claim_id");var actual=refunds.get((UUID)obligation.get("refund_attempt_id"));
   if(!obligation.get("state").equals("SUCCEEDED")||!obligation.get("order_id").equals(view.orderId())||((Number)obligation.get("amount")).longValue()!=view.originalShipping()||!actual.state().equals("SUCCEEDED")||!actual.purpose().equals("SHIPPING")||!actual.orderId().equals(view.orderId())||!actual.sellerOrderId().equals(view.sellerOrderId())||!actual.claimId().equals(origin)||actual.amount()!=view.originalShipping()||!refunds.originalReference(actual.id()).equals(view.quoteFingerprint())||!recorded.get().equals(new AftercareSettlements.Shipping(view.orderId(),view.sellerOrderId(),origin,actual.paymentId(),actual.id(),actual.amount(),actual.resultReference()))) { throw new OrderRejected(409); }
   String kind=jdbc.queryForObject("SELECT kind FROM marketplace.claim_financial_intent WHERE id=:id",Map.of("id",origin),String.class);
   if(!List.of("RETURN","CANCEL","EXCHANGE_REFUND").contains(kind)) { throw new OrderRejected(409); }
   String sql=List.of("RETURN","EXCHANGE_REFUND").contains(kind)?"SELECT count(*) FROM marketplace.claim_shipping_result WHERE attempt_id=:attempt AND result_version=:version AND intent_id=:origin AND seller_order_id=:seller AND result_state='SUCCEEDED' AND reference=:ref":"SELECT count(*) FROM marketplace.claim_refund_effect WHERE attempt_id=:attempt AND result_version=:version AND claim_id=:origin AND purpose='SHIPPING' AND reference=:ref";
   if(jdbc.queryForObject(sql,Map.of("attempt",actual.id(),"version",actual.version(),"origin",origin,"seller",view.sellerOrderId(),"ref",actual.resultReference()),Integer.class)!=1) { throw new OrderRejected(409); }
  }
  var ids=snapshots.reservations(view.orderNumber()).coupons();if(ids.isEmpty()) { return; }
  for(var coupon:coupons.inspect(view.checkoutId(),ids)) { if(coupon.sellerOrderRef().equals(view.sellerOrderId())) { coupons.restore(new com.example.marketplace.promotion.api.CouponReservations.RestoreRequest(named("cancel-coupon:"+coupon.id()),coupon.sellerOrderRef(),coupon.id(),new com.example.marketplace.promotion.api.CouponReservations.RefundCompletion(true,true))); } }
 }
}
