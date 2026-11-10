package com.example.marketplace.fulfillment.infrastructure;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.PhysicalOrders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Outbox;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
@Configuration(proxyBeanMethods=false) @Profile("db")
public class ExchangeShipmentJobs {
 @Bean JobHandler exchangeShipmentProgress(NamedParameterJdbcTemplate jdbc,ExchangeApplications applications,PhysicalOrders physical,Jobs jobs,JobLeases leases,Outbox outbox,PlatformTransactionManager manager) { return progress(jdbc,applications,physical,jobs,leases,outbox,manager,null); }
 public JobHandler progress(NamedParameterJdbcTemplate jdbc,ExchangeApplications applications,PhysicalOrders physical,Jobs jobs,JobLeases leases,Outbox outbox,PlatformTransactionManager manager,java.time.Clock clock) { return new JobHandler() {
  public String type() { return "EXCHANGE_SHIPMENT_PROGRESS.v1"; }
  public Result handle(Jobs.Job job) {
   var parts=job.command().payload().split("\\|",-1);if(parts.length!=2) { throw new OrderRejected(400); }UUID shipment=UUID.fromString(parts[0]);long expected=Long.parseLong(parts[1]);
   return new TransactionTemplate(manager).execute(t->{var ids=jdbc.queryForList("SELECT exchange_id FROM marketplace.fulfillment_exchange_shipment WHERE id=:id",Map.of("id",shipment),UUID.class);if(ids.size()!=1) { throw new OrderRejected(409); }UUID exchange=ids.getFirst();applications.lockRecorded(exchange);var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",shipment).addValue("version",expected);var schedules=jdbc.queryForList("SELECT * FROM marketplace.fulfillment_exchange_progress_schedule WHERE shipment_id=:id AND expected_version=:version",p);
    if(schedules.size()!=1||!schedules.getFirst().get("job_id").equals(job.id())||!actual.command().equals(job.command())||!actual.status().equals("RUNNING")||!java.util.Objects.equals(actual.ownerToken(),job.ownerToken())||!job.command().type().equals(type())||!job.command().causeKey().equals(shipment.toString())||!job.command().dedupeKey().equals("exchange-progress:"+shipment+":"+expected)||!job.command().nextRunAt().equals(((Timestamp)schedules.getFirst().get("scheduled_at")).toInstant())) { throw new OrderRejected(409); }
    var row=jdbc.queryForMap("SELECT * FROM marketplace.fulfillment_exchange_shipment WHERE id=:id FOR UPDATE",p);var events=jdbc.queryForList("SELECT * FROM marketplace.fulfillment_exchange_event WHERE shipment_id=:id AND version=:version",p);if(!events.isEmpty()) { replay(jdbc,physical,row,events.getFirst(),job,expected);leases.requireOwned(job.id(),job.ownerToken());return Result.COMPLETE; }
    if(expected<2||expected>3) { throw new OrderRejected(409); }if(((Number)row.get("version")).longValue()!=expected-1) { leases.requireOwned(job.id(),job.ownerToken());return Result.RETRY; }
    var dueClaim=leases.requireDueClaim(job.id(),job.ownerToken());if(!dueClaim.scheduledAt().equals(job.command().nextRunAt())) { throw new OrderRejected(409); }
    var at=clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant();UUID event=UUID.randomUUID();String state=expected==2?"IN_TRANSIT":"DELIVERED";p.addValue("event",event).addValue("state",state).addValue("at",Timestamp.from(at));jdbc.update("INSERT INTO marketplace.fulfillment_exchange_event(id,shipment_id,version,state,occurred_at) VALUES(:event,:id,:version,:state,:at)",p);jdbc.update("UPDATE marketplace.fulfillment_exchange_shipment SET state=:state,version=:version,delivered_at=CASE WHEN :state='DELIVERED' THEN CAST(:at AS timestamptz) ELSE NULL END WHERE id=:id",p);
    if(expected==3) { physical.delivered(new PhysicalOrders.Delivered(shipment,event,at));String key="exchange-delivered:"+exchange+":"+event;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("EXCHANGE_DELIVERED.v1",key,exchange.toString(),exchange+"|"+event,at))); }
    jdbc.update("INSERT INTO marketplace.fulfillment_exchange_progress_effect(shipment_id,version,event_id,job_id,owner_token,claim_attempt) VALUES(:id,:version,:event,:job,:owner,:claimAttempt)",p.addValue("job",job.id()).addValue("owner",job.ownerToken()).addValue("claimAttempt",dueClaim.attempt()));leases.requireOwned(job.id(),job.ownerToken());return Result.COMPLETE;
   });
  }
 }; }
 private static void replay(NamedParameterJdbcTemplate jdbc,PhysicalOrders physical,Map<String,Object> header,Map<String,Object> event,Jobs.Job job,long expected) {
  UUID shipment=(UUID)header.get("id");var p=Map.of("shipment",shipment,"version",expected);var effects=jdbc.queryForList("SELECT * FROM marketplace.fulfillment_exchange_progress_effect WHERE shipment_id=:shipment AND version=:version",p);long current=((Number)header.get("version")).longValue();String expectedState=expected==2?"IN_TRANSIT":expected==3?"DELIVERED":"INVALID";
  if(effects.size()!=1||!effects.getFirst().get("job_id").equals(job.id())||!effects.getFirst().get("event_id").equals(event.get("id"))||!event.get("state").equals(expectedState)||current<expected||current>3) { throw new OrderRejected(409); }var latest=jdbc.queryForList("SELECT state,occurred_at FROM marketplace.fulfillment_exchange_event WHERE shipment_id=:id AND version=:version",Map.of("id",shipment,"version",current));if(latest.size()!=1||!latest.getFirst().get("state").equals(header.get("state"))||!header.get("state").equals(current==3?"DELIVERED":"IN_TRANSIT")||current==3&&!java.util.Objects.equals(header.get("delivered_at"),latest.getFirst().get("occurred_at"))||current<3&&header.get("delivered_at")!=null) { throw new OrderRejected(409); }
  if(expected==3) { var ids=jdbc.queryForList("SELECT physical_id FROM marketplace.fulfillment_exchange_allocation WHERE shipment_id=:id ORDER BY item_id::text,ordinal",Map.of("id",shipment),UUID.class);if(ids.isEmpty()) { throw new OrderRejected(409); }for(var id:ids) { var delivery=physical.recorded(id).delivery();if(delivery==null||delivery.kind()!=PhysicalOrders.BucketKind.EXCHANGE||!delivery.shipmentId().equals(shipment)||!delivery.eventId().equals(event.get("id"))||!delivery.deliveredAt().equals(((Timestamp)event.get("occurred_at")).toInstant())) { throw new OrderRejected(409); } } }
 }

}
