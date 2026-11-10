package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.FulfillmentOrders;
import com.example.marketplace.order.api.OrderConfirmations.Unit;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.domain.ConfirmationRules;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Order-owned facts and scheduling. No fulfillment runtime dependency or table reads. */
@Service @Profile("db")
public final class AftercareLifecycle {
 private final NamedParameterJdbcTemplate jdbc;private final Outbox outbox;
 public AftercareLifecycle(NamedParameterJdbcTemplate jdbc,Outbox outbox) { this.jdbc=jdbc;this.outbox=outbox; }
 static void initialize(NamedParameterJdbcTemplate jdbc,UUID order,AftercarePolicySource.Policy policy,Instant at) {
  var p=new MapSqlParameterSource("id",order).addValue("version",policy.version()).addValue("delay",policy.confirmationDelaySeconds()).addValue("window",policy.claimWindowSeconds()).addValue("at",Timestamp.from(ConfirmationRules.normalized(at)));
  jdbc.update("INSERT INTO marketplace.order_aftercare_policy(order_id,schema_version,status,policy_version,confirmation_delay_seconds,claim_window_seconds,captured_at,effective_at,source) VALUES(:id,1,'ACTIVE',:version,:delay,:window,:at,:at,'ORDER_CREATION')",p);
  jdbc.update("INSERT INTO marketplace.order_confirmation_state(order_id) VALUES(:id)",p);
  jdbc.update("INSERT INTO marketplace.order_unit_lifecycle(item_id,ordinal,order_id,seller_order_id) SELECT u.item_id,u.ordinal,i.order_id,i.seller_order_id FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:id",p);
 }
 static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); } }
 public String lockOrder(UUID id) { transaction();var rows=jdbc.queryForList("SELECT state FROM marketplace.purchase_order WHERE id=:id FOR UPDATE",Map.of("id",id),String.class);if(rows.size()!=1) { throw new OrderRejected(404); }return rows.getFirst(); }
 public void lockSellerUnits(UUID seller) { transaction();jdbc.queryForList("SELECT item_id FROM marketplace.order_unit_lifecycle WHERE seller_order_id=:id ORDER BY item_id::text,ordinal FOR UPDATE",Map.of("id",seller)); }
 static List<Unit> canonical(List<Unit> input) {
  if(input==null||input.isEmpty()||input.size()>1980||input.stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.ordinal()>98)||input.stream().distinct().count()!=input.size()) { throw new OrderRejected(400); }
  return input.stream().sorted(java.util.Comparator.comparing((Unit u)->u.itemId().toString()).thenComparingInt(Unit::ordinal)).toList();
 }
 static String unitsText(List<Unit> units) { return units.stream().map(u->u.itemId()+":"+u.ordinal()).collect(java.util.stream.Collectors.joining(",")); }
 static String hash(String value) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); } }
 public Map<String,Object> lockUnit(UUID order,Unit u) {
  var rows=jdbc.queryForList("SELECT * FROM marketplace.order_unit_lifecycle WHERE order_id=:order AND item_id=:item AND ordinal=:ordinal FOR UPDATE",Map.of("order",order,"item",u.itemId(),"ordinal",u.ordinal()));if(rows.size()!=1) { throw new OrderRejected(409); }return rows.getFirst();
 }
 public long revision(UUID order) { var rows=jdbc.queryForList("SELECT revision FROM marketplace.order_confirmation_state WHERE order_id=:id",Map.of("id",order),Long.class);if(rows.size()!=1) { throw new OrderRejected(409); }return rows.getFirst(); }
 public long bump(UUID order) { return jdbc.queryForObject("UPDATE marketplace.order_confirmation_state SET revision=revision+1 WHERE order_id=:id RETURNING revision",Map.of("id",order),Long.class); }
 public Map<String,Object> policy(UUID order) { var rows=jdbc.queryForList("SELECT * FROM marketplace.order_aftercare_policy WHERE order_id=:id",Map.of("id",order));if(rows.size()!=1) { throw new OrderRejected(409); }return rows.getFirst(); }
 public Instant delivered(Unit unit) { var current=jdbc.queryForList("SELECT p.round,d.delivered_at FROM marketplace.order_physical_current c JOIN marketplace.order_physical_source p ON p.id=c.physical_id LEFT JOIN marketplace.order_physical_delivery d ON d.physical_id=p.id WHERE c.item_id=:i AND c.ordinal=:o",Map.of("i",unit.itemId(),"o",unit.ordinal()));if(!current.isEmpty()&&((Number)current.getFirst().get("round")).intValue()>0) { return current.getFirst().get("delivered_at")==null?null:((Timestamp)current.getFirst().get("delivered_at")).toInstant(); }var rows=jdbc.query("SELECT f.occurred_at FROM marketplace.order_initial_delivery_unit u JOIN marketplace.order_initial_delivery f ON f.event_id=u.event_id WHERE u.item_id=:i AND u.ordinal=:o",Map.of("i",unit.itemId(),"o",unit.ordinal()),(r,n)->r.getTimestamp(1).toInstant());return rows.isEmpty()?null:rows.getFirst(); }
 public Instant due(UUID order,Unit unit,boolean claim) { var p=policy(order);Instant at=delivered(unit);return at==null||!p.get("status").equals("ACTIVE")?null:ConfirmationRules.due(at,((Number)p.get(claim?"claim_window_seconds":"confirmation_delay_seconds")).longValue()); }
 public void dispatch(FulfillmentOrders.Dispatch f) {
  transaction();if(f==null||f.schemaVersion()!=1||f.orderId()==null||f.sellerOrderId()==null||f.shipmentId()==null||f.eventId()==null||f.occurredAt()==null) { throw new OrderRejected(400); }
  var units=canonical(f.units()==null?null:f.units().stream().map(u->new Unit(u.itemId(),u.ordinal())).toList());String state=lockOrder(f.orderId());for(var u:units) { lockUnit(f.orderId(),u); }
  String at=new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(ConfirmationRules.normalized(f.occurredAt()));String fingerprint=hash("1|"+f.orderId()+"|"+f.sellerOrderId()+"|"+f.shipmentId()+"|"+f.eventId()+"|"+at+"|"+unitsText(units));
  var old=jdbc.queryForList("SELECT fingerprint FROM marketplace.order_dispatch WHERE event_id=:id",Map.of("id",f.eventId()),String.class);if(!old.isEmpty()) { if(!old.getFirst().equals(fingerprint)) { throw new OrderRejected(409); }return; }
  if(!state.equals("PAID")) { throw new OrderRejected(409); }
  for(var u:units) { var row=lockUnit(f.orderId(),u);if(!row.get("seller_order_id").equals(f.sellerOrderId())||!row.get("state").equals("OPEN")||jdbc.queryForObject("SELECT count(*) FROM marketplace.order_dispatch_unit WHERE item_id=:i AND ordinal=:o",Map.of("i",u.itemId(),"o",u.ordinal()),Integer.class)!=0) { throw new OrderRejected(409); } }
  var p=new MapSqlParameterSource("e",f.eventId()).addValue("order",f.orderId()).addValue("seller",f.sellerOrderId()).addValue("shipment",f.shipmentId()).addValue("at",Timestamp.from(ConfirmationRules.normalized(f.occurredAt()))).addValue("hash",fingerprint);
  jdbc.update("INSERT INTO marketplace.order_dispatch(event_id,order_id,seller_order_id,shipment_id,occurred_at,fingerprint) VALUES(:e,:order,:seller,:shipment,:at,:hash)",p);
  for(var u:units) { jdbc.update("INSERT INTO marketplace.order_dispatch_unit(item_id,ordinal,event_id,order_id,seller_order_id) VALUES(:i,:o,:e,:order,:seller)",p.addValue("i",u.itemId()).addValue("o",u.ordinal())); }bump(f.orderId());
 }
 public void delivered(FulfillmentOrders.InitialDelivery fact) { transaction();bump(fact.orderId());for(var unit:fact.units()) { schedule(fact.orderId(),new Unit(unit.itemId(),unit.ordinal()),null); } }
 public void schedule(UUID order,Unit unit,Instant reevaluatedAt) {
  var lifecycle=lockUnit(order,unit);if(!lifecycle.get("state").equals("OPEN")) { return; }var policy=policy(order);if(!policy.get("status").equals("ACTIVE")||!lockOrder(order).equals("PAID")) { return; }
  var physical=jdbc.queryForList("SELECT p.id,d.event_id,d.delivered_at FROM marketplace.order_physical_current c JOIN marketplace.order_physical_source p ON p.id=c.physical_id LEFT JOIN marketplace.order_physical_delivery d ON d.physical_id=p.id WHERE c.item_id=:i AND c.ordinal=:o AND p.round>0",Map.of("i",unit.itemId(),"o",unit.ordinal()));
  if(!physical.isEmpty()) { var fact=physical.getFirst();if(fact.get("event_id")==null) { return; }var due=ConfirmationRules.due(((Timestamp)fact.get("delivered_at")).toInstant(),((Number)policy.get("confirmation_delay_seconds")).longValue());var effective=((Timestamp)policy.get("effective_at")).toInstant();var scheduled=due.isAfter(effective)?due:effective;if(reevaluatedAt!=null&&reevaluatedAt.isAfter(scheduled)) { scheduled=reevaluatedAt; }long generation=((Number)lifecycle.get("revision")).longValue();UUID id=UUID.randomUUID();var p=new MapSqlParameterSource("id",id).addValue("order",order).addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("generation",generation).addValue("physical",fact.get("id")).addValue("event",fact.get("event_id")).addValue("policy",policy.get("policy_version")).addValue("due",Timestamp.from(due)).addValue("at",Timestamp.from(scheduled));int added=jdbc.update("INSERT INTO marketplace.order_physical_confirmation_schedule(id,order_id,item_id,ordinal,generation,physical_id,event_id,policy_version,due_at,scheduled_at) VALUES(:id,:order,:item,:ordinal,:generation,:physical,:event,:policy,:due,:at) ON CONFLICT(item_id,ordinal,generation) DO NOTHING",p);if(added==1) { String key="physical-confirmation:"+fact.get("id")+":"+generation;outbox.append(new Outbox.Event(id,new Jobs.Command("ORDER_PHYSICAL_CONFIRM_DUE.v1",key,id.toString(),id.toString(),scheduled))); }return; }
  var facts=jdbc.queryForList("SELECT f.event_id,f.occurred_at FROM marketplace.order_initial_delivery_unit u JOIN marketplace.order_initial_delivery f ON f.event_id=u.event_id WHERE u.item_id=:i AND u.ordinal=:o",Map.of("i",unit.itemId(),"o",unit.ordinal()));if(facts.isEmpty()) { return; }
  var fact=facts.getFirst();Instant due=ConfirmationRules.due(((Timestamp)fact.get("occurred_at")).toInstant(),((Number)policy.get("confirmation_delay_seconds")).longValue());Instant effective=((Timestamp)policy.get("effective_at")).toInstant();Instant scheduled=due.isAfter(effective)?due:effective;if(reevaluatedAt!=null&&reevaluatedAt.isAfter(scheduled)) { scheduled=reevaluatedAt; }
  long generation=((Number)lifecycle.get("revision")).longValue();String type=reevaluatedAt==null?"ORDER_CONFIRM_DUE":"ORDER_CONFIRM_REEVALUATE";var existing=jdbc.queryForList("SELECT * FROM marketplace.order_confirmation_schedule WHERE item_id=:i AND ordinal=:o AND generation=:g FOR UPDATE",Map.of("i",unit.itemId(),"o",unit.ordinal(),"g",generation));
  if(!existing.isEmpty()) { var e=existing.getFirst();if(!e.get("delivery_event_id").equals(fact.get("event_id"))||!e.get("policy_version").equals(policy.get("policy_version"))||!((Timestamp)e.get("due_at")).toInstant().equals(due)) { throw new OrderRejected(409); }return; }
  UUID id=UUID.randomUUID();var p=new MapSqlParameterSource("id",id).addValue("order",order).addValue("i",unit.itemId()).addValue("o",unit.ordinal()).addValue("g",generation).addValue("event",fact.get("event_id")).addValue("policy",policy.get("policy_version")).addValue("due",Timestamp.from(due)).addValue("at",Timestamp.from(scheduled)).addValue("type",type);
  jdbc.update("INSERT INTO marketplace.order_confirmation_schedule(id,order_id,item_id,ordinal,generation,delivery_event_id,policy_version,due_at,scheduled_at,job_type) VALUES(:id,:order,:i,:o,:g,:event,:policy,:due,:at,:type)",p);
  String key="confirmation:"+policy.get("policy_version")+":"+unit.itemId()+":"+unit.ordinal()+":"+generation;
  outbox.append(new Outbox.Event(id,new Jobs.Command(type,key,id.toString(),id.toString(),scheduled)));
 }
}
