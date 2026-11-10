package com.example.marketplace.claim.infrastructure;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.PhysicalOrders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.fulfillment.api.ExchangeShipments;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.JobHandler;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class ExchangeDeliveredWorker {
 public static final String TYPE="EXCHANGE_DELIVERED.v1";
 private final NamedParameterJdbcTemplate jdbc;private final ExchangeApplications applications;private final PhysicalOrders physical;private final ExchangeShipments shipments;private final JdbcExchanges exchanges;private final Jobs jobs;private final JobLeases leases;private final TransactionTemplate tx;
 private final java.time.Clock clock;
 @org.springframework.beans.factory.annotation.Autowired
 public ExchangeDeliveredWorker(NamedParameterJdbcTemplate jdbc,ExchangeApplications applications,PhysicalOrders physical,ExchangeShipments shipments,JdbcExchanges exchanges,Jobs jobs,JobLeases leases,PlatformTransactionManager manager) { this(jdbc,applications,physical,shipments,exchanges,jobs,leases,manager,null); }
 public ExchangeDeliveredWorker(NamedParameterJdbcTemplate jdbc,ExchangeApplications applications,PhysicalOrders physical,ExchangeShipments shipments,JdbcExchanges exchanges,Jobs jobs,JobLeases leases,PlatformTransactionManager manager,java.time.Clock clock) { this.jdbc=jdbc;this.applications=applications;this.physical=physical;this.shipments=shipments;this.exchanges=exchanges;this.jobs=jobs;this.leases=leases;this.tx=new TransactionTemplate(manager); this.clock=clock; }
 public JobHandler.Result complete(Jobs.Job job) {
  if(job==null||job.command()==null||job.id()==null||job.ownerToken()==null) { throw new OrderRejected(400); }var parts=job.command().payload().split("\\|",-1);if(parts.length!=2) { throw new OrderRejected(400); }UUID id=UUID.fromString(parts[0]),event=UUID.fromString(parts[1]);
  return tx.execute(t->{applications.lockRecorded(id);var row=exchanges.lock(id);var fact=shipments.delivery(id);var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));if(!actual.command().equals(job.command())||!actual.status().equals("RUNNING")||!java.util.Objects.equals(actual.ownerToken(),job.ownerToken())||!TYPE.equals(job.command().type())||!job.command().causeKey().equals(id.toString())||!job.command().dedupeKey().equals("exchange-delivered:"+id+":"+event)||!event.equals(fact.eventId())) { throw new OrderRejected(409); }
   var old=jdbc.queryForList("SELECT id,event_id FROM marketplace.claim_exchange_completion WHERE exchange_id=:id",Map.of("id",id));if(!old.isEmpty()) { if(!old.getFirst().get("event_id").equals(event)||!row.get("state").equals("COMPLETED")) { throw new OrderRejected(409); }leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE; }
   if(!row.get("state").equals("DISPATCHED")) { throw new OrderRejected(409); }UUID receipt=UUID.randomUUID();physical.completeExchange(id,receipt);long version=Math.incrementExact(((Number)row.get("version")).longValue());var at=now();var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("receipt",receipt).addValue("shipment",fact.shipmentId()).addValue("event",event).addValue("version",version).addValue("at",Timestamp.from(at)).addValue("job",job.id()).addValue("owner",job.ownerToken());jdbc.update("UPDATE marketplace.claim_exchange_request SET state='COMPLETED',version=:version WHERE id=:id",p);jdbc.update("INSERT INTO marketplace.claim_exchange_history(id,exchange_id,action,version,reason,actor,occurred_at) VALUES(:history,:id,'COMPLETED',:version,'ACTUAL_REPLACEMENT_DELIVERED','RECORDED_WORKER',:at)",p.addValue("history",UUID.randomUUID()));exchanges.event(id,"completed",at);jdbc.update("INSERT INTO marketplace.claim_exchange_completion(id,exchange_id,shipment_id,event_id,version,job_id,owner_token,recorded_at) VALUES(:receipt,:id,:shipment,:event,:version,:job,:owner,:at)",p);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;
  });
 }
 /** Production keeps its single database clock source; controlled integration fixtures supply the same explicit clock across the full workflow. */
 private java.time.Instant now() { return clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant(); }
}
