package com.example.marketplace.fulfillment.infrastructure;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.order.api.FulfillmentOrders;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration(proxyBeanMethods=false) @Profile("db")
public class MockShipmentJobs {
 private static Jobs.Command scan(String cursor) { return new Jobs.Command("FULFILLMENT_BACKFILL","fulfillment-backfill-v1:"+cursor,"fulfillment-backfill-v1",cursor,Instant.EPOCH); }
 @Bean @ConditionalOnProperty(name="marketplace.worker.enabled",havingValue="true")
 ApplicationRunner shipmentBackfillStartup(Jobs jobs) { return args->jobs.enqueue(scan("START")); }
 @Bean JobHandler fulfillmentBackfill(Jobs jobs,FulfillmentOrders orders,PlatformTransactionManager manager) { return new JobHandler() {
  public String type() { return "FULFILLMENT_BACKFILL"; }
  public Result handle(Jobs.Job job) {
   new TransactionTemplate(manager).executeWithoutResult(t->{String cursor=job.command().payload();var rows=orders.paidCandidates(cursor.equals("START")?null:UUID.fromString(cursor),50);
    for(var row:rows) { jobs.enqueue(new Jobs.Command("FULFILLMENT_PREPARE","fulfillment-backfill-v1:order:"+row.id(),row.id().toString(),row.number(),Instant.EPOCH)); }
    if(rows.size()==50) { jobs.enqueue(scan(rows.getLast().id().toString())); }
   });return Result.COMPLETE;
  }
 }; }
 @Bean JobHandler paymentSettled(JdbcShipments shipments) { return prepare("PAYMENT_SETTLED",shipments); }
 @Bean JobHandler fulfillmentPrepare(JdbcShipments shipments) { return prepare("FULFILLMENT_PREPARE",shipments); }
 private JobHandler prepare(String type,JdbcShipments shipments) { return new JobHandler() {
  public String type() { return type; }
  public Result handle(Jobs.Job job) { shipments.prepare(job.command().payload().split(":")[0]);return Result.COMPLETE; }
 }; }
 @Bean JobHandler shipmentCreated() { return new JobHandler() { public String type() { return "SHIPMENT_CREATED"; }public Result handle(Jobs.Job job) { return Result.COMPLETE; } }; }
 @Bean JobHandler shipmentProgress(JdbcShipments shipments) { return new JobHandler() {
  public String type() { return "SHIPMENT_PROGRESS"; }
  public Result handle(Jobs.Job job) { var p=job.command().payload().split("\\|");shipments.progress(UUID.fromString(p[0]),job.command().dedupeKey(),p[1],Long.parseLong(p[2]),job.command().nextRunAt());return Result.COMPLETE; }
 }; }
}
