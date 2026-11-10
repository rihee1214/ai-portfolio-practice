package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Outbox;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact payment results are retained under EXCHANGE_REFUND before its separate business effect. */
@Service @Profile("db")
public final class ExchangeRefundResults {
 private final NamedParameterJdbcTemplate jdbc;private final FinancialIntents intents;private final Refunds refunds;
 private final Jobs jobs;private final JobLeases leases;private final Outbox outbox;private final TransactionTemplate tx;
 public ExchangeRefundResults(NamedParameterJdbcTemplate jdbc,FinancialIntents intents,Refunds refunds,Jobs jobs,JobLeases leases,Outbox outbox,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.intents=intents;this.refunds=refunds;this.jobs=jobs;this.leases=leases;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
 }
 public JobHandler.Result result(Jobs.Job job,UUID attempt,long version) {
  return tx.execute(t->{
   owned(job,attempt,version);var route=jdbc.queryForList("SELECT intent_id,fee_assessment_id,amount,original_reference FROM marketplace.claim_financial_refund_binding WHERE attempt_id=:id AND intent_kind='EXCHANGE_REFUND' AND purpose='ITEM'",Map.of("id",attempt));if(route.size()!=1) { throw new OrderRejected(409); }var binding=route.getFirst();
   var intent=intents.lock((UUID)binding.get("intent_id"));var actual=refunds.get(attempt);leases.requireOwned(job.id(),job.ownerToken());
   if(!intent.kind().equals("EXCHANGE_REFUND")||!actual.claimId().equals(intent.id())||!actual.orderId().equals(intent.orderId())||!actual.sellerOrderId().equals(intent.sellerOrderId())||!actual.purpose().equals("ITEM")||actual.amount()!=((Number)binding.get("amount")).longValue()||actual.version()<version||!refunds.originalReference(attempt).equals(binding.get("original_reference"))) { throw new OrderRejected(409); }
   var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.claim_exchange_refund_result WHERE attempt_id=:id AND result_version=:version",Map.of("id",attempt,"version",version),String.class);
   if(!prior.isEmpty()) { if(actual.version()==version&&!prior.getFirst().equals(ReturnFeeQuoting.hash(actual.toString()))) { throw new OrderRejected(409); }resume(intent.id(),attempt,version);owned(job,attempt,version);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE; }
   // A newer provider outcome has its own atomically recorded delivery. An old delivery cannot
   // impersonate that new result or reverse a later completed business effect.
   if(actual.version()>version||!List.of("SUCCEEDED","FAILED","UNKNOWN").contains(actual.state())) { leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE; }
   var p=new MapSqlParameterSource("attempt",attempt).addValue("version",version).addValue("intent",intent.id()).addValue("assessment",binding.get("fee_assessment_id")).addValue("order",intent.orderId()).addValue("seller",intent.sellerOrderId()).addValue("amount",actual.amount()).addValue("state",actual.state()).addValue("reference",actual.resultReference()).addValue("at",actual.resultAt()==null?null:Timestamp.from(actual.resultAt())).addValue("hash",ReturnFeeQuoting.hash(actual.toString())).addValue("job",job.id()).addValue("owner",job.ownerToken());
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_result(attempt_id,result_version,intent_id,assessment_id,order_id,seller_order_id,amount,result_state,result_reference,result_at,fingerprint,job_id,owner_token) VALUES(:attempt,:version,:intent,:assessment,:order,:seller,:amount,:state,:reference,:at,:hash,:job,:owner)",p);
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_result_event(attempt_id,result_version,action) VALUES(:attempt,:version,'RESULT_RETAINED')",p);resume(intent.id(),attempt,version);owned(job,attempt,version);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
  });
 }
 private void owned(Jobs.Job job,UUID attempt,long version) {
  var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));
  if(!actual.command().equals(job.command())||!actual.status().equals("RUNNING")||!java.util.Objects.equals(actual.ownerToken(),job.ownerToken())||!job.command().type().equals("REFUND_RESULT_RECORDED.v1")||!job.command().dedupeKey().equals("refund-result:"+attempt+":"+version)||!job.command().causeKey().equals(attempt.toString())||!job.command().payload().equals(attempt+"|"+version)) { throw new OrderRejected(409); }
 }
 private void resume(UUID intent,UUID attempt,long version) {
  String key="exchange-refund-result:"+attempt+":"+version;var at=jdbc.queryForObject("SELECT occurred_at FROM marketplace.claim_exchange_refund_result WHERE attempt_id=:id AND result_version=:version",Map.of("id",attempt,"version",version),Timestamp.class).toInstant();
  outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("EXCHANGE_REFUND_RESUME.v1",key,intent.toString(),intent+"|"+attempt+"|"+version,at)));
 }
}
