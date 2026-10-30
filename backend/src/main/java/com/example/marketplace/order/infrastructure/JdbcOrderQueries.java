package com.example.marketplace.order.infrastructure;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.guest.api.GuestAccessSessions;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.seller.api.Stores;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcOrderQueries implements OrderQueries,com.example.marketplace.order.api.FulfillmentOrders {
    private final NamedParameterJdbcTemplate jdbc;
    private final Orders orders;
    private final GuestAccessSessions guests;
    private final HistorySessions sessions;
    private final Stores stores;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired private AftercareLifecycle aftercare;
    public JdbcOrderQueries(NamedParameterJdbcTemplate jdbc,Orders orders,GuestAccessSessions guests,HistorySessions sessions,Stores stores,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.orders=orders;this.guests=guests;this.sessions=sessions;this.stores=stores;this.tx=new TransactionTemplate(manager);
    }
    private UUID member(Authority a) {
        if(a==null||!sessions.lockReadable(a.browser(),a.member(),a.version())) { throw new OrderRejected(404); }
        return a.member();
    }
    @Override public void recordInitialDelivery(com.example.marketplace.order.api.FulfillmentOrders.InitialDelivery fact) { boolean added=InitialDeliveryWriter.record(jdbc,fact);if(added) { aftercare.delivered(fact); } }
    @Override public void recordDispatch(com.example.marketplace.order.api.FulfillmentOrders.Dispatch fact) { aftercare.dispatch(fact); }
    @Override public void lockUnits(UUID seller) { aftercare.lockSellerUnits(seller); }
    private UUID guest(String token,boolean hidden) {
        try { return guests.requireCurrent(token); }
        catch(GuestRejected rejected) { if(hidden&&rejected.status()!=503) { throw new OrderRejected(404); }throw rejected; }
    }
    @Override public Owned requireOwned(String number,Authority a) { return owned(number,a,false); }
    @Override public Owned lockOwned(String number,Authority a) { return owned(number,a,true); }
    private Owned owned(String number,Authority a,boolean write) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        if(a==null) { throw new OrderRejected(404); }
        UUID owner=a.member()!=null?member(a):guest(a.guestToken(),true);
        String ownerColumn=a.member()!=null?"member_id":"guest_buyer_id";
        var ids=jdbc.queryForList("SELECT id FROM marketplace.purchase_order WHERE order_number=:n AND "+ownerColumn+"=:owner "+(write?"FOR UPDATE":"FOR SHARE"),Map.of("n",number,"owner",owner),UUID.class);
        if(ids.size()!=1) { throw new OrderRejected(404); }
        // Row-lock waits can cross natural session deadlines even while account/session rows stay locked.
        if(a.member()!=null) { member(a); }else if(!owner.equals(guest(a.guestToken(),true))) { throw new OrderRejected(404); }
        return jdbc.queryForObject("SELECT id,state,amount,mode FROM marketplace.purchase_order WHERE id=:id",Map.of("id",ids.getFirst()),(r,n)->new Owned(r.getObject("id",UUID.class),r.getString("state"),r.getString("amount"),r.getString("mode")));
    }
    @Override public Page memberOrders(Authority a,int size,String cursor) { return tx.execute(s->page("member_id",member(a),size,cursor)); }
    @Override public Page guestOrders(String token,int size,String cursor) { return tx.execute(s->page("guest_buyer_id",guest(token,false),size,cursor)); }
    private record Cursor(Instant created,UUID id) { }
    private Cursor decode(String value) {
        if(value==null) { return null; }
        try {
            if(value.length()>128||!value.matches("[A-Za-z0-9_-]+")) { throw new IllegalArgumentException(); }
            String raw=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);String[] fields=raw.split("\\|",-1);
            if(fields.length!=2) { throw new IllegalArgumentException(); }
            var cursor=new Cursor(Instant.parse(fields[0]),UUID.fromString(fields[1]));
            if(!encode(cursor).equals(value)) { throw new IllegalArgumentException(); }return cursor;
        }catch(RuntimeException invalid) { throw new OrderRejected(400); }
    }
    private String encode(Cursor c) { return Base64.getUrlEncoder().withoutPadding().encodeToString((c.created()+"|"+c.id()).getBytes(StandardCharsets.UTF_8)); }
    private Page page(String column,UUID owner,int size,String cursor) {
        if(size<1||size>50) { throw new OrderRejected(400); }var after=decode(cursor);
        var args=new MapSqlParameterSource("owner",owner).addValue("limit",size+1);
        String boundary="";if(after!=null) { boundary=" AND (created_at,id)<(:created,:id)";args.addValue("created",Timestamp.from(after.created())).addValue("id",after.id()); }
        var rows=jdbc.query("SELECT id,order_number,state,amount,mode,created_at FROM marketplace.purchase_order WHERE "+column+"=:owner"+boundary+" ORDER BY created_at DESC,id DESC LIMIT :limit",args,(r,n)->Map.entry(new Cursor(r.getTimestamp("created_at").toInstant(),r.getObject("id",UUID.class)),new Summary(r.getString("order_number"),r.getString("state"),r.getString("amount"),r.getString("mode"),r.getTimestamp("created_at").toInstant())));
        return new Page(rows.stream().limit(size).map(Map.Entry::getValue).toList(),rows.size()>size?encode(rows.get(size-1).getKey()):null);
    }
    @Override public Detail detail(String number,Authority a) { return tx.execute(s->{requireOwned(number,a);var saved=orders.find(number);return new Detail(number,saved.state(),money(saved.quote().total()),saved.mode(),saved.createdAt(),saved.quote().policyVersion(),saved.contact(),saved.quote().stores().stream().map(store->project(saved,store)).toList());}); }
    private Store project(Orders.Snapshot order,com.example.marketplace.promotion.api.Quotes.Store q) {
        var seller=order.sellers().stream().filter(s->s.storeId().equals(q.storeId())).findFirst().orElseThrow();
        var items=q.lines().stream().map(line->{UUID id=seller.items().stream().filter(i->i.skuId().equals(line.skuId())).findFirst().orElseThrow().id();return new Item(id,line.skuId(),line.title(),line.options(),line.quantity(),money(line.unitPrice()),money(line.gross()),line.promotion()==null?"":line.promotion().title(),money(line.promotionDiscount()),money(line.couponDiscount()),money(line.net()),line.units().stream().map(u->new OrderQueries.Unit(u.ordinal(),money(u.gross()),money(u.promotionDiscount()),money(u.couponDiscount()),money(u.net()))).toList());}).toList();
        return new Store(seller.id(),q.storeId(),q.name(),money(q.subtotal()),money(q.productDiscount()),q.coupon()==null?"":q.coupon().title(),money(q.couponDiscount()),money(q.shippingBase()),q.shippingPromotion()==null?"":q.shippingPromotion().title(),money(q.shippingDiscount()),money(q.total()),items);
    }
    @Override public SellerOrder sellerOrder(UUID id,Authority a) { return tx.execute(s->seller(id,a,false,false)); }
    @Override public SellerPage sellerOrders(Authority a,int size,String cursor) {
        return tx.execute(t->{
            if(a==null||a.member()==null) { throw new OrderRejected(404); }
            stores.requireCapability(a.member(),Stores.Capability.EXISTING_TRADES);UUID owner=member(a);var store=stores.own(owner);if(store==null) { throw new OrderRejected(404); }
            if(size<1||size>50) { throw new OrderRejected(400); }var after=decode(cursor);var args=new MapSqlParameterSource("store",store.id()).addValue("limit",size+1);String boundary="";
            if(after!=null) { boundary=" AND (o.created_at,s.id)<(:created,:id)";args.addValue("created",Timestamp.from(after.created())).addValue("id",after.id()); }
            var rows=jdbc.query("SELECT s.id,o.order_number,o.state,s.total,o.created_at FROM marketplace.seller_order s JOIN marketplace.purchase_order o ON o.id=s.order_id WHERE s.store_id=:store"+boundary+" ORDER BY o.created_at DESC,s.id DESC LIMIT :limit",args,(r,n)->Map.entry(new Cursor(r.getTimestamp("created_at").toInstant(),r.getObject("id",UUID.class)),new SellerSummary(r.getObject("id",UUID.class),r.getString("order_number"),r.getString("state"),r.getString("total"),r.getTimestamp("created_at").toInstant())));
            member(a);stores.requireCapability(owner,Stores.Capability.EXISTING_TRADES);
            return new SellerPage(rows.stream().limit(size).map(Map.Entry::getValue).toList(),rows.size()>size?encode(rows.get(size-1).getKey()):null);
        });
    }
    @Override public SellerOrder paidSellerOrder(UUID id,Authority a) { return tx.execute(s->seller(id,a,true,false)); }
    @Override public SellerOrder lockSellerOrder(UUID id,Authority a) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }return seller(id,a,false,true);
    }
    @Override public SellerOrder writePaidSellerOrder(UUID id,Authority a) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        return seller(id,a,true,true);
    }
    @Override public com.example.marketplace.order.api.FulfillmentOrders.Paid lockPaid(String number) {
        var result=lockExisting(number);return orders.find(number).state().equals("PAID")?result:null;
    }
    @Override public com.example.marketplace.order.api.FulfillmentOrders.Paid lockExisting(String number) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        var saved=orders.lock(number);
        return new com.example.marketplace.order.api.FulfillmentOrders.Paid(saved.id(),saved.quote().stores().stream().map(q->{var p=project(saved,q);var c=saved.contact();return new SellerOrder(p.sellerOrderId(),number,saved.state(),p.total(),new Recipient(c.recipientName(),c.recipientPhone(),c.postalCode(),c.addressLine1(),c.addressLine2()),p.items());}).toList());
    }
    @Override public java.util.List<com.example.marketplace.order.api.FulfillmentOrders.Candidate> paidCandidates(UUID after,int size) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        if(size<1||size>50) { throw new OrderRejected(400); }
        return jdbc.query("SELECT id,order_number FROM marketplace.purchase_order WHERE state='PAID'"+(after==null?"":" AND id>:after")+" ORDER BY id LIMIT :size",new MapSqlParameterSource("after",after).addValue("size",size),(r,n)->new com.example.marketplace.order.api.FulfillmentOrders.Candidate(r.getObject("id",UUID.class),r.getString("order_number")));
    }
    private SellerOrder seller(UUID id,Authority a,boolean paid,boolean write) {
        if(a==null||a.member()==null) { throw new OrderRejected(404); }stores.requireCapability(a.member(),Stores.Capability.EXISTING_TRADES);UUID owner=member(a);var store=stores.own(owner);if(store==null) { throw new OrderRejected(404); }
        var numbers=jdbc.queryForList("SELECT o.order_number FROM marketplace.seller_order s JOIN marketplace.purchase_order o ON o.id=s.order_id WHERE s.id=:id AND s.store_id=:store "+(write?"FOR UPDATE OF o":"FOR SHARE OF o"),Map.of("id",id,"store",store.id()),String.class);
        if(numbers.size()!=1) { throw new OrderRejected(404); }member(a);if(write) { stores.requireCapability(a.member(),Stores.Capability.EXISTING_TRADES);stores.own(a.member()); }var saved=orders.find(numbers.getFirst());if(paid&&!saved.state().equals("PAID")) { throw new OrderRejected(409); }
        var projection=project(saved,saved.quote().stores().stream().filter(q->q.storeId().equals(store.id())).findFirst().orElseThrow());var c=saved.contact();
        return new SellerOrder(id,saved.orderNumber(),saved.state(),projection.total(),new Recipient(c.recipientName(),c.recipientPhone(),c.postalCode(),c.addressLine1(),c.addressLine2()),projection.items());
    }
    private static String money(long value) { return Long.toString(value); }
}
