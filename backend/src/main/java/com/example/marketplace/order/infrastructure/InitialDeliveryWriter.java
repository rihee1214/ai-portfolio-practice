package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.FulfillmentOrders;
import com.example.marketplace.order.api.OrderRejected;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;
import java.util.HexFormat;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Order-owned persistence; receives trusted facts without reading another module's tables. */
final class InitialDeliveryWriter {
 private InitialDeliveryWriter() { }
 static boolean record(NamedParameterJdbcTemplate jdbc,FulfillmentOrders.InitialDelivery f) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(f==null||f.schemaVersion()!=1||f.orderId()==null||f.sellerOrderId()==null||f.shipmentId()==null||f.eventId()==null||f.occurredAt()==null||f.units()==null||f.units().isEmpty()||f.units().stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.ordinal()>98)||f.units().stream().distinct().count()!=f.units().size()) { throw new OrderRejected(400); }
  var units=f.units().stream().sorted(java.util.Comparator.comparing((FulfillmentOrders.Unit u)->u.itemId().toString()).thenComparingInt(FulfillmentOrders.Unit::ordinal)).toList();
  var at=f.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  String fingerprint=hash("1|"+f.orderId()+"|"+f.sellerOrderId()+"|"+f.shipmentId()+"|"+f.eventId()+"|"+at+"|"+units);
  // Re-lock the already owned parent; never acquire accounts/stores or another order here.
  var state=jdbc.queryForList("SELECT state FROM marketplace.purchase_order WHERE id=:id FOR UPDATE",Map.of("id",f.orderId()),String.class);
  if(state.size()!=1) { throw new OrderRejected(409); }
  var old=jdbc.queryForList("SELECT fingerprint FROM marketplace.order_initial_delivery WHERE event_id=:id",Map.of("id",f.eventId()),String.class);
  if(!old.isEmpty()) { if(!old.getFirst().equals(fingerprint)) { throw new OrderRejected(409); }return false; }
  if(!state.getFirst().equals("PAID")) { throw new OrderRejected(409); }
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.seller_order WHERE id=:seller AND order_id=:order",Map.of("seller",f.sellerOrderId(),"order",f.orderId()),Integer.class)!=1) { throw new OrderRejected(409); }
  for(var unit:units) {
   var p=Map.of("item",unit.itemId(),"ordinal",unit.ordinal(),"seller",f.sellerOrderId(),"order",f.orderId());
   if(jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE u.item_id=:item AND u.ordinal=:ordinal AND i.seller_order_id=:seller AND i.order_id=:order",p,Integer.class)!=1||jdbc.queryForObject("SELECT count(*) FROM marketplace.order_initial_delivery_unit WHERE item_id=:item AND ordinal=:ordinal",p,Integer.class)!=0) { throw new OrderRejected(409); }
  }
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("event",f.eventId()).addValue("order",f.orderId()).addValue("seller",f.sellerOrderId()).addValue("shipment",f.shipmentId()).addValue("at",Timestamp.from(at)).addValue("hash",fingerprint);
  jdbc.update("INSERT INTO marketplace.order_initial_delivery(event_id,schema_version,order_id,seller_order_id,shipment_id,occurred_at,fingerprint) VALUES(:event,1,:order,:seller,:shipment,:at,:hash)",p);
  for(var u:units) { jdbc.update("INSERT INTO marketplace.order_initial_delivery_unit(item_id,ordinal,event_id,order_id,seller_order_id) VALUES(:item,:ordinal,:event,:order,:seller)",p.addValue("item",u.itemId()).addValue("ordinal",u.ordinal())); }
  return true;
 }
 private static String hash(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
