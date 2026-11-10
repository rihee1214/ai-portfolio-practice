package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Original S only: this operation neither reapplies ITEM fees nor touches inventory. */
@Service @Profile("db")
public final class ReturnShippingResults {
 private final NamedParameterJdbcTemplate jdbc;private final AftercareSettlements orders;private final FinancialIntents intents;private final Refunds refunds;
 private final Jobs jobs;private final JobLeases leases;private final Outbox outbox;private final TransactionTemplate tx;
 public ReturnShippingResults(NamedParameterJdbcTemplate jdbc,AftercareSettlements orders,FinancialIntents intents,Refunds refunds,Jobs jobs,JobLeases leases,Outbox outbox,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.orders=orders;this.intents=intents;this.refunds=refunds;this.jobs=jobs;this.leases=leases;this.outbox=outbox;tx=new TransactionTemplate(manager);
 }
 private record Recovery(ReturnRecoveryDeliveries.Delivery legacy,ExchangeRefundRecoveryDeliveries.Delivery exchange) { }
 private Recovery stored(Jobs.Job job,UUID attempt,long version) {
  if(job==null||job.ownerToken()==null) { throw new OrderRejected(409); }var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));var c=actual.command();
  if(!actual.status().equals("RUNNING")||!job.ownerToken().equals(actual.ownerToken())||!c.equals(job.command())||!c.type().equals("REFUND_RESULT_RECORDED.v1")||!c.causeKey().equals(attempt.toString())||!c.payload().equals(attempt+"|"+version)) { throw new OrderRejected(409); }
  if(c.dedupeKey().equals("refund-result:"+attempt+":"+version)) { return null; }
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_delivery WHERE job_id=:id",Map.of("id",job.id()),Integer.class)==1){var delivery=ExchangeRefundRecoveryDeliveries.require(jdbc,actual,ExchangeRefundRecoveryDeliveries.Stage.SHIPPING_RESULT);if(!delivery.attemptId().equals(attempt)||delivery.resultVersion()!=version){throw new OrderRejected(409);}return new Recovery(null,delivery);}
  var delivery=ReturnRecoveryDeliveries.require(jdbc,actual,ReturnRecoveryDeliveries.Stage.SHIPPING_RESULT);if(!delivery.attemptId().equals(attempt)||delivery.resultVersion()!=version) { throw new OrderRejected(409); }return new Recovery(delivery,null);
 }
 public JobHandler.Result result(Jobs.Job job,UUID attempt,long version) {
  var recovery=stored(job,attempt,version);
  var locator=jdbc.queryForList("SELECT intent_id,seller_order_id FROM marketplace.claim_return_shipping_binding WHERE attempt_id=:id UNION ALL SELECT intent_id,seller_order_id FROM marketplace.claim_exchange_refund_shipping_binding WHERE attempt_id=:id",Map.of("id",attempt));if(locator.size()!=1) { throw new OrderRejected(409); }var source=locator.getFirst();
  return tx.execute(t->{
   var view=orders.lockAftercare((UUID)source.get("seller_order_id"));var intent=intents.lock((UUID)source.get("intent_id"));var obligation=jdbc.queryForMap("SELECT * FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id FOR UPDATE",Map.of("id",view.sellerOrderId()));var actual=refunds.get(attempt);if(recovery!=null){ReturnRecoveryDeliveries.financialMatches(recovery.legacy(),actual);ExchangeRefundRecoveryDeliveries.financialMatches(recovery.exchange(),actual);}
   if(intent.kind().equals("EXCHANGE_REFUND")&&(!obligation.get("refund_attempt_id").equals(attempt)||actual.version()>version)) { historicalExchange(job,attempt,version,view,intent,obligation,actual);return JobHandler.Result.COMPLETE; }
   if(!java.util.List.of("RETURN","EXCHANGE_REFUND").contains(intent.kind())||recovery!=null&&(intent.kind().equals("RETURN")?recovery.legacy()==null:recovery.exchange()==null)||!intent.orderId().equals(view.orderId())||!intent.sellerOrderId().equals(view.sellerOrderId())||!obligation.get("claim_id").equals(intent.id())||!obligation.get("refund_attempt_id").equals(attempt)||!actual.claimId().equals(intent.id())||!actual.orderId().equals(view.orderId())||!actual.sellerOrderId().equals(view.sellerOrderId())||!actual.purpose().equals("SHIPPING")||actual.amount()!=view.originalShipping()||actual.version()!=version||!refunds.originalReference(attempt).equals(view.quoteFingerprint())||!java.util.List.of("SUCCEEDED","FAILED","UNKNOWN").contains(actual.state())) { throw new OrderRejected(409); }
   String fingerprint=ReturnFeeQuoting.hash(actual.toString());var p=new MapSqlParameterSource("attempt",attempt).addValue("version",version).addValue("seller",view.sellerOrderId()).addValue("intent",intent.id()).addValue("state",actual.state()).addValue("ref",actual.resultReference()).addValue("resultAt",actual.resultAt()==null?null:Timestamp.from(actual.resultAt())).addValue("hash",fingerprint).addValue("job",job.id()).addValue("owner",job.ownerToken());
   var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.claim_shipping_result WHERE attempt_id=:attempt AND result_version=:version",p,String.class);
   if(prior.isEmpty()) {
    var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class);p.addValue("at",at).addValue("operationRevision",Math.incrementExact(((Number)obligation.get("version")).longValue()));
    jdbc.update("INSERT INTO marketplace.claim_shipping_result(attempt_id,result_version,seller_order_id,intent_id,result_state,reference,result_at,fingerprint,job_id,owner_token,occurred_at,workflow_revision) VALUES(:attempt,:version,:seller,:intent,:state,:ref,:resultAt,:hash,:job,:owner,:at,:operationRevision)",p);
    jdbc.update("UPDATE marketplace.claim_shipping_obligation SET state=:state,version=:operationRevision WHERE seller_order_id=:seller",p);
    if(actual.state().equals("SUCCEEDED")) {
     orders.recordShippingSettlement(new AftercareSettlements.Shipping(view.orderId(),view.sellerOrderId(),intent.id(),actual.paymentId(),actual.id(),actual.amount(),actual.resultReference()));
    }
    String key="seller-aftercare:"+view.sellerOrderId()+":shipping-result:"+attempt+":"+version;
    outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command(SellerAftercareWorker.TYPE,key,view.sellerOrderId().toString(),"SHIPPING_RESULT|"+attempt+"|"+version,at.toInstant())));
   }else if(!prior.getFirst().equals(fingerprint)) { throw new OrderRejected(409); }
   if(actual.state().equals("SUCCEEDED")) {
    if(!obligation.get("state").equals("SUCCEEDED")&&!prior.isEmpty()) { throw new OrderRejected(409); }
    var receipt=orders.lockShippingSettlement(view.sellerOrderId()).orElseThrow(()->new OrderRejected(409));
    if(!receipt.equals(new AftercareSettlements.Shipping(view.orderId(),view.sellerOrderId(),intent.id(),actual.paymentId(),actual.id(),actual.amount(),actual.resultReference()))) { throw new OrderRejected(409); }
   }
   stored(job,attempt,version);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
  });
 }
 private void historicalExchange(Jobs.Job job,UUID attempt,long version,AftercareSettlements.AftercareView view,FinancialIntents.Reference intent,Map<String,Object> obligation,Refunds.Attempt old) {
  if(!intent.kind().equals("EXCHANGE_REFUND")||!intent.id().equals(obligation.get("claim_id"))||!intent.orderId().equals(view.orderId())||!intent.sellerOrderId().equals(view.sellerOrderId())||!old.claimId().equals(intent.id())||!old.orderId().equals(view.orderId())||!old.sellerOrderId().equals(view.sellerOrderId())||!old.purpose().equals("SHIPPING")||old.amount()!=view.originalShipping()||old.version()<version||!refunds.originalReference(attempt).equals(view.quoteFingerprint())) { throw new OrderRejected(409); }
  var retained=jdbc.queryForList("SELECT intent_id,seller_order_id,job_id FROM marketplace.claim_shipping_result WHERE attempt_id=:id AND result_version=:version",Map.of("id",attempt,"version",version));
  UUID currentId=(UUID)obligation.get("refund_attempt_id");
  if(retained.isEmpty()) { if(!currentId.equals(attempt)||old.version()<=version) { throw new OrderRejected(409); } }
  else if(retained.size()!=1||!retained.getFirst().get("intent_id").equals(intent.id())||!retained.getFirst().get("seller_order_id").equals(view.sellerOrderId())||!retained.getFirst().get("job_id").equals(job.id())) { throw new OrderRejected(409); }
  if(!currentId.equals(attempt)) {
   if(jdbc.queryForObject("WITH RECURSIVE attempts(id) AS(SELECT CAST(:old AS uuid) UNION SELECT s.attempt_id FROM marketplace.claim_exchange_shipping_support_command s JOIN attempts p ON p.id=s.previous_attempt_id WHERE s.exchange_id=:intent AND s.seller_order_id=:seller AND s.order_id=:order AND s.action='RETRY_FAILED') SELECT count(*) FROM attempts WHERE id=:current",Map.of("old",attempt,"current",currentId,"intent",intent.id(),"seller",view.sellerOrderId(),"order",view.orderId()),Integer.class)!=1) { throw new OrderRejected(409); }
   var current=refunds.get(currentId);if(!current.claimId().equals(intent.id())||!current.orderId().equals(view.orderId())||!current.sellerOrderId().equals(view.sellerOrderId())||!current.paymentId().equals(old.paymentId())||!current.purpose().equals("SHIPPING")||current.amount()!=old.amount()||current.sequence()<=old.sequence()||!refunds.originalReference(current.id()).equals(view.quoteFingerprint())) { throw new OrderRejected(409); }
  }
  stored(job,attempt,version);leases.requireOwned(job.id(),job.ownerToken());
 }

}
