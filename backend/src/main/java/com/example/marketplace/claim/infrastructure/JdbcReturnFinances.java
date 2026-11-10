package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnFinances;
import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Refunds;
import com.example.marketplace.platform.api.Outbox;
import com.example.marketplace.platform.api.Jobs;
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

/** Final buyer consent coordinates fee and payment reservations in the same parent-locked transaction. */
@Service @Profile("db")
public final class JdbcReturnFinances implements ReturnFinances {
 private final ReturnFees fees;private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;
 private final InspectedReturnQuotes inspected;private final Refunds refunds;private final ReturnCommands commands;
 private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public JdbcReturnFinances(ReturnFees fees,NamedParameterJdbcTemplate jdbc,OrderQueries queries,InspectedReturnQuotes inspected,Refunds refunds,ReturnCommands commands,Outbox outbox,PlatformTransactionManager manager) {
  this.fees=fees;this.jdbc=jdbc;this.queries=queries;this.inspected=inspected;this.refunds=refunds;this.commands=commands;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
 }
 @Override public Quote quote(String number,UUID id,OrderQueries.Authority authority,long expectedRevision) {
  return new Quote(id,expectedRevision,fees.quoteForReturn(number,authority,id,expectedRevision));
 }
 @Override public Accepted consent(String number,UUID id,OrderQueries.Authority authority,Consent command) {
  if(id==null||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.quoteId()==null||command.quoteFingerprint()==null||!command.quoteFingerprint().matches("[0-9a-f]{64}")||!command.consent()) { throw new OrderRejected(400); }
  String fingerprint=ReturnFeeQuoting.hash(id+"|"+command);
  return tx.execute(t->{
   var own=queries.lockOwned(number,authority);var route=jdbc.queryForList("SELECT order_id FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id),UUID.class);if(route.size()!=1||!own.id().equals(route.getFirst())) { throw new OrderRejected(404); }
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_return_financial_consent WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }queries.lockOwned(number,authority);return mapper.readValue(prior.getFirst().get("receipt").toString(),Accepted.class); }
   if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_command WHERE return_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()),Integer.class)!=0) { throw new OrderRejected(409); }
   var source=inspected.lock(number,authority,id,command.expectedRevision());UUID consentId=UUID.randomUUID();String feeKey=UUID.nameUUIDFromBytes((id+"|"+command.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
   var assessment=fees.acceptForReturn(number,authority,id,command.expectedRevision(),command.quoteId(),command.quoteFingerprint(),feeKey,command.legacyConsent());
   var original=refunds.original(own.id());UUID attemptId=UUID.randomUUID();refunds.reserve(new Refunds.Reserve(attemptId,consentId,original.paymentId(),own.id(),source.selection().sellerOrderId(),id,"ITEM",1,Long.parseLong(assessment.refund()),command.quoteFingerprint()));
   fees.bindRefund(number,authority,assessment.id(),attemptId);
   long version=Math.incrementExact(command.expectedRevision());Instant at=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();String reason="BUYER_FINAL_FINANCIAL_CONSENT";
   var p=new MapSqlParameterSource("id",id).addValue("consent",consentId).addValue("key",command.key()).addValue("hash",fingerprint).addValue("quote",command.quoteId()).addValue("assessment",assessment.id()).addValue("attempt",attemptId).addValue("old",command.expectedRevision()).addValue("version",version).addValue("legacy",command.legacyConsent()).addValue("authority",ReturnFeeQuoting.hash(authority.toString())).addValue("actor",authority.member()==null?"GAS":"MEMBER:"+authority.member()).addValue("reason",reason).addValue("at",Timestamp.from(at));
   jdbc.update("UPDATE marketplace.claim_return_request SET state='REFUND_PENDING',version=:version WHERE id=:id",p);
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,'REFUND_PENDING',:version,:actor,:reason,:at)",p.addValue("event",UUID.randomUUID()));
   var progress=commands.progress(jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",id)));var result=new Accepted(progress,assessment.id(),attemptId);
   jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,'REFUND_PENDING',:version,CAST(:progress AS jsonb))",p.addValue("progress",mapper.writeValueAsString(progress)));
   jdbc.update("INSERT INTO marketplace.claim_return_financial_consent(id,return_id,request_key,fingerprint,quote_id,assessment_id,attempt_id,original_revision,revision,explicit_consent,legacy_consent,authority_reference,receipt,accepted_at) VALUES(:consent,:id,:key,:hash,:quote,:assessment,:attempt,:old,:version,true,:legacy,:authority,CAST(:receipt AS jsonb),:at)",p.addValue("receipt",mapper.writeValueAsString(result)));
   String key="return-command:"+id+":"+version;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),at)));
   queries.lockOwned(number,authority);return result;
  });
 }
}
