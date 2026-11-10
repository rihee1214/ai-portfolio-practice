package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.Jobs;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Exact immutable delivery proof. The caller must first obtain this job from the actual Jobs port. */
final class ExchangeRefundRecoveryDeliveries {
 enum Stage { ITEM_EFFECT, SHIPPING_RESULT, SELLER_REEVALUATE }
 record Delivery(UUID id,UUID runId,Stage stage,UUID orderId,UUID sellerOrderId,UUID exchangeId,UUID attemptId,Long resultVersion,
                 UUID sourceFactId,String fingerprint,UUID paymentId,Long amount,String originalReference,String resultState,String reference,Instant resultAt,
                 UUID jobId,String type,String key,String cause,String payload,Instant scheduledAt) { }
 record ItemFact(UUID exchangeId,UUID orderId,UUID sellerOrderId,UUID consentId,UUID assessmentId,UUID attemptId,long version,
                 UUID paymentId,long amount,String originalReference,String state,String reference,Instant resultAt,String fingerprint) { }
 record ReadyFact(UUID exchangeId,UUID orderId,UUID sellerOrderId,UUID attemptId,long version,UUID assessmentId,String fingerprint) { }
 private ExchangeRefundRecoveryDeliveries() { }
 static ItemFact item(NamedParameterJdbcTemplate jdbc,UUID id,UUID attempt,long version) {
  var rows=jdbc.queryForList("SELECT c.id AS consent_id,c.exchange_id,c.assessment_id,a.order_id,a.seller_order_id,a.refund,a.quote_fingerprint,x.result_state,x.result_reference,x.result_at,x.fingerprint AS retained_fingerprint,fb.payment_id FROM marketplace.claim_exchange_refund_consent c JOIN marketplace.claim_fee_assessment a ON a.id=c.assessment_id AND a.intent_id=c.exchange_id JOIN marketplace.claim_fee_binding_history h ON h.assessment_id=a.id AND h.attempt_id=:attempt JOIN marketplace.claim_exchange_refund_result x ON x.intent_id=c.exchange_id AND x.assessment_id=a.id AND x.attempt_id=h.attempt_id AND x.result_version=:version JOIN marketplace.claim_financial_refund_binding fb ON fb.attempt_id=x.attempt_id AND fb.intent_id=c.exchange_id AND fb.intent_kind='EXCHANGE_REFUND' AND fb.purpose='ITEM' WHERE c.exchange_id=:id",Map.of("id",id,"attempt",attempt,"version",version));
  if(rows.size()!=1) { throw new OrderRejected(409); }var r=rows.getFirst();UUID consent=(UUID)r.get("consent_id"),assessment=(UUID)r.get("assessment_id");
  String fingerprint=ReturnFeeQuoting.hash("ITEM_EFFECT|"+id+"|"+consent+"|"+assessment+"|"+attempt+"|"+version+"|"+r.get("retained_fingerprint"));
  return new ItemFact(id,(UUID)r.get("order_id"),(UUID)r.get("seller_order_id"),consent,assessment,attempt,version,(UUID)r.get("payment_id"),((Number)r.get("refund")).longValue(),r.get("quote_fingerprint").toString(),r.get("result_state").toString(),(String)r.get("result_reference"),instant(r.get("result_at")),fingerprint);
 }
 static ReadyFact ready(NamedParameterJdbcTemplate jdbc,UUID id) {
  var rows=jdbc.queryForList("SELECT * FROM marketplace.claim_exchange_refund_item_ready WHERE exchange_id=:id",Map.of("id",id));if(rows.size()!=1) { throw new OrderRejected(409); }var r=rows.getFirst();UUID attempt=(UUID)r.get("attempt_id"),assessment=(UUID)r.get("assessment_id");long version=((Number)r.get("result_version")).longValue();
  return new ReadyFact(id,(UUID)r.get("order_id"),(UUID)r.get("seller_order_id"),attempt,version,assessment,ReturnFeeQuoting.hash("SELLER_REEVALUATE|"+id+"|"+attempt+"|"+version+"|"+assessment));
 }
 static String shippingFingerprint(UUID id,UUID attempt,long version,long amount,String originalReference,String state) { return ReturnFeeQuoting.hash("SHIPPING_RESULT|"+id+"|"+attempt+"|"+version+"|"+amount+"|"+originalReference+"|"+state); }
 static Delivery require(NamedParameterJdbcTemplate jdbc,Jobs.Job job,Stage expected) {
  if(job==null||job.id()==null||!job.status().equals("RUNNING")||job.ownerToken()==null) { throw new OrderRejected(409); }
  var rows=jdbc.queryForList("SELECT d.* FROM marketplace.claim_exchange_refund_recovery_delivery d JOIN marketplace.claim_exchange_refund_recovery_run r ON r.id=d.run_id AND r.scan_version='P09-E-REFUND-RECOVERY-1' JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=d.page_id AND p.run_id=d.run_id AND p.stage=d.stage WHERE d.job_id=:job AND d.stage=:stage AND jsonb_exists(r.stages,d.stage)",Map.of("job",job.id(),"stage",expected.name()));if(rows.size()!=1) { throw new OrderRejected(409); }var r=rows.getFirst();
  Delivery d;try { d=new Delivery((UUID)r.get("id"),(UUID)r.get("run_id"),Stage.valueOf(r.get("stage").toString()),(UUID)r.get("order_id"),(UUID)r.get("seller_order_id"),(UUID)r.get("exchange_id"),(UUID)r.get("attempt_id"),number(r.get("result_version")),(UUID)r.get("source_fact_id"),r.get("source_fingerprint").toString(),(UUID)r.get("payment_id"),number(r.get("amount")),(String)r.get("original_reference"),(String)r.get("result_state"),(String)r.get("result_reference"),instant(r.get("result_at")),(UUID)r.get("job_id"),r.get("type").toString(),r.get("dedupe_key").toString(),r.get("cause_key").toString(),r.get("payload").toString(),instant(r.get("scheduled_at"))); }
  catch(IllegalArgumentException|NullPointerException invalid) { throw new OrderRejected(409); }
  var c=job.command();if(d.stage()!=expected||!job.id().equals(d.jobId())||!d.key().equals("e-refund-recovery:"+d.id())||!c.type().equals(d.type())||!c.dedupeKey().equals(d.key())||!c.causeKey().equals(d.cause())||!c.payload().equals(d.payload())||!c.nextRunAt().equals(d.scheduledAt())) { throw new OrderRejected(409); }
  if(expected==Stage.ITEM_EFFECT) {
   if(d.attemptId()==null||d.resultVersion()==null||d.amount()==null||d.paymentId()==null||!c.type().equals(ExchangeRefundFinancialWorker.TYPE)||!c.causeKey().equals(d.exchangeId().toString())||!c.payload().equals(d.exchangeId()+"|"+d.attemptId()+"|"+d.resultVersion())) { throw new OrderRejected(409); }
   var source=item(jdbc,d.exchangeId(),d.attemptId(),d.resultVersion());if(!source.orderId().equals(d.orderId())||!source.sellerOrderId().equals(d.sellerOrderId())||!source.consentId().equals(d.sourceFactId())||!source.fingerprint().equals(d.fingerprint())||!source.paymentId().equals(d.paymentId())||source.amount()!=d.amount()||!source.originalReference().equals(d.originalReference())||!source.state().equals(d.resultState())||!Objects.equals(source.reference(),d.reference())||!Objects.equals(source.resultAt(),d.resultAt())) { throw new OrderRejected(409); }
  }else if(expected==Stage.SELLER_REEVALUATE) {
   var source=ready(jdbc,d.exchangeId());if(!source.orderId().equals(d.orderId())||!source.sellerOrderId().equals(d.sellerOrderId())||!d.exchangeId().equals(d.sourceFactId())||!source.fingerprint().equals(d.fingerprint())||!c.type().equals(SellerAftercareWorker.TYPE)||!c.causeKey().equals(d.sellerOrderId().toString())||!c.payload().equals("EXCHANGE_REFUND_READY|"+d.exchangeId())||d.attemptId()!=null||d.resultVersion()!=null||d.amount()!=null||d.paymentId()!=null) { throw new OrderRejected(409); }
  }else {
   if(d.attemptId()==null||d.resultVersion()==null||d.amount()==null||d.paymentId()==null||!d.exchangeId().equals(d.sourceFactId())||!c.type().equals("REFUND_RESULT_RECORDED.v1")||!c.causeKey().equals(d.attemptId().toString())||!c.payload().equals(d.attemptId()+"|"+d.resultVersion())) { throw new OrderRejected(409); }
   var bindings=jdbc.queryForList("SELECT * FROM marketplace.claim_exchange_refund_shipping_binding WHERE attempt_id=:attempt",Map.of("attempt",d.attemptId()));if(bindings.size()!=1) { throw new OrderRejected(409); }var b=bindings.getFirst();if(!b.get("intent_id").equals(d.exchangeId())||!b.get("order_id").equals(d.orderId())||!b.get("seller_order_id").equals(d.sellerOrderId())||!b.get("payment_id").equals(d.paymentId())||((Number)b.get("amount")).longValue()!=d.amount()||!b.get("original_reference").equals(d.originalReference())||!shippingFingerprint(d.exchangeId(),d.attemptId(),d.resultVersion(),d.amount(),d.originalReference(),d.resultState()).equals(d.fingerprint())) { throw new OrderRejected(409); }
  }
  return d;
 }
 static void financialMatches(Delivery d,Refunds.Attempt actual) {
  if(d==null) { return; }
  if(!actual.id().equals(d.attemptId())||!actual.claimId().equals(d.exchangeId())||!actual.orderId().equals(d.orderId())||!actual.sellerOrderId().equals(d.sellerOrderId())||!actual.paymentId().equals(d.paymentId())||d.resultVersion()==null||d.amount()==null||actual.version()!=d.resultVersion()||actual.amount()!=d.amount()||!actual.state().equals(d.resultState())||!Objects.equals(actual.resultReference(),d.reference())||!Objects.equals(actual.resultAt(),d.resultAt())) { throw new OrderRejected(409); }
 }
 private static Long number(Object value) { return value==null?null:((Number)value).longValue(); }
 private static Instant instant(Object value) { return value==null?null:((Timestamp)value).toInstant(); }
}
