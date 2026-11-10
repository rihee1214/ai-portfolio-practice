package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.order.api.ExchangeApplications;
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
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retained EXCHANGE_REFUND ITEM outcome -> fee application/ready; original S and completion are separate. */
@Service @Profile("db")
public final class ExchangeRefundFinancialWorker {
 public static final String TYPE="EXCHANGE_REFUND_RESUME.v1";
 private record Route(UUID exchange,UUID attempt,long version) { String payload() { return exchange+"|"+attempt+"|"+version; }String key() { return "exchange-refund-result:"+attempt+":"+version; } }
 private final NamedParameterJdbcTemplate jdbc;private final JdbcExchanges exchanges;private final ExchangeApplications applications;
 private final FinancialIntents intents;private final Refunds refunds;private final ReturnFees fees;private final Outbox outbox;
 private final Jobs jobs;private final JobLeases leases;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public ExchangeRefundFinancialWorker(NamedParameterJdbcTemplate jdbc,JdbcExchanges exchanges,ExchangeApplications applications,FinancialIntents intents,Refunds refunds,ReturnFees fees,Outbox outbox,Jobs jobs,JobLeases leases,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.exchanges=exchanges;this.applications=applications;this.intents=intents;this.refunds=refunds;this.fees=fees;this.outbox=outbox;this.jobs=jobs;this.leases=leases;this.tx=new TransactionTemplate(manager);
 }
 private Route route(Jobs.Job job) {
  try { var parts=job.command().payload().split("\\|",-1);if(parts.length!=3) { throw new IllegalArgumentException(); }var r=new Route(UUID.fromString(parts[0]),UUID.fromString(parts[1]),Long.parseLong(parts[2]));if(r.version()<1||!r.payload().equals(job.command().payload())||!r.exchange().toString().equals(job.command().causeKey())||!TYPE.equals(job.command().type())) { throw new IllegalArgumentException(); }return r; }
  catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
 }
 private ExchangeRefundRecoveryDeliveries.Delivery owned(Jobs.Job job,Route route) {
  var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));if(!actual.command().equals(job.command())||!actual.status().equals("RUNNING")||!java.util.Objects.equals(actual.ownerToken(),job.ownerToken())||!route.payload().equals(actual.command().payload())) { throw new OrderRejected(409); }
  if(route.key().equals(actual.command().dedupeKey())){return null;}var delivery=ExchangeRefundRecoveryDeliveries.require(jdbc,actual,ExchangeRefundRecoveryDeliveries.Stage.ITEM_EFFECT);if(!delivery.exchangeId().equals(route.exchange())||!delivery.attemptId().equals(route.attempt())||delivery.resultVersion()!=route.version()){throw new OrderRejected(409);}return delivery;
 }
 public JobHandler.Result resume(Jobs.Job job) {
  if(job==null||job.id()==null||job.ownerToken()==null||job.command()==null) { throw new OrderRejected(400); }var route=route(job);
  return tx.execute(t->{
   var recovery=owned(job,route);var app=applications.lockRecorded(route.exchange());var row=exchanges.lock(route.exchange());var intent=intents.lock(route.exchange());
   if(!intent.kind().equals("EXCHANGE_REFUND")||!intent.orderId().equals(app.orderId())||!intent.sellerOrderId().equals(app.sellerOrderId())||!intent.holdId().equals(app.holdId())||!intent.causeId().equals(app.causeId())) { throw new OrderRejected(409); }
   var saved=jdbc.queryForList("SELECT * FROM marketplace.claim_exchange_refund_result WHERE intent_id=:id AND attempt_id=:attempt AND result_version=:version",Map.of("id",route.exchange(),"attempt",route.attempt(),"version",route.version()));if(saved.size()!=1) { throw new OrderRejected(409); }var result=saved.getFirst();
   String fingerprint=ReturnFeeQuoting.hash(route+"|"+result.get("fingerprint"));var prior=jdbc.queryForList("SELECT fingerprint FROM marketplace.claim_exchange_refund_effect WHERE attempt_id=:attempt AND result_version=:version",Map.of("attempt",route.attempt(),"version",route.version()),String.class);
   if(!prior.isEmpty()) { if(prior.size()!=1||!fingerprint.equals(prior.getFirst())) { throw new OrderRejected(409); }owned(job,route);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE; }
   var actual=refunds.get(route.attempt());ExchangeRefundRecoveryDeliveries.financialMatches(recovery,actual);if(!actual.claimId().equals(intent.id())||!actual.orderId().equals(intent.orderId())||!actual.sellerOrderId().equals(intent.sellerOrderId())||!actual.purpose().equals("ITEM")||actual.amount()!=((Number)result.get("amount")).longValue()||actual.version()<route.version()) { throw new OrderRejected(409); }
   UUID assessment=(UUID)result.get("assessment_id");var current=jdbc.queryForList("SELECT b.attempt_id FROM marketplace.claim_fee_binding b JOIN marketplace.claim_fee_assessment a ON a.id=b.assessment_id WHERE a.id=:id AND a.intent_id=:intent",Map.of("id",assessment,"intent",intent.id()),UUID.class);if(current.size()!=1) { throw new OrderRejected(409); }
   String mode;if(!current.getFirst().equals(actual.id())||actual.version()>route.version()||row.get("state").equals("REFUNDED")) { mode="STALE"; }
   else {
    if(!actual.state().equals(result.get("result_state"))||!ReturnFeeQuoting.hash(actual.toString()).equals(result.get("fingerprint"))||!List.of("REFUND_PENDING","REVIEW_REQUIRED","SETTLEMENT_PENDING").contains(row.get("state").toString())) { throw new OrderRejected(409); }
    if(actual.state().equals("SUCCEEDED")) { var applied=fees.apply(new ReturnFees.Apply(assessment,actual.id(),actual.version(),job.id(),job.ownerToken()));mode=applied.additionalDue().equals("0")?"ITEM_READY":"SETTLEMENT_PENDING"; }
    else if(List.of("FAILED","UNKNOWN").contains(actual.state())) { mode="REVIEW_REQUIRED"; }
    else { throw new OrderRejected(409); }
   }
   var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();long original=((Number)row.get("version")).longValue();Long revision=mode.equals("STALE")?null:Math.incrementExact(original);UUID ready=mode.equals("ITEM_READY")?named("exchange-refund-ready:"+intent.id()+":"+assessment+":"+route.version()):null;
   var p=new MapSqlParameterSource("id",intent.id()).addValue("attempt",actual.id()).addValue("result",route.version()).addValue("assessment",assessment).addValue("outcome",result.get("result_state")).addValue("mode",mode).addValue("old",original).addValue("revision",revision).addValue("ready",ready).addValue("hash",fingerprint).addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(at));
   if(revision!=null) {
    String state=mode.equals("ITEM_READY")?"REFUND_PENDING":mode;String reason="EXCHANGE_REFUND_"+result.get("result_state");p.addValue("state",state).addValue("reason",reason).addValue("event",UUID.randomUUID());
    jdbc.update("UPDATE marketplace.claim_exchange_request SET state=:state,version=:revision WHERE id=:id",p);jdbc.update("INSERT INTO marketplace.claim_exchange_history(id,exchange_id,action,version,reason,actor,occurred_at) VALUES(:event,:id,:mode,:revision,:reason,'RECORDED_WORKER',:at)",p);
   }
   String receipt=mapper.writeValueAsString(exchanges.progress(intent.id()));p.addValue("receipt",receipt);
   if(revision!=null) { jdbc.update("INSERT INTO marketplace.claim_exchange_command(exchange_id,command_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,:mode,:revision,CAST(:receipt AS jsonb))",p.addValue("key","refund-result:"+route.attempt()+":"+route.version())); }
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_effect(exchange_id,attempt_id,result_version,assessment_id,result_state,mode,original_revision,workflow_revision,ready_id,fingerprint,receipt,job_id,owner_token,recorded_at) VALUES(:id,:attempt,:result,:assessment,:outcome,:mode,:old,:revision,:ready,:hash,CAST(:receipt AS jsonb),:job,:owner,:at)",p);
   exchanges.event(intent.id(),"refund-result-"+route.attempt()+"-"+route.version(),at);
   if(ready!=null) { ready(app,intent,actual,assessment,revision,ready,job,at); }
   owned(job,route);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
  });
 }
 private void ready(ExchangeApplications.Source app,FinancialIntents.Reference intent,Refunds.Attempt actual,UUID assessment,long revision,UUID ready,Jobs.Job job,Instant at) {
  var facts=exchanges.progress(intent.id()).physical();if(facts.size()!=intent.units().size()||facts.stream().anyMatch(p->!p.collection().equals("RECEIVED")||p.inspectionId()==null||p.responsibility()==null||!List.of("SALEABLE","DEFECTIVE").contains(p.inspection())||p.inspection().equals("SALEABLE")&&p.restitutionId()==null)) { throw new OrderRejected(409); }
  var conversion=jdbc.queryForMap("SELECT id FROM marketplace.claim_exchange_refund_consent WHERE exchange_id=:id AND assessment_id=:assessment",Map.of("id",intent.id(),"assessment",assessment));String snapshot=mapper.writeValueAsString(facts);
  var p=new MapSqlParameterSource("ready",ready).addValue("id",intent.id()).addValue("conversion",conversion.get("id")).addValue("order",app.orderId()).addValue("seller",app.sellerOrderId()).addValue("hold",app.holdId()).addValue("cause",app.causeId()).addValue("payment",actual.paymentId()).addValue("attempt",actual.id()).addValue("result",actual.version()).addValue("assessment",assessment).addValue("amount",actual.amount()).addValue("reference",actual.resultReference()).addValue("revision",revision).addValue("snapshot",snapshot).addValue("hash",ReturnFeeQuoting.hash(snapshot)).addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(at));
  jdbc.update("INSERT INTO marketplace.claim_exchange_refund_item_ready(id,exchange_id,conversion_id,intent_id,order_id,seller_order_id,hold_id,cause_id,payment_id,attempt_id,result_version,assessment_id,amount,reference,workflow_revision,physical_fingerprint,physical_snapshot,job_id,owner_token,occurred_at) VALUES(:ready,:id,:conversion,:id,:order,:seller,:hold,:cause,:payment,:attempt,:result,:assessment,:amount,:reference,:revision,:hash,CAST(:snapshot AS jsonb),:job,:owner,:at)",p);
  for(var fact:facts) { jdbc.update("INSERT INTO marketplace.claim_exchange_refund_ready_unit(exchange_id,item_id,ordinal,physical_id,collection_id,inspection_id,condition,responsibility,restitution_id) VALUES(:id,:item,:ordinal,:physical,:collection,:inspection,:condition,:responsibility,:restitution)",p.addValue("item",fact.itemId()).addValue("ordinal",fact.ordinal()).addValue("physical",fact.physicalId()).addValue("collection",fact.collectionId()).addValue("inspection",fact.inspectionId()).addValue("condition",fact.inspection()).addValue("responsibility",fact.responsibility().name()).addValue("restitution",fact.restitutionId())); }
  String key="seller-aftercare:"+app.sellerOrderId()+":exchange-refund-ready:"+intent.id();outbox.append(new Outbox.Event(named(key),new Jobs.Command(SellerAftercareWorker.TYPE,key,app.sellerOrderId().toString(),"EXCHANGE_REFUND_READY|"+intent.id(),at)));
 }
 private static UUID named(String key) { return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
