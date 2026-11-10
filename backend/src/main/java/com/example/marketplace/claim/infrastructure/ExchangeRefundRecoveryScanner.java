package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded delivery recovery; candidate transactions never hold a scan cursor lock while waiting on business locks. */
@Service @Profile("db")
public final class ExchangeRefundRecoveryScanner {
 public static final String TYPE="EXCHANGE_REFUND_RECOVERY_SCAN.v1";
 private static final List<ExchangeRefundRecoveryDeliveries.Stage> SUPPORTED=List.of(ExchangeRefundRecoveryDeliveries.Stage.values());
 private final NamedParameterJdbcTemplate jdbc;private final AftercareSettlements aftercare;private final ExchangeApplications applications;private final com.example.marketplace.order.api.OrderUnitHolds holds;
 private final FinancialIntents intents;private final Refunds refunds;private final Jobs jobs;private final JobLeases leases;private final Outbox outbox;private final Environment environment;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public ExchangeRefundRecoveryScanner(NamedParameterJdbcTemplate jdbc,AftercareSettlements aftercare,ExchangeApplications applications,FinancialIntents intents,Refunds refunds,Jobs jobs,JobLeases leases,Outbox outbox,Environment environment,PlatformTransactionManager manager,com.example.marketplace.order.api.OrderUnitHolds holds) {
  this.jdbc=jdbc;this.aftercare=aftercare;this.applications=applications;this.holds=holds;this.intents=intents;this.refunds=refunds;this.jobs=jobs;this.leases=leases;this.outbox=outbox;this.environment=environment;tx=new TransactionTemplate(manager);
 }
 @EventListener(ApplicationReadyEvent.class) public void startup() { if(environment.getProperty("marketplace.worker.enabled",Boolean.class,false)) { start(); } }
 /** Internal trusted scheduler entry: callers cannot select stages, upper bounds or cursors. */
 public UUID start() {
  return tx.execute(t->{UUID run=UUID.randomUUID();var at=now();jdbc.update("INSERT INTO marketplace.claim_exchange_refund_recovery_run(id,scan_version,started_at,stages) VALUES(:id,'P09-E-REFUND-RECOVERY-1',:at,CAST(:stages AS jsonb))",new MapSqlParameterSource("id",run).addValue("at",Timestamp.from(at)).addValue("stages",mapper.writeValueAsString(SUPPORTED)));
   for(var stage:SUPPORTED) { var rows=jdbc.queryForList("SELECT id FROM marketplace.claim_exchange_refund_recovery_ids(:stage) ORDER BY id DESC LIMIT 1",Map.of("stage",stage.name()),UUID.class);UUID upper=rows.isEmpty()?null:rows.getFirst();UUID job=enqueue(new Route(run,stage,"START"),at);jdbc.update("INSERT INTO marketplace.claim_exchange_refund_recovery_stage(run_id,stage,upper_id,first_job_id) VALUES(:run,:stage,:upper,:job)",new MapSqlParameterSource("run",run).addValue("stage",stage.name()).addValue("upper",upper).addValue("job",job)); }return run;
  });
 }
 private record Route(UUID run,ExchangeRefundRecoveryDeliveries.Stage stage,String cursor) {
  String payload() { return run+"|"+stage+"|"+cursor; }String key() { return "exchange-refund-recovery-scan:"+run+":"+stage+":"+cursor; }
  UUID cursorId() { return cursor.equals("START")?null:UUID.fromString(cursor); }
 }
 private record Page(UUID id,Route route,List<UUID> locators) { }
 private record Source(AftercareSettlements.AftercareView view,ExchangeApplications.Source application,FinancialIntents.Reference intent,String holdState) { }
 private Route route(Jobs.Job job) {
  try { if(job==null||job.ownerToken()==null||!TYPE.equals(job.command().type())) { throw new IllegalArgumentException(); }var parts=job.command().payload().split("\\|",-1);if(parts.length!=3) { throw new IllegalArgumentException(); }var r=new Route(UUID.fromString(parts[0]),ExchangeRefundRecoveryDeliveries.Stage.valueOf(parts[1]),parts[2]);r.cursorId();if(!SUPPORTED.contains(r.stage())||!r.payload().equals(job.command().payload())||!r.key().equals(job.command().dedupeKey())||!r.run().toString().equals(job.command().causeKey())) { throw new IllegalArgumentException(); }return r; }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
 }
 private void stored(Jobs.Job job,Route route) {
  var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));if(!actual.status().equals("RUNNING")||!Objects.equals(job.ownerToken(),actual.ownerToken())||!actual.command().equals(job.command())) { throw new OrderRejected(409); }
  var stages=jdbc.queryForList("SELECT first_job_id FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND stage=:stage",Map.of("run",route.run(),"stage",route.stage().name()),UUID.class);if(stages.size()!=1) { throw new OrderRejected(409); }
  if(route.cursor().equals("START")) { if(!stages.getFirst().equals(job.id())) { throw new OrderRejected(409); } }
  else if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_page_complete c JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=c.page_id WHERE p.run_id=:run AND p.stage=:stage AND c.next_cursor=:cursor AND c.next_job_id=:job AND NOT c.finished",Map.of("run",route.run(),"stage",route.stage().name(),"cursor",route.cursorId(),"job",job.id()),Integer.class)!=1) { throw new OrderRejected(409); }
 }
 private void fence(Jobs.Job job,Route route) { stored(job,route);leases.requireOwned(job.id(),job.ownerToken()); }
 public JobHandler.Result scan(Jobs.Job job) {
  var route=route(job);stored(job,route);var page=plan(job,route);
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_page_complete WHERE page_id=:id",Map.of("id",page.id()),Integer.class)>0) { tx.executeWithoutResult(t->fence(job,route));return JobHandler.Result.COMPLETE; }
  for(UUID id:page.locators()) { candidate(job,page,id); }finish(job,page);return JobHandler.Result.COMPLETE;
 }
 private Page plan(Jobs.Job job,Route route) {
  return tx.execute(t->{stored(job,route);var prior=jdbc.queryForList("SELECT id,locator_ids::text,scan_job_id FROM marketplace.claim_exchange_refund_recovery_page WHERE run_id=:run AND stage=:stage AND cursor_key=:cursor",Map.of("run",route.run(),"stage",route.stage().name(),"cursor",route.cursor()));
   if(!prior.isEmpty()) { var p=prior.getFirst();if(!job.id().equals(p.get("scan_job_id"))) { throw new OrderRejected(409); }fence(job,route);return page((UUID)p.get("id"),route,p.get("locator_ids").toString()); }
   var state=jdbc.queryForMap("SELECT * FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND stage=:stage FOR UPDATE",Map.of("run",route.run(),"stage",route.stage().name()));if((Boolean)state.get("completed")||!Objects.equals(state.get("cursor_id"),route.cursorId())) { throw new OrderRejected(409); }
   var p=new MapSqlParameterSource("run",route.run()).addValue("stage",route.stage().name()).addValue("upper",state.get("upper_id")).addValue("cursor",route.cursorId());String ids=jdbc.queryForObject("SELECT marketplace.claim_exchange_refund_recovery_locators(:stage,CAST(:upper AS uuid),CAST(:cursor AS uuid))::text",p,String.class);UUID id=UUID.randomUUID();p.addValue("id",id).addValue("cursorKey",route.cursor()).addValue("ids",ids).addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(now()));
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_recovery_page(id,run_id,stage,cursor_key,locator_ids,scan_job_id,plan_owner_token,planned_at) VALUES(:id,:run,:stage,:cursorKey,CAST(:ids AS jsonb),:job,:owner,:at)",p);fence(job,route);return page(id,route,ids);
  });
 }
 private Page page(UUID id,Route route,String json) { return new Page(id,route,java.util.stream.StreamSupport.stream(mapper.readTree(json).spliterator(),false).map(n->UUID.fromString(n.asText())).toList()); }
 private Source source(UUID id) {
  var rows=jdbc.queryForList("SELECT order_id,seller_order_id FROM marketplace.claim_exchange_request WHERE id=:id",Map.of("id",id));if(rows.size()!=1){throw new OrderRejected(409);}var r=rows.getFirst();var view=aftercare.lockAftercare((UUID)r.get("seller_order_id"));var app=applications.lockRecorded(id);var intent=intents.lock(id);jdbc.queryForMap("SELECT id FROM marketplace.claim_exchange_request WHERE id=:id FOR UPDATE",Map.of("id",id));var hold=holds.inspect(app.orderId(),app.holdId());
  if(!view.orderId().equals(r.get("order_id"))||!app.id().equals(intent.id())||!app.orderId().equals(view.orderId())||!app.sellerOrderId().equals(view.sellerOrderId())||!intent.kind().equals("EXCHANGE_REFUND")||!intent.orderId().equals(view.orderId())||!intent.sellerOrderId().equals(view.sellerOrderId())||!app.holdId().equals(intent.holdId())||!app.causeId().equals(intent.causeId())||!intent.units().equals(app.units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(java.util.Comparator.comparing((com.example.marketplace.order.api.OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(com.example.marketplace.order.api.OrderConfirmations.Unit::ordinal)).toList())){throw new OrderRejected(409);}return new Source(view,app,intent,hold.state());
 }
 private void candidate(Jobs.Job job,Page page,UUID id) {
  tx.executeWithoutResult(t->{stored(job,page.route());var source=source(id);
   if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_candidate WHERE page_id=:page AND exchange_id=:id",Map.of("page",page.id(),"id",id),Integer.class)>0) { fence(job,page.route());return; }
   if(source.holdState().equals("RELEASED")) { candidateReceipt(job,page,id,"SKIP","RELEASED_HOLD",null);fence(job,page.route());return; }
   switch(page.route().stage()) { case ITEM_EFFECT->item(job,page,id,source);case SHIPPING_RESULT->shipping(job,page,id,source);case SELLER_REEVALUATE->seller(job,page,id,source); }fence(job,page.route());
  });
 }
 private void item(Jobs.Job job,Page page,UUID id,Source source) {
  var rows=jdbc.queryForList("SELECT b.attempt_id,x.result_version FROM marketplace.claim_exchange_refund_consent c JOIN marketplace.claim_fee_binding b ON b.assessment_id=c.assessment_id JOIN LATERAL(SELECT result_version FROM marketplace.claim_exchange_refund_result x WHERE x.intent_id=c.exchange_id AND x.assessment_id=c.assessment_id AND x.attempt_id=b.attempt_id ORDER BY result_version DESC LIMIT 1)x ON true WHERE c.exchange_id=:id",Map.of("id",id));
  if(rows.size()!=1) { candidateReceipt(job,page,id,"SKIP","NO_CURRENT_RETAINED_ITEM_RESULT",null);return; }var r=rows.getFirst();UUID attempt=(UUID)r.get("attempt_id");long version=((Number)r.get("result_version")).longValue();
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_effect WHERE exchange_id=:id AND attempt_id=:attempt AND result_version=:version",Map.of("id",id,"attempt",attempt,"version",version),Integer.class)>0) { boolean missingReady=jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_fee_assessment a WHERE a.id=(SELECT assessment_id FROM marketplace.claim_exchange_refund_consent WHERE exchange_id=:id) AND a.state='APPLIED' AND a.additional_due=0 AND NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_item_ready ready WHERE ready.exchange_id=:id)",Map.of("id",id),Integer.class)>0;
   candidateReceipt(job,page,id,missingReady?"INTEGRITY_ERROR":"SKIP",missingReady?"RECORDED_ITEM_EFFECT_MISSING_READY":"ITEM_OPERATION_ALREADY_RECORDED",null);return; }
  if(!source.view().orderState().equals("PAID")||!source.holdState().equals("ACTIVE")) { candidateReceipt(job,page,id,"INTEGRITY_ERROR","MISSING_ITEM_EFFECT_ON_TERMINAL_SOURCE",null);return; }
  var fact=ExchangeRefundRecoveryDeliveries.item(jdbc,id,attempt,version);var actual=refunds.get(attempt);
  if(!actual.claimId().equals(id)||!actual.orderId().equals(fact.orderId())||!actual.sellerOrderId().equals(fact.sellerOrderId())||!actual.paymentId().equals(fact.paymentId())||!actual.purpose().equals("ITEM")||actual.amount()!=fact.amount()||actual.version()!=fact.version()||!actual.state().equals(fact.state())||!Objects.equals(actual.resultReference(),fact.reference())||!Objects.equals(actual.resultAt(),fact.resultAt())||!refunds.originalReference(attempt).equals(fact.originalReference())) { candidateReceipt(job,page,id,"SKIP","CURRENT_ITEM_RESULT_NOT_RETAINED",null);return; }
  deliver(job,page,id,fact.orderId(),fact.sellerOrderId(),fact.consentId(),fact.fingerprint(),actual,fact.originalReference());
 }
 private void shipping(Jobs.Job job,Page page,UUID id,Source source) {
  var rows=jdbc.queryForList("SELECT o.refund_attempt_id FROM marketplace.claim_shipping_obligation o JOIN marketplace.claim_exchange_refund_shipping_binding b ON b.attempt_id=o.refund_attempt_id AND b.intent_id=o.claim_id WHERE o.claim_id=:id AND o.order_id=:order AND o.seller_order_id=:seller FOR UPDATE OF o",Map.of("id",id,"order",source.view().orderId(),"seller",source.view().sellerOrderId()));
  if(rows.size()!=1) { candidateReceipt(job,page,id,"SKIP","NO_CURRENT_TYPED_SHIPPING_OBLIGATION",null);return; }
  UUID attempt=(UUID)rows.getFirst().get("refund_attempt_id");var actual=refunds.get(attempt);String original=refunds.originalReference(attempt);
  if(!actual.claimId().equals(id)||!actual.orderId().equals(source.view().orderId())||!actual.sellerOrderId().equals(source.view().sellerOrderId())||!actual.purpose().equals("SHIPPING")||actual.amount()!=source.view().originalShipping()||!original.equals(source.view().quoteFingerprint())) { throw new OrderRejected(409); }
  if(!List.of("SUCCEEDED","FAILED","UNKNOWN").contains(actual.state())) { candidateReceipt(job,page,id,"SKIP","SHIPPING_RESULT_NOT_AVAILABLE",null);return; }
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_result WHERE attempt_id=:attempt AND result_version=:version",Map.of("attempt",attempt,"version",actual.version()),Integer.class)>0) { candidateReceipt(job,page,id,"SKIP","SHIPPING_RESULT_ALREADY_RECORDED",null);return; }
  deliver(job,page,id,actual.orderId(),actual.sellerOrderId(),id,ExchangeRefundRecoveryDeliveries.shippingFingerprint(id,attempt,actual.version(),actual.amount(),original,actual.state()),actual,original);
 }
 private void seller(Jobs.Job job,Page page,UUID id,Source source) {
  var ready=ExchangeRefundRecoveryDeliveries.ready(jdbc,id);if(!ready.orderId().equals(source.view().orderId())||!ready.sellerOrderId().equals(source.view().sellerOrderId())) { throw new OrderRejected(409); }
  var units=ReturnAftercareEvidence.units(jdbc,source.view());var evaluation=com.example.marketplace.claim.domain.AftercareShippingRules.evaluate(source.view().originalShipping(),units);String fingerprint=ReturnAftercareEvidence.fingerprint(source.view(),units,evaluation);
  var current=jdbc.queryForList("SELECT d.fingerprint FROM marketplace.claim_seller_shipping_current c JOIN marketplace.claim_seller_shipping_decision d ON d.id=c.decision_id WHERE c.seller_order_id=:seller",Map.of("seller",source.view().sellerOrderId()),String.class);
  boolean incomplete=jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_item_ready r LEFT JOIN marketplace.claim_exchange_refund_completion c ON c.exchange_id=r.exchange_id WHERE r.seller_order_id=:seller AND c.exchange_id IS NULL",Map.of("seller",source.view().sellerOrderId()),Integer.class)>0;
  boolean canComplete=evaluation.state()!=com.example.marketplace.claim.domain.AftercareShippingRules.State.REFUND_REQUIRED||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:seller AND state='SUCCEEDED'",Map.of("seller",source.view().sellerOrderId()),Integer.class)==1;
  if(current.size()==1&&current.getFirst().equals(fingerprint)&&(!incomplete||!canComplete)&&!source.view().orderState().equals("PAID")) { candidateReceipt(job,page,id,"SKIP","CURRENT_SELLER_FACTS_ALREADY_EVALUATED",null);return; }
  deliver(job,page,id,ready.orderId(),ready.sellerOrderId(),id,ready.fingerprint(),null,null);
 }
 private void deliver(Jobs.Job job,Page page,UUID id,UUID order,UUID seller,UUID sourceId,String hash,Refunds.Attempt actual,String original) {
  var stage=page.route().stage();UUID delivery=UUID.randomUUID();String key="e-refund-recovery:"+delivery;
  String type=stage==ExchangeRefundRecoveryDeliveries.Stage.ITEM_EFFECT?ExchangeRefundFinancialWorker.TYPE:stage==ExchangeRefundRecoveryDeliveries.Stage.SHIPPING_RESULT?"REFUND_RESULT_RECORDED.v1":SellerAftercareWorker.TYPE;
  String payload=stage==ExchangeRefundRecoveryDeliveries.Stage.ITEM_EFFECT?id+"|"+actual.id()+"|"+actual.version():stage==ExchangeRefundRecoveryDeliveries.Stage.SHIPPING_RESULT?actual.id()+"|"+actual.version():"EXCHANGE_REFUND_READY|"+id;
  String cause=stage==ExchangeRefundRecoveryDeliveries.Stage.ITEM_EFFECT?id.toString():stage==ExchangeRefundRecoveryDeliveries.Stage.SHIPPING_RESULT?actual.id().toString():seller.toString();var at=runAt(page.route().run());UUID target=outbox.append(new Outbox.Event(delivery,new Jobs.Command(type,key,cause,payload,at)));
  var p=new MapSqlParameterSource("id",delivery).addValue("run",page.route().run()).addValue("stage",stage.name()).addValue("page",page.id()).addValue("order",order).addValue("seller",seller).addValue("return",id).addValue("attempt",actual==null?null:actual.id()).addValue("version",actual==null?null:actual.version()).addValue("source",sourceId).addValue("hash",hash).addValue("payment",actual==null?null:actual.paymentId()).addValue("purpose",actual==null?null:actual.purpose()).addValue("amount",actual==null?null:actual.amount()).addValue("reference",original).addValue("state",actual==null?null:actual.state()).addValue("resultReference",actual==null?null:actual.resultReference()).addValue("resultAt",actual==null||actual.resultAt()==null?null:Timestamp.from(actual.resultAt())).addValue("job",target).addValue("type",type).addValue("key",key).addValue("cause",cause).addValue("payload",payload).addValue("scheduled",Timestamp.from(at)).addValue("scan",job.id()).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(now()));
  jdbc.update("INSERT INTO marketplace.claim_exchange_refund_recovery_delivery(id,run_id,stage,page_id,order_id,seller_order_id,exchange_id,attempt_id,result_version,source_fact_id,source_fingerprint,payment_id,purpose,amount,original_reference,result_state,result_reference,result_at,job_id,type,dedupe_key,cause_key,payload,scheduled_at,scan_job_id,scan_owner_token,issued_at) VALUES(:id,:run,:stage,:page,:order,:seller,:return,:attempt,:version,:source,:hash,:payment,:purpose,:amount,:reference,:state,:resultReference,:resultAt,:job,:type,:key,:cause,:payload,:scheduled,:scan,:owner,:at)",p);candidateReceipt(job,page,id,"DELIVERED","EXACT_"+stage+"_SOURCE",delivery);
 }
 private void candidateReceipt(Jobs.Job job,Page page,UUID id,String outcome,String reason,UUID delivery) {
  jdbc.update("INSERT INTO marketplace.claim_exchange_refund_recovery_candidate(page_id,run_id,stage,exchange_id,outcome,reason,delivery_id,scan_job_id,owner_token,recorded_at) VALUES(:page,:run,:stage,:id,:outcome,:reason,:delivery,:job,:owner,:at)",new MapSqlParameterSource("page",page.id()).addValue("run",page.route().run()).addValue("stage",page.route().stage().name()).addValue("id",id).addValue("outcome",outcome).addValue("reason",reason).addValue("delivery",delivery).addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(now())));
 }
 private void finish(Jobs.Job job,Page page) {
  tx.executeWithoutResult(t->{stored(job,page.route());var state=jdbc.queryForMap("SELECT * FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=:run AND stage=:stage FOR UPDATE",Map.of("run",page.route().run(),"stage",page.route().stage().name()));
   if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_page_complete WHERE page_id=:id",Map.of("id",page.id()),Integer.class)>0) { fence(job,page.route());return; }
   if((Boolean)state.get("completed")||!Objects.equals(state.get("cursor_id"),page.route().cursorId())||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_candidate WHERE page_id=:id",Map.of("id",page.id()),Integer.class)!=page.locators().size()) { throw new OrderRejected(409); }
   UUID cursor=page.locators().isEmpty()?page.route().cursorId():page.locators().getLast();boolean complete=page.locators().size()<50;UUID next=complete?null:enqueue(new Route(page.route().run(),page.route().stage(),cursor.toString()),runAt(page.route().run()));
   var p=new MapSqlParameterSource("page",page.id()).addValue("run",page.route().run()).addValue("stage",page.route().stage().name()).addValue("cursor",cursor).addValue("complete",complete).addValue("next",next).addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(now()));
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_recovery_page_complete(page_id,next_cursor,finished,next_job_id,scan_job_id,owner_token,recorded_at) VALUES(:page,:cursor,:complete,:next,:job,:owner,:at)",p);
   jdbc.update("UPDATE marketplace.claim_exchange_refund_recovery_stage SET cursor_id=:cursor,completed=:complete,cursor_page_id=:page WHERE run_id=:run AND stage=:stage",p);fence(job,page.route());
  });
 }
 private UUID enqueue(Route route,Instant at) { return outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(route.key().getBytes(StandardCharsets.UTF_8)),new Jobs.Command(TYPE,route.key(),route.run().toString(),route.payload(),at))); }
 private Instant runAt(UUID run) { return jdbc.queryForObject("SELECT started_at FROM marketplace.claim_exchange_refund_recovery_run WHERE id=:id",Map.of("id",run),Timestamp.class).toInstant(); }
 private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant(); }
}
