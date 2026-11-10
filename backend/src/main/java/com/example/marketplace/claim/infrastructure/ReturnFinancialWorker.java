package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Outbox;
import java.util.UUID;
import java.util.Map;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Consent-bound financial effects only. Physical completion is a separate operation. */
@Service @Profile("db")
public final class ReturnFinancialWorker {
 public static final String TYPE="RETURN_FINANCE_RESUME.v1";
 private record Route(UUID returnId,UUID attemptId,long version) { String payload() { return returnId+"|"+attemptId+"|"+version; }String key() { return "return-finance-result:"+attemptId+":"+version; } }
 private final NamedParameterJdbcTemplate jdbc;private final ReturnApplications applications;private final FinancialIntents intents;
 private final Refunds refunds;private final ReturnFees fees;private final ReturnCommands commands;private final Jobs jobs;private final JobLeases leases;private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public ReturnFinancialWorker(NamedParameterJdbcTemplate jdbc,ReturnApplications applications,FinancialIntents intents,Refunds refunds,ReturnFees fees,ReturnCommands commands,Jobs jobs,JobLeases leases,Outbox outbox,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.applications=applications;this.intents=intents;this.refunds=refunds;this.fees=fees;this.commands=commands;this.jobs=jobs;this.leases=leases;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
 }
 private Route route(Jobs.Job job) {
  if(job==null||job.id()==null||job.ownerToken()==null||job.command()==null||!TYPE.equals(job.command().type())) { throw new OrderRejected(400); }
  try { var parts=job.command().payload().split("\\|",-1);if(parts.length!=3) { throw new IllegalArgumentException(); }var route=new Route(UUID.fromString(parts[0]),UUID.fromString(parts[1]),Long.parseLong(parts[2]));if(route.version()<1||!route.returnId().toString().equals(job.command().causeKey())||!route.payload().equals(job.command().payload())) { throw new IllegalArgumentException(); }return route; }
  catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
 }
 private ReturnRecoveryDeliveries.Delivery storedJob(Jobs.Job job,Route route) {
  var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));var c=actual.command();
  if(!actual.status().equals("RUNNING")||!job.ownerToken().equals(actual.ownerToken())||!c.equals(job.command())||!TYPE.equals(c.type())||!route.returnId().toString().equals(c.causeKey())||!route.payload().equals(c.payload())) { throw new OrderRejected(409); }
  if(route.key().equals(c.dedupeKey())) { return null; }
  var delivery=ReturnRecoveryDeliveries.require(jdbc,actual,ReturnRecoveryDeliveries.Stage.ITEM_EFFECT);if(!delivery.returnId().equals(route.returnId())||!delivery.attemptId().equals(route.attemptId())||delivery.resultVersion()!=route.version()) { throw new OrderRejected(409); }return delivery;
 }
 private void finalLease(Jobs.Job job,Route route) { storedJob(job,route);leases.requireOwned(job.id(),job.ownerToken()); }
 public JobHandler.Result resume(Jobs.Job job) {
  var route=route(job);var recovery=storedJob(job,route);
  return tx.execute(t->{
   var recorded=applications.lockRecorded(route.returnId());var app=recorded.application();var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",route.returnId()));var intent=intents.lock(route.returnId());
   var consents=jdbc.queryForList("SELECT * FROM marketplace.claim_return_financial_consent WHERE return_id=:id",Map.of("id",route.returnId()));if(consents.size()!=1) { throw new OrderRejected(409); }var consent=consents.getFirst();UUID assessmentId=(UUID)consent.get("assessment_id");
   if(!app.id().equals(intent.id())||!app.orderId().equals(intent.orderId())||!app.sellerOrderId().equals(intent.sellerOrderId())||!app.holdId().equals(intent.holdId())||!app.causeId().equals(intent.causeId())||!intent.kind().equals("RETURN")||!app.orderId().equals(row.get("order_id"))||!app.sellerOrderId().equals(row.get("seller_order_id"))||!app.holdId().equals(row.get("hold_id"))||!app.causeId().equals(row.get("cause_id"))||!intent.units().equals(app.source().units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(java.util.Comparator.comparing((com.example.marketplace.order.api.OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(com.example.marketplace.order.api.OrderConfirmations.Unit::ordinal)).toList())) { throw new OrderRejected(409); }
   PhysicalFeeQuoting.lockAssessment(jdbc,assessmentId);
   var assessment=jdbc.queryForMap("SELECT * FROM marketplace.claim_fee_assessment WHERE id=:id FOR UPDATE",Map.of("id",assessmentId));UUID bound=jdbc.queryForObject("SELECT attempt_id FROM marketplace.claim_fee_binding WHERE assessment_id=:id",Map.of("id",assessmentId),UUID.class);var actual=refunds.get(route.attemptId());ReturnRecoveryDeliveries.financialMatches(recovery,actual);
   if(!route.attemptId().equals(bound)||!assessment.get("intent_id").equals(intent.id())||!assessment.get("order_id").equals(app.orderId())||!assessment.get("seller_order_id").equals(app.sellerOrderId())||!assessment.get("quote_id").equals(consent.get("quote_id"))||!actual.claimId().equals(intent.id())||!actual.orderId().equals(app.orderId())||!actual.sellerOrderId().equals(app.sellerOrderId())||!actual.purpose().equals("ITEM")||actual.amount()!=((Number)assessment.get("refund")).longValue()||actual.version()!=route.version()||!refunds.originalReference(actual.id()).equals(assessment.get("quote_fingerprint"))) { throw new OrderRejected(409); }
   var retained=intents.results(intent.id()).stream().filter(r->r.attemptId().equals(actual.id())&&r.version()==actual.version()).findFirst().orElseThrow(()->new OrderRejected(409));
   if(!retained.assessmentId().equals(assessmentId)||!retained.state().equals(actual.state())||!retained.amount().equals(Long.toString(actual.amount()))||!java.util.Objects.equals(retained.reference(),actual.resultReference())||!java.util.Objects.equals(retained.resultAt(),actual.resultAt())) { throw new OrderRejected(409); }
   String fingerprint=ReturnFeeQuoting.hash(actual+"|"+consent.get("id")+"|"+assessment.get("quote_fingerprint"));
   var prior=jdbc.queryForList("SELECT fingerprint,result_state FROM marketplace.claim_return_financial_result WHERE return_id=:id AND attempt_id=:attempt AND result_version=:version",Map.of("id",intent.id(),"attempt",actual.id(),"version",actual.version()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))||!actual.state().equals(prior.getFirst().get("result_state"))||recorded.holdState()==ReturnApplications.RecordedHoldState.RELEASED) { throw new OrderRejected(409); }finalLease(job,route);return JobHandler.Result.COMPLETE; }
   if(recorded.holdState()!=ReturnApplications.RecordedHoldState.ACTIVE||!recorded.orderState().equals("PAID")||!java.util.List.of("REFUND_PENDING","REVIEW_REQUIRED").contains(row.get("state").toString())) { throw new OrderRejected(409); }
   String state;if(actual.state().equals("SUCCEEDED")) {
    // C's financial port remains compatible; this D wrapper alone owns the mandatory final job fence,
    // after its additional workflow/history/outbox writes and all business locks have completed.
    fees.apply(new ReturnFees.Apply(assessmentId,actual.id(),actual.version(),null,null));state=((Number)assessment.get("additional_due")).longValue()>0?"DUE_PENDING":"REFUND_PENDING";
   }else if(java.util.List.of("FAILED","UNKNOWN").contains(actual.state())) { if(!assessment.get("state").equals("BOUND")) { throw new OrderRejected(409); }state="REVIEW_REQUIRED"; }
   else { throw new OrderRejected(409); }
   long revision=Math.incrementExact(((Number)row.get("version")).longValue());var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();String key="finance-"+actual.id()+"-"+actual.version();
   var p=new MapSqlParameterSource("id",intent.id()).addValue("attempt",actual.id()).addValue("assessment",assessmentId).addValue("resultVersion",actual.version()).addValue("resultState",actual.state()).addValue("state",state).addValue("revision",revision).addValue("reason","ITEM_FINANCIAL_"+actual.state()).addValue("key",key).addValue("hash",fingerprint).addValue("at",Timestamp.from(at)).addValue("job",job.id()).addValue("owner",job.ownerToken());
   jdbc.update("UPDATE marketplace.claim_return_request SET state=:state,version=:revision WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,:state,:revision,'RECORDED_WORKER',:reason,:at)",p.addValue("event",UUID.randomUUID()));
   var progress=commands.progress(jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",intent.id())));p.addValue("receipt",mapper.writeValueAsString(progress));
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,:state,:revision,CAST(:receipt AS jsonb))",p);
   jdbc.update("INSERT INTO marketplace.claim_return_financial_result(return_id,attempt_id,result_version,assessment_id,result_state,workflow_revision,fingerprint,receipt,job_id,owner_token,recorded_at) VALUES(:id,:attempt,:resultVersion,:assessment,:resultState,:revision,:hash,CAST(:receipt AS jsonb),:job,:owner,:at)",p);
   String audit="return-command:"+intent.id()+":"+revision;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(audit.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",audit,intent.id().toString(),intent.id().toString(),at)));
   if(actual.state().equals("SUCCEEDED")) { ReturnItemReady.record(jdbc,outbox,intent.id(),actual.id(),actual.version()); }
   finalLease(job,route);return JobHandler.Result.COMPLETE;
  });
 }
}
