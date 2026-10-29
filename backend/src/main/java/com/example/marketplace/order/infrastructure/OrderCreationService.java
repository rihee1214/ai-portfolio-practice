package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.promotion.api.Quotes;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import com.example.marketplace.catalog.api.PurchaseOffers;
import com.example.marketplace.guest.api.GuestOrders;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.promotion.api.CouponReservations;
import com.example.marketplace.inventory.api.Reservations;
import com.example.marketplace.platform.api.Outbox;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.order.domain.OrderRequestHash;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class OrderCreationService {
    public record Input(String requestKey,List<Quotes.LineRequest> lines,List<Quotes.CouponSelection> coupons,
            String fingerprint,Orders.Contact contact,UUID guestAttemptId,Integer linkRevision) { }
    @FunctionalInterface public interface Checkpoint { void after(String stage) throws Exception; }
    private final NamedParameterJdbcTemplate jdbc;
    private final Orders orders;
    private final Quotes quotes;
    private final PurchaseOffers offers;
    private final GuestOrders guests;
    private final SellerMembership members;
    private final CouponReservations coupons;
    private final Reservations inventory;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final String mode;
    private final Checkpoint checkpoint;
    private com.example.marketplace.identity.api.PurchaseSessions sessions;
    @Autowired public OrderCreationService(NamedParameterJdbcTemplate jdbc,Orders orders,Quotes quotes,PurchaseOffers offers,
            GuestOrders guests,SellerMembership members,CouponReservations coupons,Reservations inventory,Outbox outbox,
            PlatformTransactionManager manager,@Value("${marketplace.payment.mode:DEMO}") String mode,com.example.marketplace.identity.api.PurchaseSessions sessions) {
        this(jdbc,orders,quotes,offers,guests,members,coupons,inventory,outbox,manager,mode,stage->{ });
        this.sessions=sessions;
    }
    public OrderCreationService(NamedParameterJdbcTemplate jdbc,Orders orders,Quotes quotes,PurchaseOffers offers,
            GuestOrders guests,SellerMembership members,CouponReservations coupons,Reservations inventory,Outbox outbox,
            PlatformTransactionManager manager,String mode,Checkpoint checkpoint) {
        this.jdbc=jdbc;this.orders=orders;this.quotes=quotes;this.offers=offers;this.guests=guests;this.members=members;
        this.coupons=coupons;this.inventory=inventory;this.outbox=outbox;this.tx=new TransactionTemplate(manager);this.mode=mode;this.checkpoint=checkpoint;
        if(!List.of("DEMO","SANDBOX").contains(mode)) { throw new IllegalArgumentException("INVALID_PAYMENT_MODE"); }
    }
    public Orders.Snapshot create(String browser,UUID member,String guestToken,Input input) {
        return create(browser,member,guestToken,input,false,null);
    }
    public Orders.Snapshot createForSession(String browser,UUID member,Long authVersion,String guestToken,Input input) {
        return create(browser,member,guestToken,input,true,authVersion);
    }
    private Orders.Snapshot create(String browser,UUID member,String guestToken,Input input,boolean http,Long authVersion) {
        if(member==null&&input!=null&&input.guestAttemptId()==null) { throw new OrderRejected(401); }
        if(browser==null||browser.isBlank()||input==null||input.requestKey()==null||!input.requestKey().matches("[A-Za-z0-9-]{1,128}")
                ||input.fingerprint()==null||!input.fingerprint().matches("[0-9a-f]{64}")||input.contact()==null
                ||input.lines()==null||input.lines().isEmpty()||input.lines().size()>20||input.lines().stream().anyMatch(l->l==null||l.skuId()==null||l.quantity()==null||l.quantity()<1||l.quantity()>99)
                ||input.lines().stream().map(Quotes.LineRequest::skuId).distinct().count()!=input.lines().size()
                ||input.coupons()==null||input.coupons().size()>20||input.coupons().stream().anyMatch(c->c==null||c.storeId()==null||c.couponId()==null)
                ||input.coupons().stream().map(Quotes.CouponSelection::storeId).distinct().count()!=input.coupons().size()
                ||input.coupons().stream().map(Quotes.CouponSelection::couponId).distinct().count()!=input.coupons().size()
                ||member==null&&(input.guestAttemptId()==null||input.linkRevision()==null||input.linkRevision()<0||!input.coupons().isEmpty())
                ||member!=null&&input.guestAttemptId()!=null) { throw new OrderRejected(400); }
        Orders.Contact contact=normalize(input.contact());
        String hash=OrderRequestHash.calculate(input.lines(),input.coupons(),input.fingerprint(),contact,input.guestAttemptId(),member==null?input.linkRevision():null);
        UUID session=UUID.nameUUIDFromBytes(("order-session:"+browser).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return tx.execute(status->{
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key","order:"+session+":"+input.requestKey()),(row,index)->row.getObject(1));
            var previous=jdbc.queryForList("SELECT c.request_hash,c.guest_attempt_id,o.order_number FROM marketplace.order_checkout_attempt c JOIN marketplace.purchase_order o ON o.checkout_id=c.id WHERE c.purchase_session_id=:session AND c.request_key=:key",Map.of("session",session,"key",input.requestKey()));
            if(!previous.isEmpty()) {
                var row=previous.getFirst();var saved=orders.find((String)row.get("order_number"));
                if(!java.util.Objects.equals(member,saved.memberId())) { throw new OrderRejected(401); }
                if(member!=null) { members.lockActiveBuyer(member); }
                else { guests.authorizeRetry(browser,(UUID)row.get("guest_attempt_id"),saved.guestBuyerId(),guestToken); }
                if(http&&!sessions.lockCurrent(browser,member,authVersion)) { throw new OrderRejected(401); }
                if(!hash.equals(row.get("request_hash"))) { throw new OrderRejected(409); }
                return saved;
            }
            GuestOrders.Prepared guest=member==null?guests.lock(browser,input.guestAttemptId(),input.linkRevision(),guestToken):null;
            offers.lock(member,input.lines().stream().map(Quotes.LineRequest::skuId).toList());
            if(http&&!sessions.lockCurrent(browser,member,authVersion)) { throw new OrderRejected(401); }
            Quotes.Quote quote=quotes.calculate(member,input.lines(),input.coupons());
            if(!quote.fingerprint().equals(input.fingerprint())) { throw new OrderRejected(409,quote); }
            Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS),expires=now.plusSeconds(900);
            for(var store:quote.stores()) { if(store.coupon()!=null&&store.coupon().terms().endsAt().isBefore(expires)) { expires=store.coupon().terms().endsAt().truncatedTo(ChronoUnit.MICROS); } }
            UUID guestBuyer=guest==null?null:guests.consume(browser,guest,guestToken);after("GUEST");
            Orders.Contact persisted=guest==null?contact:new Orders.Contact(guest.name(),guest.phone(),contact.recipientName(),contact.recipientPhone(),contact.postalCode(),contact.addressLine1(),contact.addressLine2());
            var saved=orders.save(new Orders.Draft(session,input.requestKey(),hash,member,guestBuyer,input.guestAttemptId(),mode,quote,persisted,now,expires));after("ORDER");
            var couponRequests=new java.util.ArrayList<CouponReservations.Request>();
            for(var seller:saved.sellers()) {
                var store=quote.stores().stream().filter(s->s.storeId().equals(seller.storeId())).findFirst().orElseThrow();
                if(store.coupon()!=null) { couponRequests.add(new CouponReservations.Request(UUID.randomUUID(),saved.checkoutId(),seller.id(),member,seller.storeId(),store.coupon().id(),quote,expires)); }
            }
            if(!couponRequests.isEmpty()) { coupons.reserve(couponRequests).forEach(r->reference(saved.id(),r.id(),"COUPON")); }after("COUPON");
            Instant deadline=expires;
            var inventoryRequests=saved.sellers().stream().flatMap(s->s.items().stream()).map(item->new Reservations.Request(UUID.randomUUID(),saved.checkoutId(),item.id(),item.skuId(),item.quantity(),deadline)).toList();
            inventory.reserve(inventoryRequests).forEach(r->reference(saved.id(),r.id(),"INVENTORY"));after("INVENTORY");
            if(guestBuyer!=null) { guests.reference(guestBuyer,saved.orderNumber()); }after("REFERENCE");
            jdbc.update("INSERT INTO marketplace.order_event(id,order_id,action,occurred_at) VALUES(:id,:order,'CREATED',:at)",Map.of("id",UUID.randomUUID(),"order",saved.id(),"at",java.sql.Timestamp.from(now)));after("AUDIT");
            outbox.append(new Outbox.Event(saved.id(),new Jobs.Command("ORDER_EXPIRY","order-expiry:"+saved.id(),saved.id().toString(),saved.orderNumber(),expires)));after("OUTBOX");
            return saved;
        });
    }
    private void reference(UUID order,UUID id,String kind) { jdbc.update("INSERT INTO marketplace.order_reservation(order_id,reservation_id,kind) VALUES(:order,:id,:kind)",Map.of("order",order,"id",id,"kind",kind)); }
    private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException e) { throw e; }catch(Exception e) { throw new IllegalStateException("ORDER_CREATION_CHECKPOINT",e); } }
    private Orders.Contact normalize(Orders.Contact c) {
        return new Orders.Contact(text(c.buyerName(),80,false),phone(c.buyerPhone()),text(c.recipientName(),80,false),phone(c.recipientPhone()),text(c.postalCode(),20,false),text(c.addressLine1(),300,false),text(c.addressLine2(),300,true));
    }
    private String phone(String value) { try { return com.example.marketplace.phone.api.PhoneVerifications.normalize(value); }catch(IllegalArgumentException rejected) { throw new OrderRejected(400); } }
    private String text(String value,int max,boolean empty) {
        if(value==null) { throw new OrderRejected(400); }String result=java.text.Normalizer.normalize(value.strip(),java.text.Normalizer.Form.NFC);
        if(result.length()>max||!empty&&result.isEmpty()||result.codePoints().anyMatch(Character::isISOControl)) { throw new OrderRejected(400); }return result;
    }
}
