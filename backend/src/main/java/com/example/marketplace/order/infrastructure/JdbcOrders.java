package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.domain.OrderRules;
import com.example.marketplace.promotion.api.Quotes;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service @Profile("db")
public final class JdbcOrders implements Orders {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Supplier<UUID> numbers;
    private java.util.function.Supplier<AftercarePolicySource.Policy> aftercarePolicy=()->new AftercarePolicySource().current();
    public void policySource(java.util.function.Supplier<AftercarePolicySource.Policy> source) { this.aftercarePolicy=java.util.Objects.requireNonNull(source); }
    private com.example.marketplace.identity.api.SellerMembership members;
    private com.example.marketplace.identity.api.PurchaseSessions sessions;
    private com.example.marketplace.guest.api.GuestOrders guests;
    @Autowired void authorities(com.example.marketplace.identity.api.SellerMembership members,com.example.marketplace.identity.api.PurchaseSessions sessions,com.example.marketplace.guest.api.GuestOrders guests) {
        this.members=members;this.sessions=sessions;this.guests=guests;
    }
    @Autowired public JdbcOrders(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager) {
        this(jdbc,mapper,manager,UUID::randomUUID);
    }
    public JdbcOrders(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager,Supplier<UUID> numbers) {
        this.jdbc=jdbc;this.mapper=mapper;this.tx=new TransactionTemplate(manager);this.numbers=numbers;
    }
    @Override public Snapshot save(Draft d) {
        validate(d);
        return tx.execute(status->{
            var p=new MapSqlParameterSource().addValue("session",d.purchaseSessionId()).addValue("key",d.requestKey())
                    .addValue("hash",d.requestHash()).addValue("attempt",d.guestAttemptId()).addValue("created",Timestamp.from(d.createdAt()))
                    .addValue("expires",Timestamp.from(d.expiresAt())).addValue("checkout",UUID.randomUUID());
            jdbc.update("INSERT INTO marketplace.order_checkout_attempt(id,purchase_session_id,request_key,request_hash,guest_attempt_id,created_at,expires_at) VALUES(:checkout,:session,:key,:hash,:attempt,:created,:expires) ON CONFLICT DO NOTHING",p);
            var checkouts=jdbc.queryForList("SELECT * FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:session AND request_key=:key FOR UPDATE",p);
            if(checkouts.isEmpty()) { throw new OrderRejected(409); }
            var checkout=checkouts.getFirst();
            if(!d.requestHash().equals(checkout.get("request_hash")) || !Objects.equals(d.guestAttemptId(),checkout.get("guest_attempt_id"))) { throw new OrderRejected(409); }
            p.addValue("checkout",checkout.get("id"));
            var prior=jdbc.queryForList("SELECT order_number FROM marketplace.purchase_order WHERE checkout_id=:checkout",p,String.class);
            if(!prior.isEmpty()) {
                var saved=find(prior.getFirst());
                if(!Objects.equals(d.memberId(),saved.memberId()) || !Objects.equals(d.guestBuyerId(),saved.guestBuyerId())) { throw new OrderRejected(409); }
                return saved;
            }
            UUID id=UUID.randomUUID();p.addValue("id",id).addValue("member",d.memberId()).addValue("guest",d.guestBuyerId())
                    .addValue("mode",d.mode()).addValue("amount",d.quote().total()).addValue("policy",d.quote().policyVersion())
                    .addValue("quote",mapper.writeValueAsString(d.quote()));
            String number=null;
            for(int tries=0;tries<5;tries++) {
                String candidate="O"+numbers.get().toString().replace("-","");p.addValue("number",candidate);
                int changed=jdbc.update("INSERT INTO marketplace.purchase_order(id,checkout_id,order_number,member_id,guest_buyer_id,mode,state,amount,policy_version,quote_snapshot,created_at,expires_at) VALUES(:id,:checkout,:number,:member,:guest,:mode,'PENDING_PAYMENT',:amount,:policy,CAST(:quote AS jsonb),:created,:expires) ON CONFLICT(order_number) DO NOTHING",p);
                if(changed==1) { number=candidate;break; }
            }
            if(number==null) { throw new IllegalStateException("ORDER_NUMBER_EXHAUSTED"); }
            for(var store:d.quote().stores()) { saveStore(id,store); }
            AftercareLifecycle.initialize(jdbc,id,aftercarePolicy.get(),Instant.now());
            var c=d.contact();
            p.addValue("buyerName",c.buyerName()).addValue("buyerPhone",c.buyerPhone()).addValue("recipientName",c.recipientName())
                    .addValue("recipientPhone",c.recipientPhone()).addValue("postal",c.postalCode()).addValue("address1",c.addressLine1()).addValue("address2",c.addressLine2());
            jdbc.update("INSERT INTO marketplace.order_contact(order_id,buyer_name,buyer_phone,recipient_name,recipient_phone,postal_code,address_line1,address_line2) VALUES(:id,:buyerName,:buyerPhone,:recipientName,:recipientPhone,:postal,:address1,:address2)",p);
            return find(number);
        });
    }
    private void saveStore(UUID order,Quotes.Store store) {
        UUID seller=UUID.randomUUID();var p=new MapSqlParameterSource().addValue("id",seller).addValue("order",order).addValue("store",store.storeId())
                .addValue("gross",store.subtotal()).addValue("promotion",store.productDiscount()).addValue("coupon",store.couponDiscount())
                .addValue("shipping",store.shippingBase()).addValue("shippingDiscount",store.shippingDiscount()).addValue("total",store.total());
        jdbc.update("INSERT INTO marketplace.seller_order(id,order_id,store_id,subtotal,product_discount,coupon_discount,shipping_base,shipping_discount,total) VALUES(:id,:order,:store,:gross,:promotion,:coupon,:shipping,:shippingDiscount,:total)",p);
        for(var line:store.lines()) {
            if(line.returnPolicy()==null||!line.productId().equals(line.returnPolicy().productId())) { throw new OrderRejected(409); }line.returnPolicy().checkedAmount();
            UUID item=UUID.randomUUID();p.addValue("item",item).addValue("sku",line.skuId()).addValue("quantity",line.quantity())
                    .addValue("price",line.unitPrice()).addValue("gross",line.gross()).addValue("promotion",line.promotionDiscount())
                    .addValue("coupon",line.couponDiscount()).addValue("net",line.net()).addValue("snapshot",mapper.writeValueAsString(line));
            jdbc.update("INSERT INTO marketplace.order_item(id,order_id,seller_order_id,sku_id,quantity,unit_price,gross,promotion_discount,coupon_discount,net,line_snapshot) VALUES(:item,:order,:id,:sku,:quantity,:price,:gross,:promotion,:coupon,:net,CAST(:snapshot AS jsonb))",p);
            jdbc.update("INSERT INTO marketplace.order_item_return_policy(item_id,status,source,policy_snapshot) VALUES(:item,'ACTIVE',:source,CAST(:policySnapshot AS jsonb))",new MapSqlParameterSource("item",item).addValue("source",line.returnPolicy().source()).addValue("policySnapshot",mapper.writeValueAsString(line.returnPolicy())));
            for(var unit:line.units()) {
                p.addValue("ordinal",unit.ordinal()).addValue("gross",unit.gross()).addValue("promotion",unit.promotionDiscount()).addValue("coupon",unit.couponDiscount()).addValue("net",unit.net());
                jdbc.update("INSERT INTO marketplace.order_unit(item_id,ordinal,gross,promotion_discount,coupon_discount,net) VALUES(:item,:ordinal,:gross,:promotion,:coupon,:net)",p);
            }
        }
    }
    @Override public Snapshot find(String number) {
        var p=Map.of("number",number);
        var rows=jdbc.query("SELECT o.*,c.buyer_name,c.buyer_phone,c.recipient_name,c.recipient_phone,c.postal_code,c.address_line1,c.address_line2 FROM marketplace.purchase_order o JOIN marketplace.order_contact c ON c.order_id=o.id WHERE o.order_number=:number",p,(rs,n)-> {
            UUID id=rs.getObject("id",UUID.class);
            var sellers=jdbc.query("SELECT id,store_id FROM marketplace.seller_order WHERE order_id=:id ORDER BY store_id",Map.of("id",id),(seller,index)-> {
                UUID sellerId=seller.getObject("id",UUID.class);
                var items=jdbc.query("SELECT id,sku_id,quantity FROM marketplace.order_item WHERE seller_order_id=:id ORDER BY sku_id",Map.of("id",sellerId),(item,i)->new Item(item.getObject("id",UUID.class),item.getObject("sku_id",UUID.class),item.getInt("quantity")));
                return new SellerOrder(sellerId,seller.getObject("store_id",UUID.class),items);
            });
            var contact=new Contact(rs.getString("buyer_name"),rs.getString("buyer_phone"),rs.getString("recipient_name"),rs.getString("recipient_phone"),rs.getString("postal_code"),rs.getString("address_line1"),rs.getString("address_line2"));
            return new Snapshot(id,rs.getObject("checkout_id",UUID.class),number,rs.getObject("member_id",UUID.class),rs.getObject("guest_buyer_id",UUID.class),rs.getString("mode"),rs.getString("state"),mapper.readValue(rs.getString("quote_snapshot"),Quotes.Quote.class),contact,rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),sellers);
        });
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void validate(Draft d) {
        if(d==null || d.purchaseSessionId()==null || d.requestKey()==null || d.requestKey().isBlank() || d.requestKey().length()>128
                || d.requestHash()==null || !d.requestHash().matches("[0-9a-f]{64}") || (d.memberId()==null)==(d.guestBuyerId()==null)
                || (d.guestBuyerId()!=null)!=(d.guestAttemptId()!=null) || d.mode()==null || !List.of("DEMO","SANDBOX").contains(d.mode())
                || d.quote()==null || !Objects.equals(d.memberId(),d.quote().memberId()) || d.contact()==null) { throw new IllegalArgumentException("INVALID_ORDER"); }
        Instant created=d.createdAt(),expires=d.expiresAt();
        if(created==null || expires==null || !created.equals(created.truncatedTo(ChronoUnit.MICROS)) || !expires.equals(expires.truncatedTo(ChronoUnit.MICROS))
                || !expires.isAfter(created) || expires.isAfter(created.plusSeconds(900))) { throw new IllegalArgumentException("INVALID_ORDER_DEADLINE"); }
        OrderRules.validateSnapshot(d.quote());
        for(var store:d.quote().stores()) {
            if(store.coupon()!=null && (d.memberId()==null || expires.isAfter(store.coupon().terms().endsAt()))) { throw new IllegalArgumentException("INVALID_ORDER_COUPON_DEADLINE"); }
        }
        var c=d.contact();text(c.buyerName(),80,false);text(c.buyerPhone(),32,false);text(c.recipientName(),80,false);
        text(c.recipientPhone(),32,false);text(c.postalCode(),20,false);text(c.addressLine1(),300,false);text(c.addressLine2(),300,true);
    }
    @Override public ReservationIds reservations(String number) {
        var saved=find(number);if(saved==null) { throw new OrderRejected(404); }
        var rows=jdbc.queryForList("SELECT reservation_id,kind FROM marketplace.order_reservation WHERE order_id=:id ORDER BY reservation_id",Map.of("id",saved.id()));
        return new ReservationIds(rows.stream().filter(r->"INVENTORY".equals(r.get("kind"))).map(r->(UUID)r.get("reservation_id")).toList(),rows.stream().filter(r->"COUPON".equals(r.get("kind"))).map(r->(UUID)r.get("reservation_id")).toList());
    }
    @Override public Snapshot lock(String number) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        if(jdbc.queryForList("SELECT id FROM marketplace.purchase_order WHERE order_number=:n FOR UPDATE",Map.of("n",number)).isEmpty()) { throw new OrderRejected(404); }
        return find(number);
    }
    @Override public Snapshot authorize(String number,String browser,UUID member,Long version,String token) {
        var saved=find(number);if(saved==null||!Objects.equals(member,saved.memberId())) { throw new OrderRejected(401); }
        UUID attempt=null;
        if(member!=null) { members.lockActiveBuyer(member); }
        else {
            attempt=jdbc.queryForObject("SELECT guest_attempt_id FROM marketplace.order_checkout_attempt WHERE id=:id",Map.of("id",saved.checkoutId()),UUID.class);
            guests.authorizeRetry(browser,attempt,saved.guestBuyerId(),token);
        }
        if(!sessions.lockCurrent(browser,member,version)) { throw new OrderRejected(401); }
        var locked=lock(number);
        // The same guest rows remain locked, but their idle/maximum deadlines can pass during the order wait.
        if(member==null) { guests.authorizeRetry(browser,attempt,saved.guestBuyerId(),token); }
        if(!sessions.lockCurrent(browser,member,version)) { throw new OrderRejected(401); }
        return locked;
    }
    @Override public void transition(String number,String target) {
        var saved=lock(number);OrderRules.transition(saved.state(),target);
        if(saved.state().equals(target)) { return; }
        jdbc.update("UPDATE marketplace.purchase_order SET state=:s,version=version+1 WHERE id=:id",Map.of("s",target,"id",saved.id()));
        jdbc.update("INSERT INTO marketplace.order_event(id,order_id,action,occurred_at) VALUES(:id,:o,:s,clock_timestamp())",Map.of("id",UUID.randomUUID(),"o",saved.id(),"s",target));
    }
    private void text(String value,int max,boolean empty) {
        if(value==null || value.length()>max || (!empty && value.isBlank())) { throw new IllegalArgumentException("INVALID_ORDER_CONTACT"); }
    }
}
