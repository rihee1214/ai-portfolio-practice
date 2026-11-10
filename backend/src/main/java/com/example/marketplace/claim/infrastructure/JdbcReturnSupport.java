package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnSupport;
import com.example.marketplace.claim.api.ClaimSupport;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.Jobs;
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

/** Support never constructs buyer authority or rewrites the original accepted financial terms. */
@Service @Profile("db")
public final class JdbcReturnSupport implements ReturnSupport {
 private enum Action { RETRY_FAILED, QUERY_UNKNOWN }
 private final NamedParameterJdbcTemplate jdbc;private final CurrentSupport support;private final ReturnApplications applications;private final FinancialIntents intents;
 private final Orders orders;private final Refunds refunds;private final ReturnFees fees;private final ReturnCommands commands;private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();private final ReturnShippingSupport shipping;
 public JdbcReturnSupport(NamedParameterJdbcTemplate jdbc,CurrentSupport support,ReturnApplications applications,FinancialIntents intents,Orders orders,Refunds refunds,ReturnFees fees,ReturnCommands commands,ReturnShippingSupport shipping,Outbox outbox,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.support=support;this.applications=applications;this.intents=intents;this.orders=orders;this.refunds=refunds;this.fees=fees;this.commands=commands;this.shipping=shipping;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
 }
 @Override public ShippingState shipping(String number,OrderQueries.Authority operator,UUID id) { return shipping.status(number,operator,id); }
 @Override public Receipt retryFailed(String number,OrderQueries.Authority operator,UUID id,String purpose,ClaimSupport.Command command) { return perform(number,operator,id,purpose,command,Action.RETRY_FAILED); }
 @Override public Receipt queryRefund(String number,OrderQueries.Authority operator,UUID id,String purpose,ClaimSupport.Command command) { return perform(number,operator,id,purpose,command,Action.QUERY_UNKNOWN); }
 private Receipt perform(String number,OrderQueries.Authority operator,UUID id,String purpose,ClaimSupport.Command command,Action action) {
  if(id==null||purpose==null||!java.util.List.of("ITEM","SHIPPING").contains(purpose)||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9_-]{1,100}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.reason().codePoints().anyMatch(Character::isISOControl)) { throw new OrderRejected(400); }
  if(purpose.equals("SHIPPING")) { return shipping.perform(number,operator,id,command,action==Action.RETRY_FAILED); }
  return tx.execute(t->{
   support.require(operator);var route=jdbc.queryForList("SELECT order_id FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id),UUID.class);if(route.size()!=1) { throw new OrderRejected(404); }var parent=orders.lock(number);if(!parent.id().equals(route.getFirst())) { throw new OrderRejected(404); }
   var recorded=applications.lockRecorded(id);var app=recorded.application();var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",id));var intent=intents.lock(id);
   if(!app.id().equals(intent.id())||!app.orderId().equals(parent.id())||!app.orderId().equals(intent.orderId())||!app.sellerOrderId().equals(intent.sellerOrderId())||!app.holdId().equals(intent.holdId())||!app.causeId().equals(intent.causeId())||!intent.kind().equals("RETURN")||!app.orderId().equals(row.get("order_id"))||!app.sellerOrderId().equals(row.get("seller_order_id"))||!app.holdId().equals(row.get("hold_id"))||!app.causeId().equals(row.get("cause_id"))||!intent.units().equals(app.source().units().stream().map(u->new com.example.marketplace.order.api.OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(java.util.Comparator.comparing((com.example.marketplace.order.api.OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(com.example.marketplace.order.api.OrderConfirmations.Unit::ordinal)).toList())) { throw new OrderRejected(409); }
   String fingerprint=ReturnFeeQuoting.hash(number+"|"+id+"|"+purpose+"|"+action+"|"+command+"|"+operator.member());
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_return_support_command WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }var receipt=mapper.readValue(prior.getFirst().get("receipt").toString(),Receipt.class);if(((Number)row.get("version")).longValue()<receipt.revision()||!id.equals(receipt.returnId())) { throw new OrderRejected(409); }support.require(operator);return receipt; }
   var consents=jdbc.queryForList("SELECT * FROM marketplace.claim_return_financial_consent WHERE return_id=:id",Map.of("id",id));if(consents.size()!=1) { throw new OrderRejected(409); }var consent=consents.getFirst();UUID assessmentId=(UUID)consent.get("assessment_id");
   if(recorded.holdState()!=ReturnApplications.RecordedHoldState.ACTIVE||!recorded.orderState().equals("PAID")||((Number)row.get("version")).longValue()!=command.expectedRevision()||!java.util.List.of("REFUND_PENDING","REVIEW_REQUIRED").contains(row.get("state").toString())||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_command WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()),Integer.class)!=0) { throw new OrderRejected(409); }
   PhysicalFeeQuoting.lockAssessment(jdbc,assessmentId);
   var assessment=jdbc.queryForMap("SELECT * FROM marketplace.claim_fee_assessment WHERE id=:id FOR UPDATE",Map.of("id",assessmentId));UUID previous=jdbc.queryForObject("SELECT attempt_id FROM marketplace.claim_fee_binding WHERE assessment_id=:id",Map.of("id",assessmentId),UUID.class);var actual=refunds.get(previous);
   if(!assessment.get("intent_id").equals(id)||!assessment.get("order_id").equals(app.orderId())||!assessment.get("seller_order_id").equals(app.sellerOrderId())||!assessment.get("quote_id").equals(consent.get("quote_id"))||!assessment.get("state").equals("BOUND")||!actual.claimId().equals(id)||!actual.orderId().equals(app.orderId())||!actual.sellerOrderId().equals(app.sellerOrderId())||!actual.purpose().equals("ITEM")||actual.amount()!=((Number)assessment.get("refund")).longValue()||!refunds.originalReference(actual.id()).equals(assessment.get("quote_fingerprint"))) { throw new OrderRejected(409); }
   UUID cause,next,feeCommand=null,queryCause=null;
   if(action==Action.RETRY_FAILED) {
    if(!actual.state().equals("FAILED")) { throw new OrderRejected(409); }String key=UUID.nameUUIDFromBytes((id+"|"+command.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();var retry=fees.retryFailed(number,operator,assessmentId,new ClaimSupport.Command(key,((Number)assessment.get("version")).longValue(),command.reason()));cause=retry.commandId();feeCommand=cause;next=retry.refundAttemptId();
   }else {
    if(!java.util.List.of("UNKNOWN","PROCESSING").contains(actual.state())) { throw new OrderRejected(409); }cause=UUID.randomUUID();queryCause=cause;next=actual.id();refunds.requestQuery(next,cause,command.reason());
   }
   String state=action==Action.RETRY_FAILED?"REFUND_PENDING":"REVIEW_REQUIRED";long revision=Math.incrementExact(command.expectedRevision());var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();
   var p=new MapSqlParameterSource("id",id).addValue("command",cause).addValue("assessment",assessmentId).addValue("previous",previous).addValue("attempt",next).addValue("purpose",purpose).addValue("action",action.name()).addValue("state",state).addValue("key",command.key()).addValue("hash",fingerprint).addValue("actor",operator.member()).addValue("actorVersion",operator.version()).addValue("reason",command.reason()).addValue("old",command.expectedRevision()).addValue("revision",revision).addValue("resultVersion",actual.version()).addValue("feeCommand",feeCommand).addValue("queryCause",queryCause).addValue("at",Timestamp.from(at));
   jdbc.update("UPDATE marketplace.claim_return_request SET state=:state,version=:revision WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,:state,:revision,:historyActor,:reason,:at)",p.addValue("event",UUID.randomUUID()).addValue("historyActor","SUPPORT:"+operator.member()));
   var progress=commands.progress(jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id)));var receipt=new Receipt(cause,id,next,purpose,action.name(),revision,at,progress);
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,:state,:revision,CAST(:progress AS jsonb))",p.addValue("progress",mapper.writeValueAsString(progress)));
   jdbc.update("INSERT INTO marketplace.claim_return_support_command(id,return_id,assessment_id,previous_attempt_id,attempt_id,purpose,action,request_key,fingerprint,actor_id,actor_version,reason,original_revision,revision,original_result_version,fee_command_id,query_cause_id,receipt,accepted_at) VALUES(:command,:id,:assessment,:previous,:attempt,:purpose,:action,:key,:hash,:actor,:actorVersion,:reason,:old,:revision,:resultVersion,:feeCommand,:queryCause,CAST(:receipt AS jsonb),:at)",p.addValue("receipt",mapper.writeValueAsString(receipt)));
   String key="return-command:"+id+":"+revision;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),at)));
   support.require(operator);return receipt;
  });
 }
}
