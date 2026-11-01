package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.RefundProvider;
import com.example.marketplace.order.api.OrderRejected;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class MockRefundProvider implements RefundProvider {
 private final NamedParameterJdbcTemplate jdbc;private final TransactionTemplate tx;
 public MockRefundProvider(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;this.tx=new TransactionTemplate(manager); }
 private void outside(Request r) { if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("REFUND_PROVIDER_REQUIRES_OUTSIDE_TRANSACTION"); }if(!r.mode().equals("DEMO")||r.amount()<=0) { throw new OrderRejected(409); } }
 @Override public Outcome submit(Request request) {
  outside(request);var outcome=tx.execute(t->{
   var a=jdbc.queryForMap("SELECT claim_id,purpose FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",request.refundAttemptId()));var settings=jdbc.queryForList("SELECT behavior FROM marketplace.payment_refund_mock_setting WHERE claim_id=:c AND purpose=:p",Map.of("c",a.get("claim_id"),"p",a.get("purpose")),String.class);String behavior=settings.isEmpty()?"SUCCESS":settings.getFirst();
   String state=behavior.equals("FAILED")?"FAILED":behavior.equals("UNKNOWN")?"UNKNOWN":"SUCCEEDED";
   jdbc.update("INSERT INTO marketplace.payment_refund_mock(attempt_id,amount,idempotency_key,behavior,state,reference,effects) VALUES(:id,:amount,:key,:behavior,:state,:ref,:effects) ON CONFLICT DO NOTHING",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",request.refundAttemptId()).addValue("amount",request.amount()).addValue("key",request.idempotencyKey()).addValue("behavior",behavior).addValue("state",state).addValue("ref",state.equals("SUCCEEDED")?"DEMO-REFUND-"+request.refundAttemptId():null).addValue("effects",state.equals("SUCCEEDED")?1:0));
   jdbc.update("UPDATE marketplace.payment_refund_mock SET submit_calls=submit_calls+1 WHERE attempt_id=:id",Map.of("id",request.refundAttemptId()));return read(request,true);
  });return outcome;
 }
 @Override public Outcome query(Request request) {
  outside(request);return tx.execute(t->{var rows=jdbc.queryForList("SELECT attempt_id FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",request.refundAttemptId()));
   // Absence cannot prove permanent no-effect: a prepared stale worker may still submit later.
   if(rows.isEmpty()) { return new Outcome("UNKNOWN",request.refundAttemptId(),request.amount(),null); }
   jdbc.update("UPDATE marketplace.payment_refund_mock SET query_calls=query_calls+1 WHERE attempt_id=:id",Map.of("id",request.refundAttemptId()));return read(request,false);
  });
 }
 private Outcome read(Request request,boolean submitting) {
  var row=jdbc.queryForMap("SELECT * FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",request.refundAttemptId()));if(((Number)row.get("amount")).longValue()!=request.amount()||!row.get("idempotency_key").equals(request.idempotencyKey())) { throw new OrderRejected(409); }
  String state=submitting&&row.get("behavior").equals("LOST_RESPONSE")?"UNKNOWN":row.get("state").toString();return new Outcome(state,request.refundAttemptId(),request.amount(),state.equals("SUCCEEDED")?(String)row.get("reference"):null);
 }
}
