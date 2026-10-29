package com.example.marketplace.payment.infrastructure;
import com.example.marketplace.payment.api.PaymentProvider;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db") @org.springframework.context.annotation.Fallback
public final class MockPaymentProvider implements PaymentProvider {
 private final NamedParameterJdbcTemplate jdbc;private final TransactionTemplate tx;
 public MockPaymentProvider(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;tx=new TransactionTemplate(manager); }
 private void outside(Request r) { if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("PROVIDER_INSIDE_TRANSACTION"); }if(!"DEMO".equals(r.mode())) { throw new IllegalStateException("NO_SANDBOX_ADAPTER"); } }
 @Override public Result approve(Request r) {
  outside(r);return tx.execute(status->{
   var p=Map.of("id",r.attemptId(),"order",r.orderId());
   jdbc.update("INSERT INTO marketplace.payment_mock(attempt_id,approval,cancellation,state,approval_effects,approval_calls) SELECT :id,coalesce(s.approval,'SUCCESS'),coalesce(s.cancellation,'SUCCESS'),CASE WHEN s.approval='REJECT' THEN 'FAILED' WHEN s.approval IN ('DELAYED_SUCCESS','QUERY_FAILURE') THEN 'UNKNOWN' ELSE 'SUCCEEDED' END,CASE WHEN s.approval IN ('REJECT','DELAYED_SUCCESS','QUERY_FAILURE') THEN 0 ELSE 1 END,1 FROM (SELECT 1) seed LEFT JOIN marketplace.payment_mock_setting s ON s.order_id=:order ON CONFLICT(attempt_id) DO UPDATE SET approval_calls=payment_mock.approval_calls+1",p);
   var row=jdbc.queryForMap("SELECT * FROM marketplace.payment_mock WHERE attempt_id=:id",p);
   return "LOST_RESPONSE".equals(row.get("approval"))?Result.UNKNOWN:Result.valueOf((String)row.get("state"));
  });
 }
 @Override public Result query(Request r) {
  outside(r);return tx.execute(status->{var p=Map.of("id",r.attemptId());var rows=jdbc.queryForList("SELECT * FROM marketplace.payment_mock WHERE attempt_id=:id FOR UPDATE",p);
   // Persisted PROCESSING before a call may have been interrupted. Absence is a confirmed no-approval result for this local adapter.
   if(rows.isEmpty()) { return Result.FAILED; }var row=rows.getFirst();
   if("DELAYED_SUCCESS".equals(row.get("approval"))) { jdbc.update("UPDATE marketplace.payment_mock SET state='SUCCEEDED',approval_effects=1 WHERE attempt_id=:id",p); }
   jdbc.update("UPDATE marketplace.payment_mock SET query_calls=query_calls+1 WHERE attempt_id=:id",p);
   return "QUERY_FAILURE".equals(row.get("approval"))?Result.UNKNOWN:Result.valueOf(jdbc.queryForObject("SELECT state FROM marketplace.payment_mock WHERE attempt_id=:id",p,String.class));
  });
 }
 @Override public Result cancel(Request r) {
  outside(r);return tx.execute(status->{var p=Map.of("id",r.attemptId());var row=jdbc.queryForMap("SELECT * FROM marketplace.payment_mock WHERE attempt_id=:id FOR UPDATE",p);
   String behavior=(String)row.get("cancellation");String next=behavior.equals("REJECT")?"FAILED":behavior.equals("QUERY_FAILURE")?"UNKNOWN":"SUCCEEDED";
   jdbc.update("UPDATE marketplace.payment_mock SET cancel_state=:state,cancel_effects=CASE WHEN :state='SUCCEEDED' THEN 1 ELSE cancel_effects END,cancel_calls=cancel_calls+1 WHERE attempt_id=:id",Map.of("id",r.attemptId(),"state",next));
   return behavior.equals("LOST_RESPONSE")?Result.UNKNOWN:Result.valueOf(next);
  });
 }
 @Override public Result queryCancellation(Request r) { outside(r);String result=jdbc.queryForObject("SELECT cancel_state FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",r.attemptId()),String.class);return "NONE".equals(result)?Result.UNKNOWN:Result.valueOf(result); }
}
