package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.inventory.api.ExchangeReservations;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
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

/** Releases only an expired allocation generation; it never refunds or releases the accepted claim hold. */
@Service @Profile("db")
public final class ExchangeReservationExpiry {
 public static final String TYPE=ExchangeReservationFacts.TYPE;
 private record Route(UUID exchangeId,int sequence,int epoch) { String payload() { return exchangeId+"|"+sequence+"|"+epoch; }String key() { return "exchange-reservation-due:"+exchangeId+":"+sequence+":"+epoch; } }
 private final NamedParameterJdbcTemplate jdbc;private final JdbcExchanges exchanges;private final ExchangeApplications applications;private final ExchangeReservations stocks;private final Jobs jobs;private final JobLeases leases;private final TransactionTemplate tx;private final Clock clock;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 @org.springframework.beans.factory.annotation.Autowired public ExchangeReservationExpiry(NamedParameterJdbcTemplate jdbc,JdbcExchanges exchanges,ExchangeApplications applications,ExchangeReservations stocks,Jobs jobs,JobLeases leases,PlatformTransactionManager manager) { this(jdbc,exchanges,applications,stocks,jobs,leases,manager,null); }
 public ExchangeReservationExpiry(NamedParameterJdbcTemplate jdbc,JdbcExchanges exchanges,ExchangeApplications applications,ExchangeReservations stocks,Jobs jobs,JobLeases leases,PlatformTransactionManager manager,Clock clock) { this.jdbc=jdbc;this.exchanges=exchanges;this.applications=applications;this.stocks=stocks;this.jobs=jobs;this.leases=leases;this.tx=new TransactionTemplate(manager);this.clock=clock; }
 private Instant now() { return clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant().truncatedTo(ChronoUnit.MICROS); }
 private Route route(Jobs.Job job) {
  try { var parts=job.command().payload().split("\\|",-1);if(parts.length!=3) { throw new IllegalArgumentException(); }var result=new Route(UUID.fromString(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));if(result.sequence()<1||result.epoch()<1||result.epoch()>2||!result.payload().equals(job.command().payload())||!result.exchangeId().toString().equals(job.command().causeKey())||!result.key().equals(job.command().dedupeKey())||!TYPE.equals(job.command().type())) { throw new IllegalArgumentException(); }return result; }
  catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
 }
 private void owned(Jobs.Job job,Route route) {
  var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));if(!actual.command().equals(job.command())||!actual.status().equals("RUNNING")||!java.util.Objects.equals(actual.ownerToken(),job.ownerToken())||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_reservation_schedule WHERE exchange_id=:id AND sequence=:seq AND epoch=:epoch AND job_id=:job AND due_at=:due",Map.of("id",route.exchangeId(),"seq",route.sequence(),"epoch",route.epoch(),"job",job.id(),"due",Timestamp.from(job.command().nextRunAt())),Integer.class)!=1) { throw new OrderRejected(409); }
 }
 private void finalLease(Jobs.Job job,Route route) { owned(job,route);leases.requireOwned(job.id(),job.ownerToken()); }
 public JobHandler.Result expire(Jobs.Job job) {
  if(job==null||job.id()==null||job.ownerToken()==null||job.command()==null) { throw new OrderRejected(400); }var route=route(job);
  return tx.execute(t->{
   owned(job,route);var source=applications.lockRecorded(route.exchangeId());var row=exchanges.lock(route.exchangeId());if(!source.orderId().equals(row.get("order_id"))||!source.sellerOrderId().equals(row.get("seller_order_id"))||!source.holdId().equals(row.get("hold_id"))||!source.causeId().equals(row.get("cause_id"))) { throw new OrderRejected(409); }
   var prior=jdbc.queryForList("SELECT mode,release_id FROM marketplace.claim_exchange_expiry_delivery WHERE job_id=:id",Map.of("id",job.id()));if(!prior.isEmpty()) { if(prior.size()!=1) { throw new OrderRejected(409); }if(prior.getFirst().get("release_id")!=null) { var release=stocks.released(route.exchangeId(),route.sequence());if(!release.id().equals(prior.getFirst().get("release_id"))||release.reason()!=ExchangeReservations.ReleaseReason.EXPIRED) { throw new OrderRejected(409); } }finalLease(job,route);return JobHandler.Result.COMPLETE; }
   int current=((Number)row.get("current_sequence")).intValue();int epoch=jdbc.queryForObject("SELECT max(epoch) FROM marketplace.claim_exchange_reservation_schedule WHERE exchange_id=:id AND sequence=:seq",Map.of("id",route.exchangeId(),"seq",route.sequence()),Integer.class);
   if(current>route.sequence()||epoch>route.epoch()) { delivery(job,route,"SUPERSEDED",null,null,now(),mapper.writeValueAsString(exchanges.progress(route.exchangeId())));finalLease(job,route);return JobHandler.Result.COMPLETE; }
   if(current!=route.sequence()||epoch!=route.epoch()) { throw new OrderRejected(409); }var currentStock=stocks.lockCurrent(route.exchangeId());
   if(List.of("REFUND_PENDING","REVIEW_REQUIRED","SETTLEMENT_PENDING","REFUNDED").contains(row.get("state").toString())&&jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_refund_consent WHERE exchange_id=:id AND sequence=:seq",Map.of("id",route.exchangeId(),"seq",route.sequence()),Integer.class)==1) {
    if(currentStock.isEmpty()||currentStock.stream().anyMatch(r->!List.of("EXPIRED","RELEASED").contains(r.status()))) { throw new OrderRejected(409); }try { jdbc.queryForList("SELECT marketplace.verify_exchange_refund_consent(:id)",Map.of("id",route.exchangeId())); }catch(org.springframework.dao.DataIntegrityViolationException invalid) { throw new OrderRejected(409); }
    delivery(job,route,"CONVERTED",null,null,now(),mapper.writeValueAsString(exchanges.progress(route.exchangeId())));finalLease(job,route);return JobHandler.Result.COMPLETE;
   }
   if(List.of("DISPATCHED","COMPLETED").contains(row.get("state").toString())&&currentStock.stream().allMatch(r->r.status().equals("COMMITTED"))) { delivery(job,route,"COMMITTED",null,null,now(),mapper.writeValueAsString(exchanges.progress(route.exchangeId())));finalLease(job,route);return JobHandler.Result.COMPLETE; }
   var at=now();Instant deadline=((Timestamp)row.get("expires_at")).toInstant();if(at.isBefore(deadline)) { finalLease(job,route);return JobHandler.Result.RETRY; }
   if(!List.of("APPROVED","COLLECTING","READY","REVIEW_REQUIRED","DISPATCH_PENDING").contains(row.get("state").toString())||currentStock.isEmpty()||currentStock.stream().anyMatch(r->!r.status().equals("RESERVED")||!r.expiresAt().equals(deadline))) { throw new OrderRejected(409); }
   UUID release=UUID.randomUUID();stocks.release(new ExchangeReservations.Release(release,route.exchangeId(),route.sequence(),ExchangeReservations.ReleaseReason.EXPIRED,at));long revision=Math.incrementExact(((Number)row.get("version")).longValue());String state=row.get("state").equals("REVIEW_REQUIRED")?"REVIEW_REQUIRED":"EXPIRED";String key="reservation-expired:"+route.sequence()+":"+route.epoch();String fingerprint=ReturnFeeQuoting.hash(job.id()+"|"+job.command());
   var p=new MapSqlParameterSource("id",route.exchangeId()).addValue("revision",revision).addValue("state",state).addValue("key",key).addValue("hash",fingerprint).addValue("at",Timestamp.from(at)).addValue("reason","EXCHANGE_ALLOCATION_EXPIRED_HOLD_RETAINED");
   jdbc.update("UPDATE marketplace.claim_exchange_request SET state=:state,version=:revision WHERE id=:id",p);jdbc.update("INSERT INTO marketplace.claim_exchange_history(id,exchange_id,action,version,reason,actor,occurred_at) VALUES(:event,:id,'EXPIRED',:revision,:reason,'RECORDED_WORKER',:at)",p.addValue("event",UUID.randomUUID()));exchanges.event(route.exchangeId(),"reservation-expired-"+route.sequence(),at);String receipt=mapper.writeValueAsString(exchanges.progress(route.exchangeId()));
   jdbc.update("INSERT INTO marketplace.claim_exchange_command(exchange_id,command_key,fingerprint,action,version,receipt) VALUES(:id,:key,:hash,'EXPIRED',:revision,CAST(:receipt AS jsonb))",p.addValue("receipt",receipt));delivery(job,route,"EXPIRED",release,revision,at,receipt);finalLease(job,route);return JobHandler.Result.COMPLETE;
  });
 }
 private void delivery(Jobs.Job job,Route route,String mode,UUID release,Long revision,Instant at,String receipt) {
  jdbc.update("INSERT INTO marketplace.claim_exchange_expiry_delivery(job_id,exchange_id,sequence,epoch,mode,release_id,workflow_revision,owner_token,recorded_at,receipt) VALUES(:job,:id,:seq,:epoch,:mode,:release,:revision,:owner,:at,CAST(:receipt AS jsonb))",new MapSqlParameterSource("job",job.id()).addValue("id",route.exchangeId()).addValue("seq",route.sequence()).addValue("epoch",route.epoch()).addValue("mode",mode).addValue("release",release).addValue("revision",revision).addValue("owner",job.ownerToken()).addValue("at",Timestamp.from(at)).addValue("receipt",receipt));
 }
}
