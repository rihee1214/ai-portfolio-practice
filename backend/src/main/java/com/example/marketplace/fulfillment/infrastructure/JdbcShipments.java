package com.example.marketplace.fulfillment.infrastructure;
import com.example.marketplace.fulfillment.api.Shipments;
import com.example.marketplace.fulfillment.domain.ShipmentRules;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.FulfillmentOrders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class JdbcShipments implements Shipments {
 public interface Checkpoint { void after(String stage) throws Exception; }
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries orders;private final FulfillmentOrders fulfillment;private final Outbox outbox;private final TransactionTemplate tx;private final Clock clock;private final Checkpoint checkpoint;
 @Autowired public JdbcShipments(NamedParameterJdbcTemplate jdbc,OrderQueries orders,FulfillmentOrders fulfillment,Outbox outbox,PlatformTransactionManager manager) { this(jdbc,orders,fulfillment,outbox,manager,Clock.systemUTC(),stage->{ }); }
 public JdbcShipments(NamedParameterJdbcTemplate jdbc,OrderQueries orders,FulfillmentOrders fulfillment,Outbox outbox,PlatformTransactionManager manager,Clock clock,Checkpoint checkpoint) { this.jdbc=jdbc;this.orders=orders;this.fulfillment=fulfillment;this.outbox=outbox;this.tx=new TransactionTemplate(manager);this.clock=clock;this.checkpoint=checkpoint; }
 private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException e) { throw e; }catch(Exception e) { throw new IllegalStateException("SHIPMENT_TRANSACTION_FAILED",e); } }
 private record Header(UUID id,UUID seller,String number,String state,long version) { }
 private Header header(UUID id,boolean lock) {
  var rows=jdbc.query("SELECT * FROM marketplace.shipment WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id),(r,n)->new Header(r.getObject("id",UUID.class),r.getObject("seller_order_id",UUID.class),r.getString("order_number"),r.getString("state"),r.getLong("version")));
  if(rows.isEmpty()) { throw new OrderRejected(404); }return rows.getFirst();
 }
 private List<Item> items(UUID id) { return jdbc.query("SELECT order_item_id,quantity FROM marketplace.shipment_item WHERE shipment_id=:id ORDER BY order_item_id",Map.of("id",id),(r,n)->new Item(r.getObject(1,UUID.class),r.getInt(2))); }
 private View view(UUID id) {
  var h=header(id,false);var events=jdbc.query("SELECT * FROM marketplace.shipment_event WHERE shipment_id=:id ORDER BY event_sequence",Map.of("id",id),(r,n)->new Event(r.getObject("id",UUID.class),r.getString("event_key"),r.getString("source"),r.getString("actor"),r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("processed_at").toInstant(),r.getString("previous_state"),r.getString("next_state"),r.getLong("version"),r.getBoolean("applied")));
  return new View(id,h.seller(),h.state(),h.version(),"MOCK",items(id),events);
 }
 @Override public List<View> seller(UUID seller,OrderQueries.Authority a) { return tx.execute(s->{orders.sellerOrder(seller,a);return jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE seller_order_id=:id ORDER BY created_at,id",Map.of("id",seller),UUID.class).stream().map(this::view).toList();}); }
 @Override public List<View> buyer(String number,OrderQueries.Authority a) { return tx.execute(s->{orders.requireOwned(number,a);return jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE order_number=:n ORDER BY created_at,id",Map.of("n",number),UUID.class).stream().map(this::view).toList();}); }
 private static List<Item> original(OrderQueries.SellerOrder order) { return order.items().stream().map(i->new Item(i.id(),i.quantity())).sorted(java.util.Comparator.comparing(i->i.orderItemId().toString())).toList(); }
 /** Trusted worker entry: order lock is acquired before any shipment lock. */
 public void prepare(String number) {
  tx.executeWithoutResult(t->{var paid=fulfillment.lockPaid(number);if(paid==null) { return; }for(var order:paid.sellers()) {
   var existing=jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE seller_order_id=:id AND purpose_key='BASIC' FOR UPDATE",Map.of("id",order.sellerOrderId()),UUID.class);
   if(!existing.isEmpty()) { if(!items(existing.getFirst()).equals(original(order))) { throw new OrderRejected(409); }continue; }
   UUID id=UUID.randomUUID();Instant now=clock.instant();jdbc.update("INSERT INTO marketplace.shipment(id,order_id,seller_order_id,order_number,purpose_key,state,version,created_at) VALUES(:id,:order,:seller,:number,'BASIC','PREPARING',0,:at)",Map.of("id",id,"order",paid.orderId(),"seller",order.sellerOrderId(),"number",number,"at",Timestamp.from(now)));
   for(var item:original(order)) { jdbc.update("INSERT INTO marketplace.shipment_item(shipment_id,seller_order_id,order_item_id,quantity) VALUES(:id,:seller,:item,:q)",Map.of("id",id,"seller",order.sellerOrderId(),"item",item.orderItemId(),"q",item.quantity())); }
   after("ITEMS");record(new Header(id,order.sellerOrderId(),number,"PREPARING",0),"basic","SYSTEM","fulfillment-worker","basic:"+order.sellerOrderId(),now,"PREPARING",0,true);
   append("SHIPMENT_CREATED","created:"+id,id.toString(),now);after("OUTBOX");
  }});
 }
 @Override public Result ship(UUID id,OrderQueries.Authority a,Command c) {
  if(c==null||c.key()==null||!c.key().matches("[A-Za-z0-9_-]{1,100}")||c.version()==null||c.version()<0||c.items()==null||c.items().isEmpty()||c.items().size()>20) { throw new OrderRejected(400); }
  var seen=new java.util.HashSet<UUID>();
  for(var item:c.items()) { if(item==null||item.orderItemId()==null||!seen.add(item.orderItemId())||item.quantity()<1||item.quantity()>99) { throw new OrderRejected(400); } }
  var requested=c.items().stream().sorted(java.util.Comparator.comparing(i->i.orderItemId().toString())).toList();
  return tx.execute(t->{var hint=header(id,false);var order=orders.writePaidSellerOrder(hint.seller(),a);var h=header(id,true);orders.writePaidSellerOrder(h.seller(),a);
   if(!requested.equals(original(order))||!items(id).equals(requested)) { throw new OrderRejected(409); }
   String content="SHIPPED|"+c.version()+"|"+requested;String key="seller:"+c.key();var prior=prior(id,key,"SELLER",a.member().toString(),content);if(prior!=null) { return prior; }
   if(h.version()!=c.version()||!ShipmentRules.allowed(h.state(),"SHIPPED","SELLER")) { throw new OrderRejected(409); }
   Instant now=clock.instant();var result=change(h,key,"SELLER",a.member().toString(),content,now,"SHIPPED",true);
   append("SHIPMENT_PROGRESS","progress:"+id+":IN_TRANSIT",id+"|IN_TRANSIT|1",now.plusSeconds(5));after("OUTBOX");return result;
  });
 }
 /** Only registered internal jobs call this; no public HTTP status or actor input exists. */
 public Result progress(UUID id,String key,String target,long expected,Instant occurred) {
  return tx.execute(t->{var hint=header(id,false);if(fulfillment.lockPaid(hint.number())==null) { throw new OrderRejected(409); }var h=header(id,true);
   if(!List.of("IN_TRANSIT","DELIVERED").contains(target)||occurred==null||expected<1) { throw new OrderRejected(400); }
   String content=target+"|"+expected+"|"+occurred;var prior=prior(id,key,"MOCK_PROVIDER","mock-shipment-worker",content);if(prior!=null) { return prior; }
   boolean apply=ShipmentRules.rank(target)>ShipmentRules.rank(h.state());
   if(apply&&(h.version()!=expected||!ShipmentRules.allowed(h.state(),target,"MOCK_PROVIDER"))) { throw new OrderRejected(409); }
   var result=change(h,key,"MOCK_PROVIDER","mock-shipment-worker",content,occurred,apply?target:h.state(),apply);
   if(apply&&target.equals("IN_TRANSIT")) { append("SHIPMENT_PROGRESS","progress:"+id+":DELIVERED",id+"|DELIVERED|2",occurred.plusSeconds(5));after("OUTBOX"); }return result;
  });
 }
 private Result prior(UUID id,String key,String source,String actor,String content) {
  var rows=jdbc.queryForList("SELECT * FROM marketplace.shipment_event WHERE shipment_id=:id AND event_key=:key",Map.of("id",id,"key",key));if(rows.isEmpty()) { return null; }var r=rows.getFirst();
  if(!r.get("source").equals(source)||!r.get("actor").equals(actor)||!r.get("content").equals(content)) { throw new OrderRejected(409); }
  return new Result(id,(String)r.get("next_state"),((Number)r.get("version")).longValue(),(Boolean)r.get("applied"),(UUID)r.get("id"));
 }
 private Result change(Header h,String key,String source,String actor,String content,Instant occurred,String next,boolean applied) {
  long version=applied?Math.addExact(h.version(),1):h.version();if(applied) { jdbc.update("UPDATE marketplace.shipment SET state=:state,version=:v WHERE id=:id",Map.of("state",next,"v",version,"id",h.id())); }after("STATE");return record(h,key,source,actor,content,occurred,next,version,applied);
 }
 private Result record(Header h,String key,String source,String actor,String content,Instant occurred,String next,long version,boolean applied) {
  UUID event=UUID.randomUUID();var p=new MapSqlParameterSource("id",event).addValue("shipment",h.id()).addValue("key",key).addValue("source",source).addValue("actor",actor).addValue("content",content).addValue("occurred",Timestamp.from(occurred)).addValue("processed",Timestamp.from(clock.instant())).addValue("before",h.state()).addValue("after",next).addValue("version",version).addValue("applied",applied);
  jdbc.update("INSERT INTO marketplace.shipment_event(id,shipment_id,event_key,source,actor,content,occurred_at,processed_at,previous_state,next_state,version,applied) VALUES(:id,:shipment,:key,:source,:actor,:content,:occurred,:processed,:before,:after,:version,:applied)",p);after("EVENT");
  jdbc.update("INSERT INTO marketplace.shipment_audit(event_id,shipment_id,source,actor,processed_at) VALUES(:id,:shipment,:source,:actor,:processed)",p);after("AUDIT");return new Result(h.id(),next,version,applied,event);
 }
 private void append(String type,String key,String payload,Instant at) { outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command(type,key,key,payload,at))); }
}
