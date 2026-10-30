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
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 @Autowired public JdbcShipments(NamedParameterJdbcTemplate jdbc,OrderQueries orders,FulfillmentOrders fulfillment,Outbox outbox,PlatformTransactionManager manager) { this(jdbc,orders,fulfillment,outbox,manager,Clock.systemUTC(),stage->{ }); }
 public JdbcShipments(NamedParameterJdbcTemplate jdbc,OrderQueries orders,FulfillmentOrders fulfillment,Outbox outbox,PlatformTransactionManager manager,Clock clock,Checkpoint checkpoint) { this.jdbc=jdbc;this.orders=orders;this.fulfillment=fulfillment;this.outbox=outbox;this.tx=new TransactionTemplate(manager);this.clock=clock;this.checkpoint=checkpoint; }
 private void after(String stage) { try { checkpoint.after(stage); }catch(RuntimeException e) { throw e; }catch(Exception e) { throw new IllegalStateException("SHIPMENT_TRANSACTION_FAILED",e); } }
 private record Header(UUID id,UUID order,UUID seller,String number,String state,long version,String tracking,String exception,long epoch) { }
 private Header header(UUID id,boolean lock) {
  var rows=jdbc.query("SELECT * FROM marketplace.shipment WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id),(r,n)->new Header(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("seller_order_id",UUID.class),r.getString("order_number"),r.getString("state"),r.getLong("version"),r.getString("tracking"),r.getString("exception_status"),r.getLong("progress_epoch")));
  if(rows.isEmpty()) { throw new OrderRejected(404); }return rows.getFirst();
 }
 private List<Item> items(UUID id) { return jdbc.query("SELECT order_item_id,quantity FROM marketplace.shipment_item WHERE shipment_id=:id ORDER BY order_item_id::text",Map.of("id",id),(r,n)->new Item(r.getObject(1,UUID.class),r.getInt(2))); }
 private List<Unit> units(UUID id) { return jdbc.query("SELECT order_item_id,ordinal FROM marketplace.shipment_unit WHERE shipment_id=:id ORDER BY order_item_id::text,ordinal",Map.of("id",id),(r,n)->new Unit(r.getObject(1,UUID.class),r.getInt(2))); }
 private View view(UUID id) {
  var h=header(id,false);var events=jdbc.query("SELECT * FROM marketplace.shipment_event WHERE shipment_id=:id ORDER BY event_sequence",Map.of("id",id),(r,n)->new Event(r.getObject("id",UUID.class),r.getString("event_key"),r.getString("source"),r.getString("actor"),r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("processed_at").toInstant(),r.getString("previous_state"),r.getString("next_state"),r.getLong("version"),r.getBoolean("applied")));
  var history=jdbc.query("SELECT h.* FROM marketplace.shipment_tracking_history h JOIN marketplace.shipment_event e ON e.id=h.event_id WHERE h.shipment_id=:id ORDER BY e.event_sequence",Map.of("id",id),(r,n)->new TrackingHistory(r.getObject("event_id",UUID.class),r.getString("previous_tracking"),r.getString("next_tracking"),r.getString("reason"),r.getString("source"),r.getString("actor"),r.getTimestamp("recorded_at").toInstant()));
  var delivered=events.stream().filter(e->e.applied()&&e.nextState().equals("DELIVERED")&&!e.previousState().equals("DELIVERED")).map(Event::occurredAt).findFirst().orElse(null);
  return new View(id,h.seller(),h.state(),h.version(),"MOCK",items(id),events,units(id),h.tracking(),history,h.exception(),h.epoch(),delivered);
 }
 @Override public List<View> seller(UUID seller,OrderQueries.Authority a) { return tx.execute(s->{orders.sellerOrder(seller,a);return jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE seller_order_id=:id ORDER BY created_at,id",Map.of("id",seller),UUID.class).stream().map(this::view).toList();}); }
 @Override public List<View> buyer(String number,OrderQueries.Authority a) { return tx.execute(s->{orders.requireOwned(number,a);return jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE order_number=:n ORDER BY created_at,id",Map.of("n",number),UUID.class).stream().map(this::view).toList();}); }
 private static List<Item> original(OrderQueries.SellerOrder order) { return order.items().stream().map(i->new Item(i.id(),i.quantity())).sorted(java.util.Comparator.comparing(i->i.orderItemId().toString())).toList(); }
 private static List<Unit> originalUnits(OrderQueries.SellerOrder order) { return order.items().stream().flatMap(i->i.units().stream().map(u->new Unit(i.id(),u.ordinal()))).sorted(java.util.Comparator.comparing((Unit u)->u.orderItemId().toString()).thenComparingInt(Unit::ordinal)).toList(); }
 private static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
 private static String digest(UUID parent,UUID seller,List<Unit> units) { return hash(parent+"|"+seller+"|"+units.stream().map(u->u.orderItemId()+":"+u.ordinal()).collect(java.util.stream.Collectors.joining(","))); }
 private void validateAllocation(UUID seller,List<Unit> original) {
  var current=jdbc.query("SELECT u.order_item_id,u.ordinal FROM marketplace.shipment_unit u JOIN marketplace.shipment s ON s.id=u.shipment_id WHERE s.seller_order_id=:id ORDER BY u.order_item_id::text,u.ordinal",Map.of("id",seller),(r,n)->new Unit(r.getObject(1,UUID.class),r.getInt(2)));
  if(!current.equals(original)||jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment_item i JOIN marketplace.shipment s ON s.id=i.shipment_id WHERE s.seller_order_id=:id AND i.quantity<>(SELECT count(*) FROM marketplace.shipment_unit u WHERE u.shipment_id=i.shipment_id AND u.order_item_id=i.order_item_id)",Map.of("id",seller),Integer.class)!=0) { throw new OrderRejected(409); }
 }
 private void insertItems(UUID id,UUID seller,List<Item> rows) { for(var item:rows) { jdbc.update("INSERT INTO marketplace.shipment_item(shipment_id,seller_order_id,order_item_id,quantity) VALUES(:id,:seller,:item,:q)",Map.of("id",id,"seller",seller,"item",item.orderItemId(),"q",item.quantity())); } }
 private void insertHeader(UUID id,UUID order,UUID seller,String number,String purpose,Instant at) { jdbc.update("INSERT INTO marketplace.shipment(id,order_id,seller_order_id,order_number,purpose_key,state,version,created_at) VALUES(:id,:order,:seller,:number,:purpose,'PREPARING',0,:at)",Map.of("id",id,"order",order,"seller",seller,"number",number,"purpose",purpose,"at",Timestamp.from(at))); }
 /** Trusted worker entry: order lock is acquired before any shipment lock. */
 public void prepare(String number) {
  tx.executeWithoutResult(t->{var paid=fulfillment.lockPaid(number);if(paid==null) { return; }for(var order:paid.sellers()) {
   fulfillment.lockUnits(order.sellerOrderId());var existing=jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE seller_order_id=:id ORDER BY id FOR UPDATE",Map.of("id",order.sellerOrderId()),UUID.class);
   var marker=jdbc.queryForList("SELECT * FROM marketplace.shipment_preparation WHERE seller_order_id=:id",Map.of("id",order.sellerOrderId()));var all=originalUnits(order);String digest=digest(paid.orderId(),order.sellerOrderId(),all);
   if(!marker.isEmpty()) { var m=marker.getFirst();if(!m.get("original_digest").equals(digest)||!existing.contains(m.get("basic_id"))||jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment s JOIN marketplace.shipment_event e ON e.shipment_id=s.id WHERE s.id=:id AND s.purpose_key='BASIC' AND e.event_key='basic' AND e.source='SYSTEM'",Map.of("id",m.get("basic_id")),Integer.class)!=1) { throw new OrderRejected(409); }validateAllocation(order.sellerOrderId(),all);continue; }
   if(!existing.isEmpty()) { throw new OrderRejected(409); }
   UUID id=UUID.randomUUID();Instant now=clock.instant();insertHeader(id,paid.orderId(),order.sellerOrderId(),number,"BASIC",now);insertItems(id,order.sellerOrderId(),original(order));
   for(var unit:all) { jdbc.update("INSERT INTO marketplace.shipment_unit(shipment_id,order_item_id,ordinal) VALUES(:id,:item,:ordinal)",Map.of("id",id,"item",unit.orderItemId(),"ordinal",unit.ordinal())); }
   jdbc.update("INSERT INTO marketplace.shipment_preparation(seller_order_id,basic_id,schema_version,original_digest) VALUES(:seller,:id,1,:digest)",Map.of("seller",order.sellerOrderId(),"id",id,"digest",digest));
   after("ITEMS");record(header(id,false),"basic","SYSTEM","fulfillment-worker","basic:"+order.sellerOrderId(),now,"PREPARING",0,true);
   append("SHIPMENT_CREATED","created:"+id,id.toString(),now);after("OUTBOX");
  }});
 }
 private static boolean key(String key) { return key!=null&&key.matches("[A-Za-z0-9_-]{1,100}"); }
 private static List<Item> command(Command c) {
  if(c==null||!key(c.key())||c.version()==null||c.version()<0||c.items()==null||c.items().isEmpty()||c.items().size()>20) { throw new OrderRejected(400); }
  var seen=new java.util.HashSet<UUID>();for(var item:c.items()) { if(item==null||item.orderItemId()==null||!seen.add(item.orderItemId())||item.quantity()<1||item.quantity()>99) { throw new OrderRejected(400); } }
  return c.items().stream().sorted(java.util.Comparator.comparing(i->i.orderItemId().toString())).toList();
 }
 private Header sellerLock(UUID id,OrderQueries.Authority a) { var hint=header(id,false);orders.lockSellerOrder(hint.seller(),a);fulfillment.lockUnits(hint.seller());jdbc.queryForList("SELECT id FROM marketplace.shipment WHERE seller_order_id=:id ORDER BY id FOR UPDATE",Map.of("id",hint.seller()),UUID.class);var h=header(id,false);var original=orders.lockSellerOrder(h.seller(),a);validateAllocation(h.seller(),originalUnits(original));return h; }
 private void paid(Header h,OrderQueries.Authority a) { if(!orders.lockSellerOrder(h.seller(),a).orderState().equals("PAID")) { throw new OrderRejected(409); } }
 private <T> T receipt(UUID id,String kind,String key,String content,Class<T> type) { var old=jdbc.queryForList("SELECT content,result::text FROM marketplace.shipment_command WHERE shipment_id=:id AND command_kind=:kind AND request_key=:key",Map.of("id",id,"kind",kind,"key",key));if(old.isEmpty()) { return null; }if(!old.getFirst().get("content").equals(hash(content))) { throw new OrderRejected(409); }return mapper.readValue((String)old.getFirst().get("result"),type); }
 private void receipt(UUID id,String kind,String key,String content,Object result) { jdbc.update("INSERT INTO marketplace.shipment_command(shipment_id,command_kind,request_key,content,result) VALUES(:id,:kind,:key,:content,CAST(:result AS jsonb))",Map.of("id",id,"kind",kind,"key",key,"content",hash(content),"result",mapper.writeValueAsString(result)));after("RECEIPT"); }
 @Override public SplitResult split(UUID id,OrderQueries.Authority a,Command c) {
  var requested=command(c);String content="SPLIT|"+id+"|"+c.version()+"|"+requested;
  return tx.execute(t->{var h=sellerLock(id,a);var prior=receipt(id,"SPLIT",c.key(),content,SplitResult.class);if(prior!=null) { return prior; }
   paid(h,a);if(!h.state().equals("PREPARING")||h.version()!=c.version()) { throw new OrderRejected(409); }var current=items(id);var selected=new java.util.ArrayList<Unit>();var currentUnits=units(id);
   for(var item:requested) { var ordinals=currentUnits.stream().filter(u->u.orderItemId().equals(item.orderItemId())).toList();if(item.quantity()>ordinals.size()) { throw new OrderRejected(409); }selected.addAll(ordinals.subList(0,item.quantity())); }
   if(!ShipmentRules.splitQuantities(List.of(currentUnits.size()),List.of(selected.size()))) { throw new OrderRejected(409); }
   UUID target=UUID.randomUUID();Instant now=clock.instant();insertHeader(target,h.order(),h.seller(),h.number(),"SPLIT:"+UUID.randomUUID(),now);insertItems(target,h.seller(),requested);
   for(var unit:selected) { jdbc.update("UPDATE marketplace.shipment_unit SET shipment_id=:target WHERE shipment_id=:id AND order_item_id=:item AND ordinal=:ordinal",Map.of("target",target,"id",id,"item",unit.orderItemId(),"ordinal",unit.ordinal())); }
   for(var item:current) { int moved=requested.stream().filter(i->i.orderItemId().equals(item.orderItemId())).mapToInt(Item::quantity).sum();if(moved==item.quantity()) { jdbc.update("DELETE FROM marketplace.shipment_item WHERE shipment_id=:id AND order_item_id=:item",Map.of("id",id,"item",item.orderItemId())); }else if(moved>0) { jdbc.update("UPDATE marketplace.shipment_item SET quantity=quantity-:q WHERE shipment_id=:id AND order_item_id=:item",Map.of("id",id,"item",item.orderItemId(),"q",moved)); } }
   after("UNITS");after("ITEMS");var sourceResult=change(h,"split:"+c.key(),"SELLER",a.member().toString(),content,now,h.state(),true);var targetResult=record(header(target,false),"split-created","SYSTEM","fulfillment-worker",content,now,"PREPARING",0,true);var result=new SplitResult(sourceResult,targetResult,selected);receipt(id,"SPLIT",c.key(),content,result);append("SHIPMENT_CREATED","created:"+target,target.toString(),now);after("OUTBOX");return result;
  });
 }
 @Override public Result tracking(UUID id,OrderQueries.Authority a,TrackingCommand c) {
  if(c==null||!key(c.key())||c.version()==null||c.version()<0||!ShipmentRules.tracking(c.tracking(),c.reason())) { throw new OrderRejected(400); }String content="TRACKING|"+id+"|"+c.version()+"|"+c.tracking()+"|"+c.reason();
  return tx.execute(t->{var h=sellerLock(id,a);var prior=receipt(id,"TRACKING",c.key(),content,Result.class);if(prior!=null) { return prior; }paid(h,a);if(h.state().equals("DELIVERED")||h.version()!=c.version()||c.tracking().equals(h.tracking())) { throw new OrderRejected(409); }
   jdbc.update("UPDATE marketplace.shipment SET tracking=:tracking WHERE id=:id",Map.of("id",id,"tracking",c.tracking()));var result=change(h,"tracking:"+c.key(),"SELLER",a.member().toString(),content,clock.instant(),h.state(),true);trackingHistory(h,result.eventId(),c.tracking(),c.reason(),"SELLER",a.member().toString());receipt(id,"TRACKING",c.key(),content,result);after("OUTBOX");return result;
  });
 }
 private void trackingHistory(Header h,UUID event,String next,String reason,String source,String actor) { jdbc.update("INSERT INTO marketplace.shipment_tracking_history(event_id,shipment_id,previous_tracking,next_tracking,reason,source,actor,recorded_at) VALUES(:event,:id,:before,:after,:reason,:source,:actor,:at)",new MapSqlParameterSource("event",event).addValue("id",h.id()).addValue("before",h.tracking()).addValue("after",next).addValue("reason",reason).addValue("source",source).addValue("actor",actor).addValue("at",Timestamp.from(clock.instant())));after("TRACKING"); }
 @Override public Result ship(UUID id,OrderQueries.Authority a,Command c) {
  var requested=command(c);return tx.execute(t->{var h=sellerLock(id,a);String content="SHIPPED|"+c.version()+"|"+requested;String key="seller:"+c.key();var prior=prior(id,key,"SELLER",a.member().toString(),content);if(prior!=null) { return prior; }
   paid(h,a);if(!items(id).equals(requested)||h.version()!=c.version()||!ShipmentRules.allowed(h.state(),"SHIPPED","SELLER")) { throw new OrderRejected(409); }
   Instant now=clock.instant();var result=change(h,key,"SELLER",a.member().toString(),content,now,"SHIPPED",true);
   Instant stored=jdbc.queryForObject("SELECT occurred_at FROM marketplace.shipment_event WHERE id=:id",Map.of("id",result.eventId()),(r,n)->r.getTimestamp(1).toInstant());fulfillment.recordDispatch(new FulfillmentOrders.Dispatch(1,h.order(),h.seller(),h.id(),result.eventId(),stored,units(id).stream().map(u->new FulfillmentOrders.Unit(u.orderItemId(),u.ordinal())).toList()));after("DISPATCH");
   if(h.tracking()==null) { String tracking="MOCK-"+id.toString().replace("-","");jdbc.update("UPDATE marketplace.shipment SET tracking=:tracking WHERE id=:id",Map.of("id",id,"tracking",tracking));trackingHistory(h,result.eventId(),tracking,"모의 발송 자동 등록","SYSTEM","fulfillment-worker"); }
   schedule(h.id(),h.epoch(),"SHIPPED","IN_TRANSIT",now.plusSeconds(5));after("OUTBOX");return result;
  });
 }
 /** v1 keeps its original content and result; old expected version encodes the logistics stage. */
 public Result progress(UUID id,String key,String target,long expected,Instant occurred) {
  if(!List.of("IN_TRANSIT","DELIVERED").contains(target)||occurred==null||expected<1) { throw new OrderRejected(400); }
  return progress(id,key,0,target.equals("IN_TRANSIT")?"SHIPPED":"IN_TRANSIT",target,occurred,target+"|"+expected+"|"+occurred);
 }
 public Result progressV2(UUID id,String key,long epoch,String stage,String target,Instant occurred) { return progress(id,key,epoch,stage,target,occurred,"v2|"+epoch+"|"+stage+"|"+target+"|"+occurred); }
 private Header workerLock(UUID id) { var hint=header(id,false);fulfillment.lockExisting(hint.number());fulfillment.lockUnits(hint.seller());return header(id,true); }
 private void workerPaid(Header h) { if(!fulfillment.lockExisting(h.number()).sellers().getFirst().orderState().equals("PAID")) { throw new OrderRejected(409); } }
 private void schedule(UUID id,long epoch,String stage,String target,Instant at) { String key=epoch==0?"progress:"+id+":"+target:"progress:v2:"+id+":"+epoch+":"+target;append("SHIPMENT_PROGRESS",key,"v2|"+id+"|"+epoch+"|"+stage+"|"+target+"|"+at.truncatedTo(java.time.temporal.ChronoUnit.MICROS),at); }
 private Result progress(UUID id,String key,long epoch,String stage,String target,Instant occurred,String content) {
  if(!List.of("IN_TRANSIT","DELIVERED").contains(target)||occurred==null||epoch<0||!ShipmentRules.allowed(stage,target,"MOCK_PROVIDER")) { throw new OrderRejected(400); }
  return tx.execute(t->{var h=workerLock(id);var prior=prior(id,key,"MOCK_PROVIDER","mock-shipment-worker",content);if(prior!=null) { return prior; }
   workerPaid(h);boolean apply=epoch==h.epoch()&&h.exception().equals("NONE")&&ShipmentRules.rank(target)>ShipmentRules.rank(h.state());
   if(apply&&(!h.state().equals(stage)||!ShipmentRules.allowed(h.state(),target,"MOCK_PROVIDER"))) { throw new OrderRejected(409); }if(h.state().equals("PREPARING")) { throw new OrderRejected(409); }
   var result=change(h,key,"MOCK_PROVIDER","mock-shipment-worker",content,occurred,apply?target:h.state(),apply);
   if(apply&&target.equals("IN_TRANSIT")) { schedule(id,h.epoch(),"IN_TRANSIT","DELIVERED",occurred.plusSeconds(5));after("OUTBOX"); }
   if(apply&&target.equals("DELIVERED")) { deliver(header(id,false),result.eventId());after("DELIVERY");after("OUTBOX"); }return result;
  });
 }
 /** Trusted mock adapter only; no browser endpoint chooses exceptions or completion. */
 public Result exception(UUID id,String key,String target,String reason,Instant occurred) {
  if(!key(key)||!List.of("DELAYED","FAILED","RESUME").contains(target)||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>200||occurred==null) { throw new OrderRejected(400); }
  return tx.execute(t->{var h=workerLock(id);String content="EXCEPTION|"+target+"|"+reason+"|"+occurred;var prior=prior(id,key,"MOCK_PROVIDER","mock-shipment-worker",content);if(prior!=null) { return prior; }if(h.state().equals("PREPARING")) { throw new OrderRejected(409); }
   workerPaid(h);if(h.state().equals("DELIVERED")) { return change(h,key,"MOCK_PROVIDER","mock-shipment-worker",content,occurred,h.state(),false); }if(target.equals("RESUME")&&h.exception().equals("NONE")) { throw new OrderRejected(409); }
   long epoch=Math.addExact(h.epoch(),1);jdbc.update("UPDATE marketplace.shipment SET exception_status=:e,progress_epoch=:epoch WHERE id=:id",Map.of("id",id,"e",target.equals("RESUME")?"NONE":target,"epoch",epoch));var result=change(h,key,"MOCK_PROVIDER","mock-shipment-worker",content,occurred,h.state(),true);
   if(target.equals("RESUME")) { schedule(id,epoch,h.state(),h.state().equals("SHIPPED")?"IN_TRANSIT":"DELIVERED",occurred.plusSeconds(5)); }after("OUTBOX");return result;
  });
 }
 private void deliver(Header h,UUID event) {
  var rows=jdbc.query("SELECT occurred_at FROM marketplace.shipment_event WHERE id=:event AND shipment_id=:id AND applied AND next_state='DELIVERED' AND previous_state<>'DELIVERED'",Map.of("event",event,"id",h.id()),(r,n)->r.getTimestamp(1).toInstant());if(rows.size()!=1) { throw new OrderRejected(409); }
  fulfillment.recordInitialDelivery(new FulfillmentOrders.InitialDelivery(1,h.order(),h.seller(),h.id(),event,rows.getFirst(),units(h.id()).stream().map(u->new FulfillmentOrders.Unit(u.orderItemId(),u.ordinal())).toList()));
 }
 public record Legacy(UUID shipment,UUID event) { }
 public List<Legacy> legacyCandidates(UUID after,int size) { return tx.execute(t->{if(size<1||size>50) { throw new OrderRejected(400); }if(jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment s WHERE s.state='DELIVERED' AND (SELECT count(*) FROM marketplace.shipment_event e WHERE e.shipment_id=s.id AND e.applied AND e.next_state='DELIVERED' AND e.previous_state<>'DELIVERED')<>1",Map.of(),Integer.class)!=0) { throw new OrderRejected(409); }return jdbc.query("SELECT s.id,e.id FROM marketplace.shipment s JOIN marketplace.shipment_event e ON e.shipment_id=s.id AND e.applied AND e.next_state='DELIVERED' AND e.previous_state<>'DELIVERED' WHERE s.state='DELIVERED'"+(after==null?"":" AND s.id>:after")+" ORDER BY s.id LIMIT :size",new MapSqlParameterSource("after",after).addValue("size",size),(r,n)->new Legacy(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));}); }
 public void migrateDelivery(UUID id,UUID event) { tx.executeWithoutResult(t->{var h=workerLock(id);if(!h.state().equals("DELIVERED")||jdbc.queryForObject("SELECT count(*) FROM marketplace.shipment_event WHERE shipment_id=:id AND applied AND next_state='DELIVERED' AND previous_state<>'DELIVERED'",Map.of("id",id),Integer.class)!=1) { throw new OrderRejected(409); }deliver(h,event);after("DELIVERY");}); }
 private Result prior(UUID id,String key,String source,String actor,String content) {
  var rows=jdbc.queryForList("SELECT * FROM marketplace.shipment_event WHERE shipment_id=:id AND event_key=:key",Map.of("id",id,"key",key));if(rows.isEmpty()) { return null; }var r=rows.getFirst();
  if(!r.get("source").equals(source)||!r.get("actor").equals(actor)||!r.get("content").equals(content)) { throw new OrderRejected(409); }
  return new Result(id,(String)r.get("next_state"),((Number)r.get("version")).longValue(),(Boolean)r.get("applied"),(UUID)r.get("id"));
 }
 private Result change(Header h,String key,String source,String actor,String content,Instant occurred,String next,boolean applied) { long version=applied?Math.addExact(h.version(),1):h.version();if(applied) { jdbc.update("UPDATE marketplace.shipment SET state=:state,version=:v WHERE id=:id",Map.of("state",next,"v",version,"id",h.id())); }after("STATE");return record(h,key,source,actor,content,occurred,next,version,applied); }
 private Result record(Header h,String key,String source,String actor,String content,Instant occurred,String next,long version,boolean applied) {
  UUID event=UUID.randomUUID();var p=new MapSqlParameterSource("id",event).addValue("shipment",h.id()).addValue("key",key).addValue("source",source).addValue("actor",actor).addValue("content",content).addValue("occurred",Timestamp.from(occurred)).addValue("processed",Timestamp.from(clock.instant())).addValue("before",h.state()).addValue("after",next).addValue("version",version).addValue("applied",applied);
  jdbc.update("INSERT INTO marketplace.shipment_event(id,shipment_id,event_key,source,actor,content,occurred_at,processed_at,previous_state,next_state,version,applied) VALUES(:id,:shipment,:key,:source,:actor,:content,:occurred,:processed,:before,:after,:version,:applied)",p);after("EVENT");
  jdbc.update("INSERT INTO marketplace.shipment_audit(event_id,shipment_id,source,actor,processed_at) VALUES(:id,:shipment,:source,:actor,:processed)",p);after("AUDIT");return new Result(h.id(),next,version,applied,event);
 }
 private void append(String type,String key,String payload,Instant at) { outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command(type,key,key,payload,at))); }
}
