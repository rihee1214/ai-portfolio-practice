package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ExchangeRefunds;
import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.inventory.api.ExchangeReservations;
import com.example.marketplace.payment.api.Refunds;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Quote/consent/fee/budget/stock-release/progress are one exact parent-locked financial operation. */
@Service @Profile("db")
public final class JdbcExchangeRefunds implements ExchangeRefunds {
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final JdbcExchanges exchanges;
 private final InspectedExchangeRefundQuotes inspected;private final ReturnFees fees;private final FinancialIntents intents;
 private final com.example.marketplace.order.api.ExchangeApplications applications;
 private final Refunds refunds;private final ExchangeReservations reservations;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public JdbcExchangeRefunds(NamedParameterJdbcTemplate jdbc,OrderQueries queries,JdbcExchanges exchanges,InspectedExchangeRefundQuotes inspected,ReturnFees fees,FinancialIntents intents,Refunds refunds,ExchangeReservations reservations,PlatformTransactionManager manager,com.example.marketplace.order.api.ExchangeApplications applications) {
  this.jdbc=jdbc;this.queries=queries;this.exchanges=exchanges;this.inspected=inspected;this.fees=fees;this.intents=intents;this.refunds=refunds;this.reservations=reservations;this.tx=new TransactionTemplate(manager);this.applications=applications;
 }
 @Override public Quote quote(String number,UUID id,OrderQueries.Authority authority,long revision) {
  return new Quote(id,revision,fees.quoteForExchangeRefund(number,authority,id,revision));
 }
 @Override public Accepted consent(String number,UUID id,OrderQueries.Authority authority,Consent command) {
  if(id==null||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.quoteId()==null||command.quoteFingerprint()==null||!command.quoteFingerprint().matches("[0-9a-f]{64}")||!command.consent()||command.reason()==null||command.reason().isBlank()||command.reason().length()>500) { throw new OrderRejected(400); }
  String fingerprint=ReturnFeeQuoting.hash(id+"|"+command);
  return tx.execute(t->{
   var own=queries.lockOwned(number,authority);var route=exchanges.source(id);if(!own.id().equals(route.orderId())) { throw new OrderRejected(404); }var row=exchanges.lock(id);
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_exchange_refund_consent WHERE exchange_id=:id AND request_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(prior.size()!=1||!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }applications.lockOwner(number,authority,id);try { jdbc.queryForList("SELECT marketplace.verify_exchange_refund_consent(:id)",Map.of("id",id)); }catch(org.springframework.dao.DataIntegrityViolationException invalid){throw new OrderRejected(409);}queries.lockOwned(number,authority);return mapper.readValue(prior.getFirst().get("receipt").toString(),Accepted.class); }
   if(jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_command WHERE exchange_id=:id AND command_key=:key",Map.of("id",id,"key",command.key()),Integer.class)!=0) { throw new OrderRejected(409); }
   var source=inspected.lock(number,authority,id,command.expectedRevision());UUID consentId=UUID.randomUUID();var intent=intents.registerExchangeRefund(number,authority,id);
   String feeKey=UUID.nameUUIDFromBytes((id+"|EXCHANGE_REFUND|"+command.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
   var assessment=fees.acceptForExchangeRefund(number,authority,id,command.expectedRevision(),command.quoteId(),command.quoteFingerprint(),feeKey,command.legacyConsent());
   var original=refunds.original(own.id());UUID attempt=UUID.randomUUID();refunds.reserve(new Refunds.Reserve(attempt,consentId,original.paymentId(),own.id(),intent.sellerOrderId(),id,"ITEM",1,Long.parseLong(assessment.refund()),command.quoteFingerprint()));
   fees.bindRefund(number,authority,assessment.id(),attempt);
   int sequence=((Number)row.get("current_sequence")).intValue();var stock=sequence==0?reservations.get(id):reservations.lockCurrent(id);if(sequence==0&&!stock.isEmpty()){throw new OrderRejected(409);}var at=ExchangeTimeObservation.read(jdbc,"REFUND_RELEASE",id);UUID release=null;
   if(!stock.isEmpty()) {
    if(stock.stream().allMatch(r->r.status().equals("RESERVED"))) { release=consentId;reservations.release(new ExchangeReservations.Release(release,id,sequence,ExchangeReservations.ReleaseReason.REFUND_CONVERSION,at)); }
    else if(stock.stream().allMatch(r->r.status().equals("EXPIRED"))) { if(reservations.released(id,sequence).reason()!=ExchangeReservations.ReleaseReason.EXPIRED) { throw new OrderRejected(409); } }
    else { throw new OrderRejected(409); }
   } else if(sequence!=0) { throw new OrderRejected(409); }
   long revision=Math.incrementExact(command.expectedRevision());String reason=command.reason().strip();var p=new MapSqlParameterSource("id",id).addValue("consent",consentId).addValue("order",own.id()).addValue("seller",intent.sellerOrderId()).addValue("hold",intent.holdId()).addValue("cause",intent.causeId()).addValue("key",command.key()).addValue("hash",fingerprint).addValue("quote",command.quoteId()).addValue("assessment",assessment.id()).addValue("attempt",attempt).addValue("old",command.expectedRevision()).addValue("revision",revision).addValue("release",release).addValue("sequence",sequence).addValue("inspection",source.inspection().fingerprint()).addValue("at",Timestamp.from(at)).addValue("legacy",command.legacyConsent()).addValue("authority",ReturnFeeQuoting.hash(authority.toString())).addValue("reason",reason);
   jdbc.update("UPDATE marketplace.claim_exchange_request SET state='REFUND_PENDING',version=:revision WHERE id=:id",p);exchanges.history(id,"REFUND_PENDING",revision,reason,authority,at);exchanges.event(id,"refund-consent",at);
   var progress=exchanges.progress(id);var accepted=new Accepted(progress,assessment.id(),attempt);p.addValue("progress",mapper.writeValueAsString(progress)).addValue("receipt",mapper.writeValueAsString(accepted));
   jdbc.update("INSERT INTO marketplace.claim_exchange_command(exchange_id,command_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,'REFUND_PENDING',:revision,CAST(:progress AS jsonb))",p);
   jdbc.update("INSERT INTO marketplace.claim_exchange_refund_consent(id,exchange_id,order_id,seller_order_id,hold_id,cause_id,request_key,fingerprint,quote_id,assessment_id,attempt_id,original_revision,revision,sequence,release_id,inspection_fingerprint,explicit_consent,legacy_consent,authority_reference,reason,receipt,accepted_at) VALUES(:consent,:id,:order,:seller,:hold,:cause,:key,:hash,:quote,:assessment,:attempt,:old,:revision,:sequence,:release,:inspection,true,:legacy,:authority,:reason,CAST(:receipt AS jsonb),:at)",p);
   queries.lockOwned(number,authority);return accepted;
  });
 }
}
