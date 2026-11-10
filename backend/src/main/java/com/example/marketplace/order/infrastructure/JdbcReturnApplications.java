package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.ReturnSources;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.order.api.OrderRejected;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public final class JdbcReturnApplications implements ReturnApplications {
 @org.springframework.beans.factory.annotation.Autowired private com.example.marketplace.order.api.PhysicalOrders physical;
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final ReturnSources sources;
 private final OrderUnitHolds holds;private final AftercareLifecycle lifecycle;private final Clock clock;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 @Autowired public JdbcReturnApplications(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnSources sources,OrderUnitHolds holds,AftercareLifecycle lifecycle) {
  this(jdbc,queries,sources,holds,lifecycle,Clock.systemUTC());
 }
 public JdbcReturnApplications(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnSources sources,OrderUnitHolds holds,AftercareLifecycle lifecycle,Clock clock) {
  this.jdbc=jdbc;this.queries=queries;this.sources=sources;this.holds=holds;this.lifecycle=lifecycle;this.clock=clock;
 }
 @Override public Current current(UUID orderId) {
  AftercareLifecycle.transaction();String state=lifecycle.lockOrder(orderId);return new Current(orderId,state,lifecycle.revision(orderId));
 }
 @Override public Eligibility eligibility(String number,OrderQueries.Authority authority) {
  AftercareLifecycle.transaction();var own=queries.lockOwned(number,authority);var selected=jdbc.query("SELECT item_id,ordinal FROM marketplace.order_unit_lifecycle WHERE order_id=:id ORDER BY item_id::text,ordinal",Map.of("id",own.id()),(r,n)->new OrderConfirmations.Unit(r.getObject(1,UUID.class),r.getInt(2)));
  var rows=new java.util.ArrayList<Map<String,Object>>();for(var unit:selected) { rows.add(lifecycle.lockUnit(own.id(),unit)); }
  var policy=lifecycle.policy(own.id());queries.lockOwned(number,authority);var now=clock.instant();var units=new java.util.ArrayList<EligibilityUnit>();
  for(int index=0;index<selected.size();index++) { var unit=selected.get(index);var row=rows.get(index);var delivered=lifecycle.delivered(unit);var due=delivered==null||!policy.get("status").equals("ACTIVE")?null:lifecycle.due(own.id(),unit,true);String state=row.get("state").toString();String reason=!own.state().equals("PAID")?"ORDER_NOT_PAID":!state.equals("OPEN")?"UNIT_"+state:due==null?"DELIVERY_OR_POLICY_UNAVAILABLE":!now.isBefore(due)?"RETURN_WINDOW_EXPIRED":"ELIGIBLE";units.add(new EligibilityUnit((UUID)row.get("seller_order_id"),unit.itemId(),unit.ordinal(),state,delivered,due,reason.equals("ELIGIBLE"),reason)); }
  queries.lockOwned(number,authority);return new Eligibility(number,own.state(),lifecycle.revision(own.id()),units);
 }
 @Override public Accepted begin(String number,OrderQueries.Authority authority,UUID id,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> input) {
  AftercareLifecycle.transaction();if(id==null||holdId==null||causeId==null) { throw new OrderRejected(400); }
  var units=AftercareLifecycle.canonical(input);var own=queries.lockOwned(number,authority);
  String hash=AftercareLifecycle.hash(own.id()+"|"+id+"|"+holdId+"|"+causeId+"|"+AftercareLifecycle.unitsText(units));
  var prior=jdbc.queryForList("SELECT * FROM marketplace.order_return_application WHERE id=:id OR cause_id=:cause FOR UPDATE",Map.of("id",id,"cause",causeId));
  if(!prior.isEmpty()) {
   if(prior.size()!=1||!hash.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }
   var row=prior.getFirst();queries.lockOwned(number,authority);
   return new Accepted(id,own.id(),(UUID)row.get("seller_order_id"),holdId,causeId,((Timestamp)row.get("requested_at")).toInstant(),((Number)row.get("window_seconds")).longValue(),mapper.readValue(row.get("source_snapshot").toString(),ReturnSources.Selection.class));
  }
  var selected=sources.lock(number,authority,units,null);
  holds.acquire(own.id(),holdId,causeId,"RETURN",units);
  queries.lockOwned(number,authority);
  var requestedAt=clock.instant().truncatedTo(ChronoUnit.MICROS);
  long seconds=((Number)lifecycle.policy(own.id()).get("claim_window_seconds")).longValue();
  var snapshot=new ReturnSources.Selection(selected.orderId(),selected.sellerOrderId(),selected.checkoutId(),selected.mode(),lifecycle.revision(own.id()),selected.units());
  int schema=snapshot.units().stream().anyMatch(u->u.physical()!=null)?2:1;var parameters=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("order",own.id()).addValue("seller",selected.sellerOrderId()).addValue("hold",holdId).addValue("cause",causeId).addValue("at",Timestamp.from(requestedAt)).addValue("seconds",seconds).addValue("hash",hash).addValue("snapshot",mapper.writeValueAsString(snapshot)).addValue("schema",schema);
  jdbc.update("INSERT INTO marketplace.order_return_application(id,order_id,seller_order_id,hold_id,cause_id,requested_at,window_seconds,fingerprint,source_snapshot,source_schema_version) VALUES(:id,:order,:seller,:hold,:cause,:at,:seconds,:hash,CAST(:snapshot AS jsonb),:schema)",parameters);
  if(schema==2) { for(var unit:snapshot.units()) { if(unit.physical()==null) { throw new OrderRejected(409); }jdbc.update("INSERT INTO marketplace.order_return_physical_unit(return_id,item_id,ordinal,physical_id,source_snapshot) VALUES(:id,:item,:ordinal,:physical,CAST(:physicalSnapshot AS jsonb))",parameters.addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("physical",unit.physical().id()).addValue("physicalSnapshot",mapper.writeValueAsString(unit.physical()))); } }
  return new Accepted(id,own.id(),selected.sellerOrderId(),holdId,causeId,requestedAt,seconds,snapshot);
 }
 @Override public Accepted lockOwner(String number,OrderQueries.Authority authority,UUID id) {
  AftercareLifecycle.transaction();var own=queries.lockOwned(number,authority);var accepted=load(id);
  if(!accepted.orderId().equals(own.id())) { throw new OrderRejected(404); }
  validate(accepted);queries.lockOwned(number,authority);return accepted;
 }
 @Override public Accepted lockSeller(OrderQueries.Authority authority,UUID id) {
  AftercareLifecycle.transaction();var accepted=load(id);queries.writePaidSellerOrder(accepted.sellerOrderId(),authority);
  validate(accepted);queries.writePaidSellerOrder(accepted.sellerOrderId(),authority);return accepted;
 }
 @Override public Recorded lockRecorded(UUID id) {
  AftercareLifecycle.transaction();var accepted=load(id);String orderState=lifecycle.lockOrder(accepted.orderId());
  var units=AftercareLifecycle.canonical(accepted.source().units().stream().map(u->new OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList());
  for(var unit:units) { lifecycle.lockUnit(accepted.orderId(),unit); }
  var hold=holds.inspect(accepted.orderId(),accepted.holdId());
  if(!accepted.source().orderId().equals(accepted.orderId())||!accepted.source().sellerOrderId().equals(accepted.sellerOrderId())||!hold.kind().equals("RETURN")||!hold.causeId().equals(accepted.causeId())||!hold.units().equals(units)) { throw new OrderRejected(409); }
  var parent=jdbc.queryForMap("SELECT checkout_id,mode FROM marketplace.purchase_order WHERE id=:id",Map.of("id",accepted.orderId()));
  if(!accepted.source().checkoutId().equals(parent.get("checkout_id"))||!accepted.source().mode().equals(parent.get("mode"))) { throw new OrderRejected(409); }
  validateOriginalSource(accepted);
  String outcome=jdbc.queryForObject("SELECT outcome FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",accepted.holdId()),String.class);
  RecordedHoldState state;try { state=RecordedHoldState.valueOf(hold.state()); }catch(IllegalArgumentException invalid) { throw new OrderRejected(409); }
  if(state==RecordedHoldState.ACTIVE&&outcome!=null||state==RecordedHoldState.RELEASED&&!List.of("REJECTED","WITHDRAWN").contains(outcome==null?"":outcome)||state==RecordedHoldState.REFUNDED&&(outcome==null||outcome.isBlank())) { throw new OrderRejected(409); }
  for(var unit:units) {
   var row=lifecycle.lockUnit(accepted.orderId(),unit);
   if(!accepted.sellerOrderId().equals(row.get("seller_order_id"))||state==RecordedHoldState.ACTIVE&&(!row.get("state").equals("HELD")||!accepted.holdId().equals(row.get("active_hold_id")))||state==RecordedHoldState.REFUNDED&&(!row.get("state").equals("REFUNDED")||!accepted.causeId().equals(row.get("refund_cause_id"))||row.get("active_hold_id")!=null)) { throw new OrderRejected(409); }
  }
  return new Recorded(accepted,state,outcome,orderState);
 }
 private void validateOriginalSource(Accepted accepted) {
  if(accepted.source().units().stream().anyMatch(u->u.physical()!=null)) { validatePhysical(accepted); }
  for(var unit:accepted.source().units()) {
   var rows=jdbc.queryForList("SELECT i.sku_id,u.net,p.status,p.policy_snapshot::text,d.event_id,d.shipment_id,d.occurred_at FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id JOIN marketplace.order_item_return_policy p ON p.item_id=i.id JOIN marketplace.order_initial_delivery_unit du ON du.item_id=u.item_id AND du.ordinal=u.ordinal JOIN marketplace.order_initial_delivery d ON d.event_id=du.event_id WHERE i.order_id=:order AND i.seller_order_id=:seller AND u.item_id=:item AND u.ordinal=:ordinal AND du.order_id=:order AND du.seller_order_id=:seller",Map.of("order",accepted.orderId(),"seller",accepted.sellerOrderId(),"item",unit.itemId(),"ordinal",unit.ordinal()));
   if(rows.size()!=1) { throw new OrderRejected(409); }var row=rows.getFirst();var policy=row.get("policy_snapshot")==null?null:mapper.readValue(row.get("policy_snapshot").toString(),com.example.marketplace.catalog.api.ReturnPolicy.class);
   if(!unit.skuId().equals(row.get("sku_id"))||unit.net()!=((Number)row.get("net")).longValue()||!unit.policyStatus().equals(row.get("status"))||!java.util.Objects.equals(unit.returnPolicy(),policy)||!unit.deliveryEventId().equals(row.get("event_id"))||!unit.originalShipmentId().equals(row.get("shipment_id"))||!unit.deliveredAt().equals(((Timestamp)row.get("occurred_at")).toInstant())||!accepted.requestedAt().isBefore((unit.physical()==null?unit.deliveredAt():unit.physical().delivery().deliveredAt()).plusSeconds(accepted.windowSeconds()))) { throw new OrderRejected(409); }
  }
 }
 private void validatePhysical(Accepted accepted) {
  int schema=jdbc.queryForObject("SELECT source_schema_version FROM marketplace.order_return_application WHERE id=:id",Map.of("id",accepted.id()),Integer.class);if(schema!=2) { throw new OrderRejected(409); }
  for(var unit:accepted.source().units()) { var selected=unit.physical();if(selected==null||selected.delivery()==null||!selected.orderId().equals(accepted.orderId())||!selected.sellerOrderId().equals(accepted.sellerOrderId())||!selected.itemId().equals(unit.itemId())||selected.ordinal()!=unit.ordinal()||!selected.equals(physical.recorded(selected.id()))||!accepted.requestedAt().isBefore(selected.delivery().deliveredAt().plusSeconds(accepted.windowSeconds()))) { throw new OrderRejected(409); }var snapshots=jdbc.queryForList("SELECT source_snapshot::text FROM marketplace.order_return_physical_unit WHERE return_id=:id AND item_id=:item AND ordinal=:ordinal AND physical_id=:physical",Map.of("id",accepted.id(),"item",unit.itemId(),"ordinal",unit.ordinal(),"physical",selected.id()),String.class);if(snapshots.size()!=1||!selected.equals(mapper.readValue(snapshots.getFirst(),com.example.marketplace.order.api.PhysicalOrders.Source.class))) { throw new OrderRejected(409); } }
 }
 private Accepted load(UUID id) {
  if(id==null) { throw new OrderRejected(404); }
  var rows=jdbc.queryForList("SELECT * FROM marketplace.order_return_application WHERE id=:id",Map.of("id",id));
  if(rows.size()!=1) { throw new OrderRejected(404); }var row=rows.getFirst();
  return new Accepted(id,(UUID)row.get("order_id"),(UUID)row.get("seller_order_id"),(UUID)row.get("hold_id"),(UUID)row.get("cause_id"),((Timestamp)row.get("requested_at")).toInstant(),((Number)row.get("window_seconds")).longValue(),mapper.readValue(row.get("source_snapshot").toString(),ReturnSources.Selection.class));
 }
 private void validate(Accepted a) {
  if(a.source().units().stream().anyMatch(u->u.physical()!=null)) { validatePhysical(a); }
  var units=AftercareLifecycle.canonical(a.source().units().stream().map(u->new OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList());
  var hold=holds.inspect(a.orderId(),a.holdId());
  if(!a.source().orderId().equals(a.orderId())||!a.source().sellerOrderId().equals(a.sellerOrderId())||!hold.kind().equals("RETURN")||!hold.state().equals("ACTIVE")||!hold.causeId().equals(a.causeId())||!hold.units().equals(units)) { throw new OrderRejected(409); }
  for(var unit:units) { var row=lifecycle.lockUnit(a.orderId(),unit);if(!row.get("state").equals("HELD")||!a.holdId().equals(row.get("active_hold_id"))||!a.sellerOrderId().equals(row.get("seller_order_id"))) { throw new OrderRejected(409); } }
 }
}
