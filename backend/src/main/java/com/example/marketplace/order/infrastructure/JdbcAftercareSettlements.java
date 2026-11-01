package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public final class JdbcAftercareSettlements implements AftercareSettlements {
 private final NamedParameterJdbcTemplate jdbc;private final Orders orders;
 public JdbcAftercareSettlements(NamedParameterJdbcTemplate jdbc,Orders orders) { this.jdbc=jdbc;this.orders=orders; }
 private String order(UUID id) { AftercareLifecycle.transaction();var rows=jdbc.queryForList("SELECT order_number FROM marketplace.purchase_order WHERE id=:id FOR UPDATE",Map.of("id",id),String.class);if(rows.size()!=1) { throw new OrderRejected(409); }return rows.getFirst(); }
 private static void reference(UUID payment,UUID attempt,String reference) { if(payment==null||attempt==null||reference==null||reference.isBlank()||reference.length()>200) { throw new OrderRejected(400); } }
 @Override public void record(Item f) {
  if(f==null||f.orderId()==null||f.sellerOrderId()==null||f.holdId()==null||f.causeId()==null) { throw new OrderRejected(400); }reference(f.paymentId(),f.refundAttemptId(),f.reference());var units=AftercareLifecycle.canonical(f.units());order(f.orderId());String hash=AftercareLifecycle.hash(f.orderId()+"|"+f.sellerOrderId()+"|"+f.holdId()+"|"+f.causeId()+"|"+units+"|"+f.paymentId()+"|"+f.refundAttemptId()+"|"+f.reference());
  var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.order_cancellation_settlement WHERE cause_id=:id",Map.of("id",f.causeId()),String.class);if(!prior.isEmpty()) { if(!hash.equals(prior.getFirst())) { throw new OrderRejected(409); }return; }
  var hold=jdbc.queryForList("SELECT id FROM marketplace.order_unit_hold WHERE id=:hold AND cause_id=:cause AND order_id=:order AND state='REFUNDED' AND kind='CANCEL' AND outcome=:ref",Map.of("hold",f.holdId(),"cause",f.causeId(),"order",f.orderId(),"ref",f.reference()));if(hold.size()!=1) { throw new OrderRejected(409); }
  var saved=jdbc.query("SELECT item_id,ordinal FROM marketplace.order_hold_unit WHERE hold_id=:id ORDER BY item_id::text,ordinal",Map.of("id",f.holdId()),(r,n)->new OrderConfirmations.Unit(r.getObject(1,UUID.class),r.getInt(2)));if(!saved.equals(units)) { throw new OrderRejected(409); }
  for(var u:units) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE order_id=:order AND seller_order_id=:seller AND item_id=:i AND ordinal=:o AND state='REFUNDED' AND refund_cause_id=:cause",Map.of("order",f.orderId(),"seller",f.sellerOrderId(),"i",u.itemId(),"o",u.ordinal(),"cause",f.causeId()),Integer.class)!=1) { throw new OrderRejected(409); } }
  jdbc.update("INSERT INTO marketplace.order_cancellation_settlement(cause_id,order_id,seller_order_id,hold_id,payment_id,refund_attempt_id,reference,fingerprint) VALUES(:cause,:order,:seller,:hold,:payment,:attempt,:ref,:hash)",Map.of("cause",f.causeId(),"order",f.orderId(),"seller",f.sellerOrderId(),"hold",f.holdId(),"payment",f.paymentId(),"attempt",f.refundAttemptId(),"ref",f.reference(),"hash",hash));
  for(var u:units) { jdbc.update("INSERT INTO marketplace.order_cancellation_settlement_unit(item_id,ordinal,order_id,cause_id) VALUES(:i,:o,:order,:cause)",Map.of("i",u.itemId(),"o",u.ordinal(),"order",f.orderId(),"cause",f.causeId())); }
 }
 @Override public void record(Shipping f) {
  if(f==null||f.orderId()==null||f.sellerOrderId()==null||f.causeId()==null||f.amount()<0) { throw new OrderRejected(400); }reference(f.paymentId(),f.refundAttemptId(),f.reference());order(f.orderId());String hash=AftercareLifecycle.hash(f.toString());var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.order_cancellation_shipping WHERE seller_order_id=:id",Map.of("id",f.sellerOrderId()),String.class);if(!prior.isEmpty()) { if(!hash.equals(prior.getFirst())) { throw new OrderRejected(409); }return; }
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.seller_order WHERE id=:seller AND order_id=:order AND shipping_base-shipping_discount=:amount",Map.of("seller",f.sellerOrderId(),"order",f.orderId(),"amount",f.amount()),Integer.class)!=1||!allCanceled(f.sellerOrderId())) { throw new OrderRejected(409); }
  jdbc.update("INSERT INTO marketplace.order_cancellation_shipping(seller_order_id,order_id,cause_id,payment_id,refund_attempt_id,amount,reference,fingerprint) VALUES(:seller,:order,:cause,:payment,:attempt,:amount,:ref,:hash)",Map.of("seller",f.sellerOrderId(),"order",f.orderId(),"cause",f.causeId(),"payment",f.paymentId(),"attempt",f.refundAttemptId(),"amount",f.amount(),"ref",f.reference(),"hash",hash));
 }
 private boolean allCanceled(UUID seller) { return jdbc.queryForObject("SELECT count(*)>0 AND bool_and(l.state='REFUNDED' AND s.cause_id IS NOT NULL) FROM marketplace.order_unit_lifecycle l LEFT JOIN marketplace.order_cancellation_settlement_unit s ON s.item_id=l.item_id AND s.ordinal=l.ordinal WHERE l.seller_order_id=:id",Map.of("id",seller),Boolean.class); }
 @Override public void evaluate(UUID id) {
  String number=order(id);String state=jdbc.queryForObject("SELECT state FROM marketplace.purchase_order WHERE id=:id",Map.of("id",id),String.class);if(!state.equals("PAID")) { return; }
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle l LEFT JOIN marketplace.order_cancellation_settlement_unit c ON c.item_id=l.item_id AND c.ordinal=l.ordinal WHERE l.order_id=:id AND (l.state NOT IN ('CONFIRMED','REFUNDED') OR(l.state='REFUNDED' AND c.cause_id IS NULL))",Map.of("id",id),Integer.class)!=0) { return; }
  for(UUID seller:jdbc.queryForList("SELECT id FROM marketplace.seller_order WHERE order_id=:id",Map.of("id",id),UUID.class)) { if(allCanceled(seller)&&jdbc.queryForObject("SELECT count(*) FROM marketplace.order_cancellation_shipping WHERE seller_order_id=:id",Map.of("id",seller),Integer.class)!=1) { return; } }
  boolean canceled=jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE order_id=:id AND state<>'REFUNDED'",Map.of("id",id),Integer.class)==0;String target=canceled?"CANCELED":"CLOSED";
  var payments=jdbc.queryForList("SELECT DISTINCT payment_id FROM marketplace.order_cancellation_settlement WHERE order_id=:id",Map.of("id",id),UUID.class);if(payments.size()>1) { throw new OrderRejected(409); }
  jdbc.update("INSERT INTO marketplace.order_aftercare_closure(order_id,target,reason,payment_id) VALUES(:id,:target,:reason,:payment)",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("target",target).addValue("reason",canceled?"ALL_UNSHIPPED_CANCEL":payments.isEmpty()?"ALL_CONFIRMED":"MIXED_CONFIRMED_CANCEL").addValue("payment",payments.isEmpty()?null:payments.getFirst()));orders.transition(number,target);
 }
 @Override public boolean customerCancellationSettled(UUID order,UUID payment) { AftercareLifecycle.transaction();return jdbc.queryForObject("SELECT count(*) FROM marketplace.order_aftercare_closure WHERE order_id=:order AND payment_id=:payment AND target='CANCELED' AND reason='ALL_UNSHIPPED_CANCEL'",Map.of("order",order,"payment",payment),Integer.class)==1; }
}
