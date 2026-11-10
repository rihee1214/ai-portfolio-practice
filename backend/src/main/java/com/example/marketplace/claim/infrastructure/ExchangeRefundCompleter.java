package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.domain.AftercareShippingRules;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobLeases;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Current seller evaluation completes ready E conversions; an old partial completion is never rewritten. */
@Service @Profile("db")
public final class ExchangeRefundCompleter {
 private final NamedParameterJdbcTemplate jdbc;private final ExchangeApplications applications;private final FinancialIntents intents;
 private final Refunds refunds;private final OrderUnitHolds holds;private final AftercareSettlements orders;private final JdbcExchanges exchanges;private final Jobs jobs;private final JobLeases leases;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public ExchangeRefundCompleter(NamedParameterJdbcTemplate jdbc,ExchangeApplications applications,FinancialIntents intents,Refunds refunds,OrderUnitHolds holds,AftercareSettlements orders,JdbcExchanges exchanges,Jobs jobs,JobLeases leases) {
  this.jdbc=jdbc;this.applications=applications;this.intents=intents;this.refunds=refunds;this.holds=holds;this.orders=orders;this.exchanges=exchanges;this.jobs=jobs;this.leases=leases;
 }
 public void completeReady(AftercareSettlements.AftercareView view,UUID decision,AftercareShippingRules.Evaluation evaluation,Jobs.Job job) {
  owned(job,view.sellerOrderId());
  var pending=jdbc.queryForList("SELECT r.exchange_id FROM marketplace.claim_exchange_refund_item_ready r LEFT JOIN marketplace.claim_exchange_refund_completion c ON c.exchange_id=r.exchange_id WHERE r.order_id=:order AND r.seller_order_id=:seller AND c.exchange_id IS NULL ORDER BY r.exchange_id::text",Map.of("order",view.orderId(),"seller",view.sellerOrderId()),UUID.class);
  if(pending.isEmpty()) { return; }
  if(evaluation.state()==AftercareShippingRules.State.REFUND_REQUIRED) {
   var obligation=jdbc.queryForList("SELECT claim_id,refund_attempt_id,state FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",Map.of("id",view.sellerOrderId()));if(obligation.size()!=1) { throw new OrderRejected(409); }var row=obligation.getFirst();var recorded=orders.lockShippingSettlement(view.sellerOrderId());if(recorded.isEmpty()) { if(row.get("state").equals("SUCCEEDED")) { throw new OrderRejected(409); }return; }
   var actual=refunds.get((UUID)row.get("refund_attempt_id"));if(!row.get("state").equals("SUCCEEDED")||!actual.state().equals("SUCCEEDED")||!actual.claimId().equals(row.get("claim_id"))||!actual.orderId().equals(view.orderId())||!actual.sellerOrderId().equals(view.sellerOrderId())||!actual.purpose().equals("SHIPPING")||actual.amount()!=view.originalShipping()||!recorded.get().equals(new AftercareSettlements.Shipping(view.orderId(),view.sellerOrderId(),(UUID)row.get("claim_id"),actual.paymentId(),actual.id(),actual.amount(),actual.resultReference()))) { throw new OrderRejected(409); }
  }
  for(UUID id:pending) {
   var app=applications.lockRecorded(id);var intent=intents.lock(id);var row=exchanges.lock(id);var ready=jdbc.queryForMap("SELECT * FROM marketplace.claim_exchange_refund_item_ready WHERE exchange_id=:id",Map.of("id",id));
   if(!intent.kind().equals("EXCHANGE_REFUND")||!app.orderId().equals(view.orderId())||!app.sellerOrderId().equals(view.sellerOrderId())||!app.holdId().equals(intent.holdId())||!app.causeId().equals(intent.causeId())||!row.get("state").equals("REFUND_PENDING")||!app.holdId().equals(ready.get("hold_id"))||!app.causeId().equals(ready.get("cause_id"))) { throw new OrderRejected(409); }
   var actual=refunds.get((UUID)ready.get("attempt_id"));if(!actual.state().equals("SUCCEEDED")||!actual.purpose().equals("ITEM")||!actual.claimId().equals(id)||!actual.orderId().equals(view.orderId())||!actual.sellerOrderId().equals(view.sellerOrderId())||actual.version()!=((Number)ready.get("result_version")).longValue()||!actual.resultReference().equals(ready.get("reference"))) { throw new OrderRejected(409); }
   holds.completeRefund(view.orderId(),app.holdId(),app.causeId(),actual.resultReference());
   orders.recordExchangeRefundCompletion(new AftercareSettlements.ExchangeRefundCompletion(id,(UUID)ready.get("conversion_id"),intent.id(),(UUID)ready.get("id"),decision,view.orderId(),view.sellerOrderId(),app.holdId(),app.causeId(),intent.units(),actual.paymentId(),actual.id(),actual.version(),actual.resultReference()));
   long revision=Math.incrementExact(((Number)row.get("version")).longValue());var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class);String hash=ReturnFeeQuoting.hash(id+"|"+ready+"|"+decision+"|"+revision);
   var p=new MapSqlParameterSource("id",id).addValue("conversion",ready.get("conversion_id")).addValue("ready",ready.get("id")).addValue("decision",decision).addValue("revision",revision).addValue("hash",hash).addValue("at",at).addValue("event",UUID.randomUUID()).addValue("key","refund-complete-"+id).addValue("job",job.id()).addValue("owner",job.ownerToken());
   jdbc.update("UPDATE marketplace.claim_exchange_request SET state='REFUNDED',version=:revision WHERE id=:id",p);jdbc.update("INSERT INTO marketplace.claim_exchange_history(id,exchange_id,action,version,reason,actor,occurred_at) VALUES(:event,:id,'REFUNDED',:revision,'EXCHANGE_REFUND_COMPLETED','RECORDED_WORKER',:at)",p);String receipt=mapper.writeValueAsString(exchanges.progress(id));p.addValue("receipt",receipt);
   jdbc.update("INSERT INTO marketplace.claim_exchange_command(exchange_id,command_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,'REFUNDED',:revision,CAST(:receipt AS jsonb))",p);
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_completion(exchange_id,conversion_id,ready_id,decision_id,workflow_revision,fingerprint,receipt,job_id,owner_token,occurred_at) VALUES(:id,:conversion,:ready,:decision,:revision,:hash,CAST(:receipt AS jsonb),:job,:owner,:at)",p);exchanges.event(id,"refund-completed",at.toInstant());owned(job,view.sellerOrderId());leases.requireOwned(job.id(),job.ownerToken());
  }
 }
 private void owned(Jobs.Job job,UUID seller) {
  if(job==null||job.ownerToken()==null) { throw new OrderRejected(400); }var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));if(!actual.command().equals(job.command())||!actual.status().equals("RUNNING")||!job.ownerToken().equals(actual.ownerToken())||!job.command().type().equals(SellerAftercareWorker.TYPE)||!job.command().causeKey().equals(seller.toString())) { throw new OrderRejected(409); }
 }
}
