package com.example.marketplace.order.infrastructure;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.inventory.api.ExchangeReservations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
@Service @Profile("db")
public final class JdbcExchangeApplications implements ExchangeApplications {
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final Orders orders;private final OrderUnitHolds holds;private final AftercareLifecycle lifecycle;private final ExchangeReservations stocks;private final com.example.marketplace.order.api.PhysicalOrders physical;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public JdbcExchangeApplications(NamedParameterJdbcTemplate jdbc,OrderQueries queries,Orders orders,OrderUnitHolds holds,AftercareLifecycle lifecycle,ExchangeReservations stocks,com.example.marketplace.order.api.PhysicalOrders physical) { this.jdbc=jdbc;this.queries=queries;this.orders=orders;this.holds=holds;this.lifecycle=lifecycle;this.stocks=stocks;this.physical=physical; }
 @Override public Source begin(String number,OrderQueries.Authority authority,UUID id,UUID hold,UUID cause,List<OrderConfirmations.Unit> input) {
  AftercareLifecycle.transaction();var units=AftercareLifecycle.canonical(input);var own=queries.lockOwned(number,authority);
  if(id==null||hold==null||cause==null||!own.state().equals("PAID")) { throw new OrderRejected(409); }
  var snapshot=orders.find(number);var selected=new java.util.ArrayList<Unit>();UUID seller=null,store=null;
  for(var u:units) {
   var life=lifecycle.lockUnit(own.id(),u);var current=(UUID)life.get("seller_order_id");if(!life.get("state").equals("OPEN")||seller!=null&&!seller.equals(current)) { throw new OrderRejected(409); }seller=current;
   var sellerSnapshot=snapshot.sellers().stream().filter(s->s.id().equals(current)).findFirst().orElseThrow(()->new OrderRejected(409));store=sellerSnapshot.storeId();
   var item=sellerSnapshot.items().stream().filter(i->i.id().equals(u.itemId())).findFirst().orElseThrow(()->new OrderRejected(409));
   var line=snapshot.quote().stores().stream().filter(s->s.storeId().equals(sellerSnapshot.storeId())).flatMap(s->s.lines().stream()).filter(l->l.skuId().equals(item.skuId())).findFirst().orElseThrow(()->new OrderRejected(409));
   var delivery=jdbc.queryForList("SELECT d.event_id,d.shipment_id,d.occurred_at FROM marketplace.order_initial_delivery_unit du JOIN marketplace.order_initial_delivery d ON d.event_id=du.event_id WHERE du.order_id=:order AND du.seller_order_id=:seller AND du.item_id=:item AND du.ordinal=:ordinal",Map.of("order",own.id(),"seller",seller,"item",u.itemId(),"ordinal",u.ordinal()));if(delivery.size()!=1) { throw new OrderRejected(409); }var d=delivery.getFirst();
   var purchased=stocks.purchased(snapshot.checkoutId(),u.itemId(),u.ordinal(),item.skuId());
   UUID physical=UUID.nameUUIDFromBytes(("purchase-physical:"+d.get("event_id")+":"+u.itemId()+":"+u.ordinal()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   var unit=new Unit(u.itemId(),u.ordinal(),physical,0,item.skuId(),purchased.reservationId(),snapshot.checkoutId(),(UUID)d.get("event_id"),(UUID)d.get("shipment_id"),((Timestamp)d.get("occurred_at")).toInstant(),Long.toString(line.unitPrice()),Long.toString(line.units().get(u.ordinal()).net()),line.returnPolicy());
   var p=new MapSqlParameterSource("physical",physical).addValue("order",own.id()).addValue("seller",seller).addValue("item",u.itemId()).addValue("ordinal",u.ordinal()).addValue("sku",item.skuId()).addValue("reservation",purchased.reservationId()).addValue("checkout",snapshot.checkoutId()).addValue("event",d.get("event_id")).addValue("bucket",d.get("shipment_id")).addValue("at",d.get("occurred_at")).addValue("snapshot",mapper.writeValueAsString(unit));
   jdbc.update("INSERT INTO marketplace.order_physical_unit(id,order_id,seller_order_id,item_id,ordinal,round,sku_id,reservation_id,checkout_id,delivery_event_id,delivery_bucket_id,delivered_at,source_snapshot) VALUES(:physical,:order,:seller,:item,:ordinal,0,:sku,:reservation,:checkout,:event,:bucket,:at,CAST(:snapshot AS jsonb)) ON CONFLICT(id) DO NOTHING",p);
   var saved=jdbc.queryForObject("SELECT source_snapshot::text FROM marketplace.order_physical_unit WHERE id=:physical",p,String.class);if(!unit.equals(mapper.readValue(saved,Unit.class))) { throw new OrderRejected(409); }
   selected.add(unit);
  }
  holds.acquire(own.id(),hold,cause,"EXCHANGE",units);queries.lockOwned(number,authority);
  var at=observedRequestTime(id);long window=((Number)lifecycle.policy(own.id()).get("claim_window_seconds")).longValue();
  var source=new Source(id,own.id(),seller,store,hold,cause,at,lifecycle.revision(own.id()),window,selected);
  var p=new MapSqlParameterSource("id",id).addValue("order",own.id()).addValue("seller",seller).addValue("store",store).addValue("hold",hold).addValue("cause",cause).addValue("at",Timestamp.from(at)).addValue("snapshot",mapper.writeValueAsString(source));
  jdbc.update("INSERT INTO marketplace.order_exchange_application(id,order_id,seller_order_id,store_id,hold_id,cause_id,requested_at,source_snapshot,source_schema_version) VALUES(:id,:order,:seller,:store,:hold,:cause,:at,CAST(:snapshot AS jsonb),2)",p);
  for(var u:selected) { jdbc.update("INSERT INTO marketplace.order_exchange_unit(exchange_id,hold_id,physical_id,item_id,ordinal) VALUES(:id,:hold,:physical,:item,:ordinal)",p.addValue("physical",u.physicalId()).addValue("item",u.itemId()).addValue("ordinal",u.ordinal())); }
  physical.bind(source);return source;
 }
 @Override public Source lockOwner(String number,OrderQueries.Authority authority,UUID id) { AftercareLifecycle.transaction();var own=queries.lockOwned(number,authority);var s=load(id);if(!s.orderId().equals(own.id())) { throw new OrderRejected(404); }validate(s);queries.lockOwned(number,authority);return s; }
 @Override public Source lockSeller(OrderQueries.Authority authority,UUID id) { AftercareLifecycle.transaction();var s=load(id);queries.writePaidSellerOrder(s.sellerOrderId(),authority);validate(s);queries.writePaidSellerOrder(s.sellerOrderId(),authority);return s; }
 @Override public Source lockRecorded(UUID id) { AftercareLifecycle.transaction();var s=load(id);lifecycle.lockOrder(s.orderId());validate(s);return s; }
 @Override public com.example.marketplace.order.api.ReturnSources.Selection lockRefundSource(String number,OrderQueries.Authority authority,UUID id) {
  var accepted=lockOwner(number,authority,id);var own=queries.lockOwned(number,authority);var hold=holds.inspect(accepted.orderId(),accepted.holdId());
  if(!own.state().equals("PAID")||!hold.state().equals("ACTIVE")||!hold.kind().equals("EXCHANGE")) { throw new OrderRejected(409); }
  var fixed=physical.application(id);var units=new java.util.ArrayList<com.example.marketplace.order.api.ReturnSources.Unit>();
  for(var original:accepted.units()) {
   var actual=fixed.stream().filter(u->u.itemId().equals(original.itemId())&&u.ordinal()==original.ordinal()&&u.originalPhysicalId().equals(original.physicalId())).findFirst().orElseThrow(()->new OrderRejected(409));
   long net=jdbc.queryForObject("SELECT net FROM marketplace.order_unit WHERE item_id=:item AND ordinal=:ordinal",Map.of("item",original.itemId(),"ordinal",original.ordinal()),Long.class);
   var saved=jdbc.queryForList("SELECT status,policy_snapshot::text FROM marketplace.order_item_return_policy WHERE item_id=:item",Map.of("item",original.itemId()));
   if(saved.size()!=1||!Long.toString(net).equals(original.originalNet())) { throw new OrderRejected(409); }var row=saved.getFirst();
   var policy=row.get("policy_snapshot")==null?null:mapper.readValue(row.get("policy_snapshot").toString(),com.example.marketplace.catalog.api.ReturnPolicy.class);
   if(!java.util.Objects.equals(policy,original.returnPolicy())||!row.get("status").equals(policy==null?"UNRESOLVED":"ACTIVE")) { throw new OrderRejected(409); }if(policy!=null) { policy.checkedAmount(); }
   units.add(new com.example.marketplace.order.api.ReturnSources.Unit(original.itemId(),original.ordinal(),original.skuId(),original.deliveryBucketId(),original.deliveryEventId(),original.deliveredAt(),net,row.get("status").toString(),policy,actual));
  }
  queries.lockOwned(number,authority);return new com.example.marketplace.order.api.ReturnSources.Selection(accepted.orderId(),accepted.sellerOrderId(),accepted.units().getFirst().checkoutId(),own.mode(),lifecycle.revision(accepted.orderId()),units);
 }
 private java.time.Instant observedRequestTime(UUID id) {
  var before=java.time.Instant.now();return jdbc.queryForObject("WITH observed AS MATERIALIZED(SELECT pg_catalog.clock_timestamp() AS observed_at) SELECT observed_at,observed_at::text AS raw_at,extract(epoch FROM observed_at)::text AS epoch,pg_backend_pid() AS backend,current_database() AS database,current_setting('marketplace.verification_instance',true) AS instance,inet_server_addr()::text AS server_address,inet_server_port() AS server_port,pg_postmaster_start_time()::text AS server_started FROM observed",Map.of(),(r,n)->{var actual=r.getTimestamp("observed_at").toInstant();System.getLogger(JdbcExchangeApplications.class.getName()).log(System.Logger.Level.INFO,"EXCHANGE_ACTUAL_DATABASE_CLOCK boundary=REQUEST exchange="+id+" raw="+r.getString("raw_at")+" epoch="+r.getString("epoch")+" jdbc="+actual+" backend="+r.getInt("backend")+" database="+r.getString("database")+" instance="+r.getString("instance")+" server="+r.getString("server_address")+":"+r.getInt("server_port")+" serverStarted="+r.getString("server_started")+" jvmBefore="+before+" jvmAfter="+java.time.Instant.now());return actual;});
 }
 private Source load(UUID id) { var rows=jdbc.queryForList("SELECT source_snapshot::text FROM marketplace.order_exchange_application WHERE id=:id",Map.of("id",id),String.class);if(rows.size()!=1) { throw new OrderRejected(404); }return mapper.readValue(rows.getFirst(),Source.class); }
 private void validate(Source s) {
  var units=AftercareLifecycle.canonical(s.units().stream().map(u->new OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList());for(var u:units) { lifecycle.lockUnit(s.orderId(),u); }
  var h=holds.inspect(s.orderId(),s.holdId());boolean completed=jdbc.queryForObject("SELECT count(*) FROM marketplace.order_exchange_completion WHERE exchange_id=:id AND order_id=:order AND hold_id=:hold",Map.of("id",s.id(),"order",s.orderId(),"hold",s.holdId()),Integer.class)==1;
  boolean refundSchema=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('marketplace.order_exchange_refund_completion') IS NOT NULL",Map.of(),Boolean.class));boolean refundCompleted=false;
  if(refundSchema) { var p=Map.of("id",s.id(),"order",s.orderId(),"seller",s.sellerOrderId(),"hold",s.holdId(),"cause",s.causeId());int receipts=jdbc.queryForObject("SELECT count(*) FROM marketplace.order_exchange_refund_completion WHERE exchange_id=:id",p,Integer.class);int valid=jdbc.queryForObject("SELECT count(*) FROM marketplace.order_exchange_refund_completion x JOIN marketplace.order_unit_hold h ON h.id=x.hold_id AND h.order_id=x.order_id AND h.cause_id=x.cause_id WHERE x.exchange_id=:id AND x.order_id=:order AND x.seller_order_id=:seller AND x.hold_id=:hold AND x.cause_id=:cause AND x.intent_id=:id AND h.state='REFUNDED' AND h.kind='EXCHANGE' AND h.outcome=x.reference",p,Integer.class);if(receipts!=valid||receipts>1||completed&&valid>0) { throw new OrderRejected(409); }refundCompleted=valid==1; }
  if(!h.kind().equals("EXCHANGE")||!h.state().equals(completed?"RELEASED":refundCompleted?"REFUNDED":"ACTIVE")||!h.causeId().equals(s.causeId())||!h.units().equals(units)) { throw new OrderRejected(409); }
  for(var u:s.units()) { var row=lifecycle.lockUnit(s.orderId(),new OrderConfirmations.Unit(u.itemId(),u.ordinal()));if(!row.get("seller_order_id").equals(s.sellerOrderId())||!completed&&!refundCompleted&&(!row.get("state").equals("HELD")||!s.holdId().equals(row.get("active_hold_id")))) { throw new OrderRejected(409); }var stored=jdbc.queryForList("SELECT source_snapshot::text FROM marketplace.order_physical_unit WHERE id=:id AND order_id=:order AND seller_order_id=:seller",Map.of("id",u.physicalId(),"order",s.orderId(),"seller",s.sellerOrderId()),String.class);if(stored.size()!=1||!u.equals(mapper.readValue(stored.getFirst(),Unit.class))) { throw new OrderRejected(409); } }
 }
}
