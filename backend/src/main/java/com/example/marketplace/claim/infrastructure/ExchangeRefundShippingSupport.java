package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ClaimSupport;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.api.ExchangeRefundSupport;
import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.payment.api.Refunds;
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

/** Independent original-S support: never changes the accepted EXCHANGE, ITEM, fee, stock or buyer consent. */
@Service @Profile("db")
public final class ExchangeRefundShippingSupport {
 private final NamedParameterJdbcTemplate jdbc;private final CurrentSupport support;private final Orders orders;private final AftercareSettlements aftercare;
 private final ExchangeApplications applications;private final FinancialIntents intents;private final Refunds refunds;private final JdbcExchanges exchanges;private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public ExchangeRefundShippingSupport(NamedParameterJdbcTemplate jdbc,CurrentSupport support,Orders orders,AftercareSettlements aftercare,ExchangeApplications applications,FinancialIntents intents,Refunds refunds,JdbcExchanges exchanges,Outbox outbox,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.support=support;this.orders=orders;this.aftercare=aftercare;this.applications=applications;this.intents=intents;this.refunds=refunds;this.exchanges=exchanges;this.outbox=outbox;tx=new TransactionTemplate(manager);
 }
 private record Locked(Orders.Snapshot parent,AftercareSettlements.AftercareView view,FinancialIntents.Reference intent,Map<String,Object> workflow,Map<String,Object> obligation) { }
 private Locked lock(String number,UUID id) {
  var routes=jdbc.queryForList("SELECT order_id,seller_order_id FROM marketplace.claim_exchange_request WHERE id=:id",Map.of("id",id));if(routes.size()!=1) { throw new OrderRejected(404); }var route=routes.getFirst();var parent=orders.lock(number);if(!parent.id().equals(route.get("order_id"))) { throw new OrderRejected(404); }
  var view=aftercare.lockAftercare((UUID)route.get("seller_order_id"));var app=applications.lockRecorded(id);var intent=intents.lock(id);var workflow=jdbc.queryForMap("SELECT * FROM marketplace.claim_exchange_request WHERE id=:id FOR UPDATE",Map.of("id",id));
  var canonical=app.units().stream().map(u->new OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(java.util.Comparator.comparing((OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(OrderConfirmations.Unit::ordinal)).toList();
  if(!app.id().equals(intent.id())||!app.orderId().equals(parent.id())||!app.orderId().equals(view.orderId())||!app.sellerOrderId().equals(view.sellerOrderId())||!intent.orderId().equals(app.orderId())||!intent.sellerOrderId().equals(app.sellerOrderId())||!intent.holdId().equals(app.holdId())||!intent.causeId().equals(app.causeId())||!intent.kind().equals("EXCHANGE_REFUND")||!intent.units().equals(canonical)||!java.util.List.of("REFUND_PENDING","REFUNDED").contains(workflow.get("state").toString())) { throw new OrderRejected(409); }
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_item_ready ready JOIN marketplace.claim_exchange_refund_consent c ON c.exchange_id=ready.exchange_id AND c.assessment_id=ready.assessment_id WHERE ready.exchange_id=:id AND ready.order_id=:order AND ready.seller_order_id=:seller",Map.of("id",id,"order",parent.id(),"seller",view.sellerOrderId()),Integer.class)!=1) { throw new OrderRejected(409); }
  var rows=jdbc.queryForList("SELECT * FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:seller FOR UPDATE",Map.of("seller",view.sellerOrderId()));if(rows.size()!=1) { throw new OrderRejected(409); }var obligation=rows.getFirst();if(!obligation.get("claim_id").equals(id)||!obligation.get("order_id").equals(parent.id())||((Number)obligation.get("amount")).longValue()!=view.originalShipping()) { throw new OrderRejected(409); }
  return new Locked(parent,view,intent,workflow,obligation);
 }
 private Refunds.Attempt payment(Locked source) {
  var actual=refunds.get((UUID)source.obligation().get("refund_attempt_id"));
  if(!actual.orderId().equals(source.parent().id())||!actual.sellerOrderId().equals(source.view().sellerOrderId())||!actual.claimId().equals(source.intent().id())||!actual.purpose().equals("SHIPPING")||actual.amount()!=source.view().originalShipping()||!refunds.originalReference(actual.id()).equals(source.view().quoteFingerprint())) { throw new OrderRejected(409); }return actual;
 }
 public ExchangeRefundSupport.ShippingState status(String number,OrderQueries.Authority operator,UUID id) {
  if(id==null) { throw new OrderRejected(400); }return tx.execute(t->{support.require(operator);var source=lock(number,id);var actual=payment(source);var result=new ExchangeRefundSupport.ShippingState(id,source.view().sellerOrderId(),actual.id(),((Number)source.obligation().get("version")).longValue(),source.obligation().get("state").toString(),Long.toString(actual.amount()),actual.sequence(),actual.state());support.require(operator);return result;});
 }
 public ExchangeRefundSupport.Receipt perform(String number,OrderQueries.Authority operator,UUID id,ClaimSupport.Command command,boolean retry) {
  return tx.execute(t->{
   support.require(operator);var source=lock(number,id);var actual=payment(source);String action=retry?"RETRY_FAILED":"QUERY_UNKNOWN";
   String hash=ReturnFeeQuoting.hash(number+"|"+id+"|SHIPPING|"+action+"|"+command+"|"+operator.member());var prior=jdbc.queryForList("SELECT fingerprint,revision,receipt::text FROM marketplace.claim_exchange_shipping_support_command WHERE seller_order_id=:seller AND request_key=:key",Map.of("seller",source.view().sellerOrderId(),"key",command.key()));
   if(!prior.isEmpty()) { if(!hash.equals(prior.getFirst().get("fingerprint"))||((Number)source.obligation().get("version")).longValue()<((Number)prior.getFirst().get("revision")).longValue()) { throw new OrderRejected(409); }var receipt=mapper.readValue(prior.getFirst().get("receipt").toString(),ExchangeRefundSupport.Receipt.class);if(!receipt.exchangeId().equals(id)||!receipt.purpose().equals("SHIPPING")) { throw new OrderRejected(409); }support.require(operator);return receipt; }
   if(!source.parent().state().equals("PAID")||((Number)source.obligation().get("version")).longValue()!=command.expectedRevision()||source.obligation().get("state").equals("SUCCEEDED")||(retry?!actual.state().equals("FAILED"):!java.util.List.of("UNKNOWN","PROCESSING").contains(actual.state()))) { throw new OrderRejected(409); }
   if(!actual.state().equals("PROCESSING")&&jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_result WHERE intent_id=:id AND attempt_id=:attempt AND result_version=:version AND result_state=:state",Map.of("id",id,"attempt",actual.id(),"version",actual.version(),"state",actual.state()),Integer.class)!=1) { throw new OrderRejected(409); }
   UUID cause=UUID.randomUUID(),next=actual.id();long revision=Math.incrementExact(command.expectedRevision());
   if(retry) { next=UUID.randomUUID();refunds.retryFailed(actual.id(),next,cause,command.reason()); }
   else { refunds.requestQuery(actual.id(),cause,command.reason()); }
   var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();var progress=exchanges.progress(id);var receipt=new ExchangeRefundSupport.Receipt(cause,id,next,"SHIPPING",action,revision,at,progress);
   var p=new MapSqlParameterSource("command",cause).addValue("id",id).addValue("seller",source.view().sellerOrderId()).addValue("order",source.parent().id()).addValue("previous",actual.id()).addValue("attempt",next).addValue("state",retry?"RESERVED":"UNKNOWN").addValue("action",action).addValue("key",command.key()).addValue("hash",hash).addValue("actor",operator.member()).addValue("actorVersion",operator.version()).addValue("reason",command.reason()).addValue("old",command.expectedRevision()).addValue("revision",revision).addValue("resultVersion",actual.version()).addValue("query",retry?null:cause).addValue("receipt",mapper.writeValueAsString(receipt)).addValue("at",Timestamp.from(at));
   jdbc.update("UPDATE marketplace.claim_shipping_obligation SET refund_attempt_id=:attempt,state=:state,version=:revision WHERE seller_order_id=:seller",p);
   if(retry) { intents.bindExchangeRefundShipping(id,next); }
   jdbc.update("INSERT INTO marketplace.claim_exchange_shipping_support_command(id,exchange_id,seller_order_id,order_id,previous_attempt_id,attempt_id,action,request_key,fingerprint,actor_id,actor_version,reason,original_revision,revision,original_result_version,query_cause_id,receipt,accepted_at) VALUES(:command,:id,:seller,:order,:previous,:attempt,:action,:key,:hash,:actor,:actorVersion,:reason,:old,:revision,:resultVersion,:query,CAST(:receipt AS jsonb),:at)",p);
   String key="exchange-refund-shipping-support:"+cause;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command("EXCHANGE_RECORDED.v1",key,id.toString(),id.toString(),at)));
   support.require(operator);return receipt;
  });
 }
}
