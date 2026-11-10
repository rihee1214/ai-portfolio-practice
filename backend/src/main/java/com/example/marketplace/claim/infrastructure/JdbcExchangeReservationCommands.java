package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ExchangeReservationCommands;
import com.example.marketplace.claim.api.Exchanges;
import com.example.marketplace.inventory.api.ExchangeReservations;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Current seller command; the original reservation is not rewritten or silently recreated. */
@Service @Profile("db")
public final class JdbcExchangeReservationCommands implements ExchangeReservationCommands {
 private final NamedParameterJdbcTemplate jdbc;private final JdbcExchanges exchanges;private final ExchangeApplications applications;private final ExchangeReservations stocks;private final TransactionTemplate tx;private final Clock clock;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 @org.springframework.beans.factory.annotation.Autowired public JdbcExchangeReservationCommands(NamedParameterJdbcTemplate jdbc,JdbcExchanges exchanges,ExchangeApplications applications,ExchangeReservations stocks,PlatformTransactionManager manager) { this(jdbc,exchanges,applications,stocks,manager,null); }
 public JdbcExchangeReservationCommands(NamedParameterJdbcTemplate jdbc,JdbcExchanges exchanges,ExchangeApplications applications,ExchangeReservations stocks,PlatformTransactionManager manager,Clock clock) { this.jdbc=jdbc;this.exchanges=exchanges;this.applications=applications;this.stocks=stocks;this.tx=new TransactionTemplate(manager);this.clock=clock; }
 private Instant now() { return clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant().truncatedTo(ChronoUnit.MICROS); }
 @Override public Exchanges.Progress extend(UUID id,OrderQueries.Authority seller,Exchanges.Command command) {
  if(id==null||command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500||command.reason().codePoints().anyMatch(Character::isISOControl)) { throw new OrderRejected(400); }
  return tx.execute(t->{
   applications.lockSeller(seller,id);var row=exchanges.lock(id);String reason=command.reason().strip();String fingerprint=ReturnFeeQuoting.hash("EXTENDED|"+command.expectedRevision()+"|"+reason);
   var prior=jdbc.queryForList("SELECT fingerprint,receipt::text FROM marketplace.claim_exchange_command WHERE exchange_id=:id AND command_key=:key",Map.of("id",id,"key",command.key()));
   if(!prior.isEmpty()) { if(!fingerprint.equals(prior.getFirst().get("fingerprint"))) { throw new OrderRejected(409); }stocks.lockCurrent(id);applications.lockSeller(seller,id);return mapper.readValue(prior.getFirst().get("receipt").toString(),Exchanges.Progress.class); }
   if(!List.of("APPROVED","COLLECTING","READY","REVIEW_REQUIRED","DISPATCH_PENDING").contains(row.get("state").toString())||((Number)row.get("version")).longValue()!=command.expectedRevision()||row.get("expires_at")==null||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_extension WHERE exchange_id=:id",Map.of("id",id),Integer.class)!=0) { throw new OrderRejected(409); }
   var current=stocks.lockCurrent(id);applications.lockSeller(seller,id);Instant at=now(),previous=((Timestamp)row.get("expires_at")).toInstant();
   if(!at.isBefore(previous)||current.isEmpty()||current.stream().anyMatch(r->!r.status().equals("RESERVED")||!r.expiresAt().equals(previous))) { throw new OrderRejected(409); }
   Instant extended=previous.plusSeconds(604800);UUID extension=UUID.randomUUID();long revision=Math.incrementExact(command.expectedRevision());stocks.extend(new ExchangeReservations.Extension(extension,id,current.getFirst().sequence(),previous,extended,at));
   var p=new MapSqlParameterSource("id",id).addValue("extension",extension).addValue("seq",current.getFirst().sequence()).addValue("key",command.key()).addValue("hash",fingerprint).addValue("original",command.expectedRevision()).addValue("revision",revision).addValue("state",row.get("state")).addValue("previous",Timestamp.from(previous)).addValue("extended",Timestamp.from(extended)).addValue("at",Timestamp.from(at)).addValue("actor",seller.member()).addValue("reason",reason);
   jdbc.update("UPDATE marketplace.claim_exchange_request SET expires_at=:extended,version=:revision WHERE id=:id",p);exchanges.history(id,"EXTENDED",revision,reason,seller,at);exchanges.event(id,"reservation-extended",at);var progress=exchanges.progress(id);p.addValue("receipt",mapper.writeValueAsString(progress));
   jdbc.update("INSERT INTO marketplace.claim_exchange_command(exchange_id,command_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,'EXTENDED',:revision,CAST(:receipt AS jsonb))",p);
   jdbc.update("INSERT INTO marketplace.claim_exchange_extension(id,exchange_id,sequence,command_key,fingerprint,original_revision,revision,prior_state,previous_expires_at,extended_expires_at,extended_at,actor_id,reason,receipt) VALUES(:extension,:id,:seq,:key,:hash,:original,:revision,:state,:previous,:extended,:at,:actor,:reason,CAST(:receipt AS jsonb))",p);
   ExchangeReservationFacts.schedule(jdbc,exchanges.outbox(),id,current.getFirst().sequence(),2,extended);
   applications.lockSeller(seller,id);return progress;
  });
 }
 /** Only the one stored extension of this exact pending operation can advance its dispatch revision. */
 static long dispatchRevision(NamedParameterJdbcTemplate jdbc,UUID id,long requestedRevision) {
  var extensions=jdbc.queryForList("SELECT revision FROM marketplace.claim_exchange_extension WHERE exchange_id=:id AND prior_state='DISPATCH_PENDING' AND original_revision=:revision",Map.of("id",id,"revision",requestedRevision),Long.class);return extensions.isEmpty()?requestedRevision:extensions.getFirst();
 }
}
