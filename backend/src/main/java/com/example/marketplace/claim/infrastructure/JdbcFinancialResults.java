package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RETURN outcomes remain durable financial evidence; this service never completes a physical return. */
@Service @Profile("db")
public final class JdbcFinancialResults {
 @org.springframework.beans.factory.annotation.Autowired private SupportRefundResults supportResults;
 @org.springframework.beans.factory.annotation.Autowired private ExchangeRefundResults exchangeResults;
 private final NamedParameterJdbcTemplate jdbc;private final FinancialIntents intents;private final Refunds refunds;private final JdbcClaims cancellations;private final ReturnShippingResults shipping;private final JobLeases leases;private final Outbox outbox;private final TransactionTemplate tx;
 public JdbcFinancialResults(NamedParameterJdbcTemplate jdbc,FinancialIntents intents,Refunds refunds,JdbcClaims cancellations,ReturnShippingResults shipping,JobLeases leases,Outbox outbox,PlatformTransactionManager manager) { this.jdbc=jdbc;this.intents=intents;this.refunds=refunds;this.cancellations=cancellations;this.shipping=shipping;this.leases=leases;this.outbox=outbox;this.tx=new TransactionTemplate(manager); }
 public JobHandler.Result result(Jobs.Job job) {
  UUID attempt;long version;try { var parts=job.command().payload().split("\\|",-1);if(parts.length!=2) { throw new IllegalArgumentException(); }attempt=UUID.fromString(parts[0]);version=Long.parseLong(parts[1]);if(version<1||!attempt.toString().equals(job.command().causeKey())) { throw new IllegalArgumentException(); } }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  var bindings=jdbc.queryForList("SELECT intent_id,intent_kind,purpose,fee_assessment_id,amount,original_reference FROM marketplace.claim_financial_refund_binding WHERE attempt_id=:id",Map.of("id",attempt));if(bindings.size()!=1) { throw new OrderRejected(409); }var binding=bindings.getFirst();if(binding.get("intent_kind").equals("SUPPORT")) { return supportResults.record(job,(UUID)binding.get("intent_id"),attempt,version); }if(binding.get("intent_kind").equals("CANCEL")) { return cancellations.result(job); }if(binding.get("intent_kind").equals("EXCHANGE_REFUND")) { return binding.get("purpose").equals("SHIPPING")?shipping.result(job,attempt,version):exchangeResults.result(job,attempt,version); }if(!binding.get("intent_kind").equals("RETURN")) { throw new OrderRejected(409); }if(binding.get("purpose").equals("SHIPPING")) { return shipping.result(job,attempt,version); }
  return tx.execute(t->{var intent=intents.lock((UUID)binding.get("intent_id"));var actual=refunds.get(attempt);leases.requireOwned(job.id(),job.ownerToken());
   if(!intent.kind().equals("RETURN")||!actual.claimId().equals(intent.id())||!actual.orderId().equals(intent.orderId())||!actual.sellerOrderId().equals(intent.sellerOrderId())||!actual.purpose().equals("ITEM")||actual.amount()!=((Number)binding.get("amount")).longValue()||actual.version()<version||!refunds.originalReference(attempt).equals(binding.get("original_reference"))) { throw new OrderRejected(409); }
   if(!java.util.List.of("SUCCEEDED","FAILED","UNKNOWN").contains(actual.state())) { return JobHandler.Result.COMPLETE; }
   String fingerprint=hash(actual.toString());var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.claim_return_refund_result WHERE attempt_id=:id AND result_version=:version",Map.of("id",attempt,"version",actual.version()),String.class);if(!prior.isEmpty()) { if(!prior.getFirst().equals(fingerprint)) { throw new OrderRejected(409); }resumeReturn(intent.id(),actual);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE; }
   var p=new MapSqlParameterSource("attempt",attempt).addValue("version",actual.version()).addValue("intent",intent.id()).addValue("assessment",binding.get("fee_assessment_id")).addValue("order",intent.orderId()).addValue("seller",intent.sellerOrderId()).addValue("amount",actual.amount()).addValue("state",actual.state()).addValue("reference",actual.resultReference()).addValue("at",actual.resultAt()==null?null:Timestamp.from(actual.resultAt())).addValue("hash",fingerprint);
   jdbc.update("INSERT INTO marketplace.claim_return_refund_result(attempt_id,result_version,intent_id,assessment_id,order_id,seller_order_id,amount,result_state,result_reference,result_at,fingerprint) VALUES(:attempt,:version,:intent,:assessment,:order,:seller,:amount,:state,:reference,:at,:hash)",p);
   jdbc.update("INSERT INTO marketplace.claim_return_result_event(attempt_id,result_version,action) VALUES(:attempt,:version,'RESULT_RETAINED')",p);String key="return-result-retained:"+attempt+":"+actual.version();var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("CLAIM_RECORDED.v1",key,intent.id().toString(),intent.id().toString(),at)));resumeReturn(intent.id(),actual);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
  });
 }
 private void resumeReturn(UUID intent,Refunds.Attempt actual) {
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_financial_consent WHERE return_id=:id",Map.of("id",intent),Integer.class)==0) { return; }
  var at=jdbc.queryForObject("SELECT occurred_at FROM marketplace.claim_return_refund_result WHERE attempt_id=:id AND result_version=:version",Map.of("id",actual.id(),"version",actual.version()),Timestamp.class).toInstant();String key="return-finance-result:"+actual.id()+":"+actual.version();
  outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command(ReturnFinancialWorker.TYPE,key,intent.toString(),intent+"|"+actual.id()+"|"+actual.version(),at)));
 }
 private static String hash(String text) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
