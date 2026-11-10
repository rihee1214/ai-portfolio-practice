package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.CancellationOrders;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.ReturnSources;
import com.example.marketplace.payment.api.Refunds;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
public final class JdbcFinancialIntents implements FinancialIntents {
 private final NamedParameterJdbcTemplate jdbc;private final CancellationOrders orders;private final OrderUnitHolds holds;private final Refunds refunds;private final OrderQueries queries;private final ReturnSources returns;
 public JdbcFinancialIntents(NamedParameterJdbcTemplate jdbc,CancellationOrders orders,OrderUnitHolds holds,Refunds refunds,OrderQueries queries,ReturnSources returns) { this.jdbc=jdbc;this.orders=orders;this.holds=holds;this.refunds=refunds;this.queries=queries;this.returns=returns; }
 private static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); } }
 private static List<OrderConfirmations.Unit> canonical(List<OrderConfirmations.Unit> input) {
  if(input==null||input.isEmpty()||input.size()>2000||input.stream().anyMatch(u->u==null||u.itemId()==null||u.ordinal()<0||u.ordinal()>98)||input.stream().distinct().count()!=input.size()) { throw new OrderRejected(400); }
  return input.stream().sorted(java.util.Comparator.comparing((OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(OrderConfirmations.Unit::ordinal)).toList();
 }
 @Override public Reference registerCancellation(UUID id,UUID order,UUID seller,UUID hold,UUID cause,List<OrderConfirmations.Unit> input) {
  return register(id,order,seller,hold,cause,input,"CANCEL");
 }
 private Reference register(UUID id,UUID order,UUID seller,UUID hold,UUID cause,List<OrderConfirmations.Unit> input,String kind) {
  transaction();if(id==null||order==null||seller==null||hold==null||cause==null) { throw new OrderRejected(400); }var units=canonical(input);orders.requireSellerUnits(order,seller,units);var actual=holds.inspect(order,hold);String hash=hash(id+"|"+order+"|"+seller+"|"+hold+"|"+cause+"|"+kind+"|"+units);
  var prior=jdbc.queryForList("SELECT id,fingerprint FROM marketplace.claim_financial_intent WHERE id=:id OR cause_id=:cause FOR UPDATE",Map.of("id",id,"cause",cause));if(!prior.isEmpty()) { if(prior.size()!=1||!id.equals(prior.getFirst().get("id"))||!hash.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }return lock(id); }
  String holdKind=kind.equals("EXCHANGE_REFUND")?"EXCHANGE":kind;
  if(!actual.kind().equals(holdKind)||!actual.state().equals("ACTIVE")||!cause.equals(actual.causeId())||!units.equals(actual.units())) { throw new OrderRejected(409); }
  jdbc.update("INSERT INTO marketplace.claim_financial_intent(id,order_id,seller_order_id,hold_id,cause_id,kind,fingerprint) VALUES(:id,:order,:seller,:hold,:cause,:kind,:hash)",Map.of("id",id,"order",order,"seller",seller,"hold",hold,"cause",cause,"kind",kind,"hash",hash));
  for(var u:units) { jdbc.update("INSERT INTO marketplace.claim_financial_intent_unit(intent_id,hold_id,order_id,seller_order_id,item_id,ordinal) VALUES(:id,:hold,:order,:seller,:item,:ordinal)",Map.of("id",id,"hold",hold,"order",order,"seller",seller,"item",u.itemId(),"ordinal",u.ordinal())); }
  return lock(id);
 }
 @Override public Reference registerReturn(String number,OrderQueries.Authority authority,UUID id,UUID hold,UUID cause,List<OrderConfirmations.Unit> input) {
  transaction();if(id==null||hold==null||cause==null) { throw new OrderRejected(400); }var units=canonical(input);var own=queries.lockOwned(number,authority);orders.lockUnits(own.id(),units);var prior=jdbc.queryForList("SELECT id FROM marketplace.claim_financial_intent WHERE id=:id OR cause_id=:cause",Map.of("id",id,"cause",cause),UUID.class);
  if(!prior.isEmpty()) { if(prior.size()!=1||!id.equals(prior.getFirst())) { throw new OrderRejected(409); }var saved=lock(id);queries.lockOwned(number,authority);if(!saved.orderId().equals(own.id())||!saved.kind().equals("RETURN")||!saved.holdId().equals(hold)||!saved.causeId().equals(cause)||!saved.units().equals(units)) { throw new OrderRejected(409); }return saved; }
  var source=returns.lock(number,authority,units,hold);var result=register(id,source.orderId(),source.sellerOrderId(),hold,cause,units,"RETURN");queries.lockOwned(number,authority);return result;
 }
 @Override public Reference registerExchangeRefund(String number,OrderQueries.Authority authority,UUID id) {
  transaction();if(id==null) { throw new OrderRejected(400); }var own=queries.lockOwned(number,authority);
  var route=jdbc.queryForList("SELECT order_id,seller_order_id,hold_id,cause_id FROM marketplace.claim_exchange_request WHERE id=:id FOR UPDATE",Map.of("id",id));if(route.size()!=1||!own.id().equals(route.getFirst().get("order_id"))) { throw new OrderRejected(404); }var row=route.getFirst();
  var units=jdbc.query("SELECT item_id,ordinal FROM marketplace.claim_exchange_unit WHERE exchange_id=:id ORDER BY item_id::text,ordinal",Map.of("id",id),(r,n)->new OrderConfirmations.Unit(r.getObject(1,UUID.class),r.getInt(2)));
  var result=register(id,own.id(),(UUID)row.get("seller_order_id"),(UUID)row.get("hold_id"),(UUID)row.get("cause_id"),units,"EXCHANGE_REFUND");queries.lockOwned(number,authority);return result;
 }
 @Override public Reference lock(UUID id) {
  transaction();if(id==null) { throw new OrderRejected(400); }var rows=jdbc.queryForList("SELECT order_id FROM marketplace.claim_financial_intent WHERE id=:id",Map.of("id",id),UUID.class);if(rows.size()!=1) { throw new OrderRejected(409); }var units=jdbc.query("SELECT item_id,ordinal FROM marketplace.claim_financial_intent_unit WHERE intent_id=:id ORDER BY item_id::text,ordinal",Map.of("id",id),(r,n)->new OrderConfirmations.Unit(r.getObject(1,UUID.class),r.getInt(2)));orders.lockUnits(rows.getFirst(),units);var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_financial_intent WHERE id=:id FOR UPDATE",Map.of("id",id));return new Reference(id,(UUID)row.get("order_id"),(UUID)row.get("seller_order_id"),(UUID)row.get("hold_id"),(UUID)row.get("cause_id"),row.get("kind").toString(),units);
 }
 @Override public List<Result> results(UUID intentId) {
  var intent=lock(intentId);if(!List.of("RETURN","EXCHANGE_REFUND").contains(intent.kind())) { throw new OrderRejected(409); }
  String table=intent.kind().equals("EXCHANGE_REFUND")?"claim_exchange_refund_result":"claim_return_refund_result";
  return jdbc.query("SELECT attempt_id,result_version,assessment_id,result_state,amount,result_reference,result_at FROM marketplace."+table+" WHERE intent_id=:id ORDER BY occurred_at,attempt_id,result_version",Map.of("id",intentId),(r,n)->new Result(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class),r.getString(4),Long.toString(r.getLong(5)),r.getString(6),r.getTimestamp(7)==null?null:r.getTimestamp(7).toInstant()));
 }
 @Override public void bindRefund(UUID intentId,UUID attemptId) {
  bind(intentId,attemptId,null);
 }
 @Override public void bindReturnRefund(UUID intentId,UUID attemptId,UUID assessmentId) {
  if(assessmentId==null) { throw new OrderRejected(400); }bind(intentId,attemptId,assessmentId);
 }
 @Override public void bindExchangeRefund(UUID intentId,UUID attemptId,UUID assessmentId) {
  if(assessmentId==null||!lock(intentId).kind().equals("EXCHANGE_REFUND")) { throw new OrderRejected(409); }bind(intentId,attemptId,assessmentId);
 }
 @Override public void bindReturnShipping(UUID intentId,UUID attemptId) { bindShipping(intentId,attemptId,"RETURN"); }
 @Override public void bindExchangeRefundShipping(UUID intentId,UUID attemptId) { bindShipping(intentId,attemptId,"EXCHANGE_REFUND"); }
 private void bindShipping(UUID intentId,UUID attemptId,String kind) {
  var intent=lock(intentId);var a=refunds.get(attemptId);
  if(!intent.kind().equals(kind)||!a.purpose().equals("SHIPPING")||!a.claimId().equals(intentId)||!a.orderId().equals(intent.orderId())||!a.sellerOrderId().equals(intent.sellerOrderId())) { throw new OrderRejected(409); }
  String ready=kind.equals("RETURN")?"claim_return_item_ready":"claim_exchange_refund_item_ready",column=kind.equals("RETURN")?"return_id":"exchange_id",table=kind.equals("RETURN")?"claim_return_shipping_binding":"claim_exchange_refund_shipping_binding";
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation o JOIN marketplace."+ready+" r ON r."+column+"=o.claim_id AND r.order_id=o.order_id AND r.seller_order_id=o.seller_order_id WHERE o.seller_order_id=:seller AND o.claim_id=:intent AND o.refund_attempt_id=:attempt AND o.amount=:amount",Map.of("seller",intent.sellerOrderId(),"intent",intentId,"attempt",attemptId,"amount",a.amount()),Integer.class)!=1) { throw new OrderRejected(409); }
  String reference=refunds.originalReference(attemptId);String fingerprint=hash(a.id()+"|"+a.paymentId()+"|"+a.orderId()+"|"+a.sellerOrderId()+"|"+a.claimId()+"|"+a.purpose()+"|"+a.sequence()+"|"+a.amount()+"|"+reference);
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("attempt",attemptId).addValue("intent",intentId).addValue("order",a.orderId()).addValue("seller",a.sellerOrderId()).addValue("payment",a.paymentId()).addValue("amount",a.amount()).addValue("reference",reference).addValue("hash",fingerprint).addValue("kind",kind);
  var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.claim_financial_refund_binding WHERE attempt_id=:attempt",p,String.class);if(!prior.isEmpty()) { if(prior.size()!=1||!prior.getFirst().equals(fingerprint)||jdbc.queryForObject("SELECT count(*) FROM marketplace."+table+" WHERE attempt_id=:attempt AND intent_id=:intent",p,Integer.class)!=1) { throw new OrderRejected(409); }return; }
  jdbc.update("INSERT INTO marketplace.claim_financial_refund_binding(attempt_id,intent_id,order_id,seller_order_id,intent_kind,payment_id,purpose,amount,original_reference,fingerprint) VALUES(:attempt,:intent,:order,:seller,:kind,:payment,'SHIPPING',:amount,:reference,:hash)",p);
  jdbc.update("INSERT INTO marketplace."+table+"(attempt_id,intent_id,seller_order_id,order_id,payment_id,amount,original_reference) VALUES(:attempt,:intent,:seller,:order,:payment,:amount,:reference)",p);
 }
 private void bind(UUID intentId,UUID attemptId,UUID assessmentId) {
  var intent=lock(intentId);var a=refunds.get(attemptId);if(!intent.id().equals(a.claimId())||!intent.orderId().equals(a.orderId())||!intent.sellerOrderId().equals(a.sellerOrderId())||List.of("RETURN","EXCHANGE_REFUND").contains(intent.kind())&&!a.purpose().equals("ITEM")) { throw new OrderRejected(409); }
  if(intent.kind().equals("CANCEL")&&assessmentId!=null||List.of("RETURN","EXCHANGE_REFUND").contains(intent.kind())&&assessmentId==null) { throw new OrderRejected(409); }
  String originalReference=refunds.originalReference(attemptId);String hash=hash(a.id()+"|"+a.paymentId()+"|"+a.orderId()+"|"+a.sellerOrderId()+"|"+a.claimId()+"|"+a.purpose()+"|"+a.sequence()+"|"+a.amount()+"|"+originalReference);var prior=jdbc.queryForList("SELECT fingerprint,fee_assessment_id FROM marketplace.claim_financial_refund_binding WHERE attempt_id=:id",Map.of("id",attemptId));if(!prior.isEmpty()) { if(!hash.equals(prior.getFirst().get("fingerprint"))||!java.util.Objects.equals(assessmentId,prior.getFirst().get("fee_assessment_id"))) { throw new OrderRejected(409); }return; }
  var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("attempt",attemptId).addValue("intent",intentId).addValue("order",a.orderId()).addValue("seller",a.sellerOrderId()).addValue("kind",intent.kind()).addValue("payment",a.paymentId()).addValue("purpose",a.purpose()).addValue("amount",a.amount()).addValue("reference",originalReference).addValue("hash",hash).addValue("assessment",assessmentId);
  jdbc.update("INSERT INTO marketplace.claim_financial_refund_binding(attempt_id,intent_id,order_id,seller_order_id,intent_kind,payment_id,purpose,amount,original_reference,fingerprint,fee_assessment_id) VALUES(:attempt,:intent,:order,:seller,:kind,:payment,:purpose,:amount,:reference,:hash,:assessment)",p);
 }
 private static String hash(String text) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}

