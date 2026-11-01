package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ClaimSupport;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.payment.api.Refunds;
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

@Service @Profile("db")
public final class JdbcClaimSupport implements ClaimSupport {
 private final NamedParameterJdbcTemplate jdbc;private final CurrentSupport support;private final Orders orders;private final FinancialIntents intents;private final Refunds refunds;private final Outbox outbox;private final TransactionTemplate tx;private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public JdbcClaimSupport(NamedParameterJdbcTemplate jdbc,CurrentSupport support,Orders orders,FinancialIntents intents,Refunds refunds,Outbox outbox,PlatformTransactionManager manager) { this.jdbc=jdbc;this.support=support;this.orders=orders;this.intents=intents;this.refunds=refunds;this.outbox=outbox;this.tx=new TransactionTemplate(manager); }
 private static void validate(UUID claim,String purpose,Command command) {
  if(claim==null||!java.util.List.of("ITEM","SHIPPING").contains(purpose==null?"":purpose)||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9_-]{1,100}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.reason().codePoints().anyMatch(Character::isISOControl)) { throw new OrderRejected(400); }
 }
 @Override public Receipt queryRefund(String number,OrderQueries.Authority operator,UUID claim,String purpose,Command command) {
  return perform(number,operator,claim,purpose,command,false);
 }
 @Override public Receipt retryFailed(String number,OrderQueries.Authority operator,UUID claim,String purpose,Command command) {
  return perform(number,operator,claim,purpose,command,true);
 }
 private Receipt perform(String number,OrderQueries.Authority operator,UUID claim,String purpose,Command command,boolean retry) {
  validate(claim,purpose,command);String action=retry?"RETRY_FAILED":"QUERY";
  return tx.execute(t->{support.require(operator);var order=orders.lock(number);if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim WHERE id=:id AND order_id=:order",Map.of("id",claim,"order",order.id()),Integer.class)!=1) { throw new OrderRejected(404); }var intent=intents.lock(claim);var row=jdbc.queryForMap("SELECT * FROM marketplace.claim WHERE id=:id FOR UPDATE",Map.of("id",claim));String fingerprint=hash(number+"|"+claim+"|"+purpose+"|"+action+"|"+command.key()+"|"+command.expectedRevision()+"|"+command.reason()+"|"+operator.member());
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_support_command WHERE claim_id=:id AND request_key=:key",Map.of("id",claim,"key",command.key()));if(!prior.isEmpty()) { support.require(operator);if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }return mapper.readValue(prior.getFirst().get("receipt").toString(),Receipt.class); }
   if(!intent.kind().equals("CANCEL")||((Number)row.get("version")).longValue()!=command.expectedRevision()||java.util.List.of("COMPLETED","WITHDRAWN").contains(row.get("state"))) { throw new OrderRejected(409); }UUID attempt;if(purpose.equals("ITEM")) { attempt=(UUID)row.get("refund_attempt_id"); }else { var shipping=jdbc.queryForList("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE claim_id=:id AND seller_order_id=:seller",Map.of("id",claim,"seller",intent.sellerOrderId()),UUID.class);if(shipping.size()!=1) { throw new OrderRejected(409); }attempt=shipping.getFirst(); }
   var actual=refunds.get(attempt);if(!actual.claimId().equals(claim)||!actual.orderId().equals(order.id())||!actual.sellerOrderId().equals(intent.sellerOrderId())||!actual.purpose().equals(purpose)||actual.amount()!=((Number)row.get(purpose.equals("ITEM")?"item_amount":"original_shipping")).longValue()||(retry?!actual.state().equals("FAILED"):!java.util.List.of("UNKNOWN","PROCESSING").contains(actual.state()))) { throw new OrderRejected(409); }
   UUID id=UUID.randomUUID(),next=attempt;
   if(retry) { next=UUID.randomUUID();refunds.retryFailed(attempt,next,id,command.reason());intents.bindRefund(claim,next); }
   else { refunds.requestQuery(attempt,id,command.reason()); }
   long revision=Math.incrementExact(((Number)row.get("version")).longValue());var at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();var receipt=new Receipt(id,claim,next,purpose,action,revision,at);
   var p=new MapSqlParameterSource("id",id).addValue("claim",claim).addValue("previous",attempt).addValue("attempt",next).addValue("purpose",purpose).addValue("action",action).addValue("queryCause",retry?null:id).addValue("key",command.key()).addValue("hash",fingerprint).addValue("actor",operator.member()).addValue("reason",command.reason()).addValue("old",command.expectedRevision()).addValue("revision",revision).addValue("receipt",mapper.writeValueAsString(receipt)).addValue("at",Timestamp.from(at));
   jdbc.update("INSERT INTO marketplace.claim_support_command(id,claim_id,previous_attempt_id,attempt_id,purpose,action,query_cause_id,request_key,fingerprint,actor_id,reason,original_revision,revision,receipt,accepted_at) VALUES(:id,:claim,:previous,:attempt,:purpose,:action,:queryCause,:key,:hash,:actor,:reason,:old,:revision,CAST(:receipt AS jsonb),:at)",p);
   if(retry&&purpose.equals("ITEM")) { jdbc.update("UPDATE marketplace.claim SET refund_attempt_id=:attempt,state='APPROVED',version=version+1 WHERE id=:claim",p); }
   else if(retry) { jdbc.update("UPDATE marketplace.claim_shipping_obligation SET refund_attempt_id=:attempt,state='RESERVED' WHERE claim_id=:claim",p);jdbc.update("UPDATE marketplace.claim SET state='SETTLEMENT_PENDING',version=version+1 WHERE id=:claim",p); }
   else { jdbc.update("UPDATE marketplace.claim SET version=version+1 WHERE id=:claim",p); }
   p.addValue("event",retry?"SUPPORT_RETRY_REQUESTED":"SUPPORT_QUERY_REQUESTED");jdbc.update("INSERT INTO marketplace.claim_event(id,claim_id,version,action) VALUES(:id,:claim,:revision,:event)",p);String key=(retry?"claim-support-retry:":"claim-support-query:")+id;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("CLAIM_RECORDED.v1",key,claim.toString(),claim.toString(),at)));support.require(operator);return receipt;
  });
 }
 private static String hash(String text) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
