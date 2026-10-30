package com.example.marketplace.order.infrastructure;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Outbox;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class ConfirmationJobs {
 @Bean JobHandler confirmationDue(JdbcOrderConfirmations api) { return handler("ORDER_CONFIRM_DUE",api); }
 @Bean JobHandler confirmationReevaluate(JdbcOrderConfirmations api) { return handler("ORDER_CONFIRM_REEVALUATE",api); }
 private JobHandler handler(String type,JdbcOrderConfirmations api) { return new JobHandler() { public String type() { return type; }public Result handle(Jobs.Job job) { return api.automatic(job); } }; }
 @Bean JobHandler confirmationRecorded() { return new JobHandler() { public String type() { return "ORDER_CONFIRMATION_RECORDED"; }public Result handle(Jobs.Job job) { return Result.COMPLETE; } }; }
 @Bean JobHandler unitLifecycleRecorded() { return new JobHandler() { public String type() { return "ORDER_UNIT_LIFECYCLE_RECORDED"; }public Result handle(Jobs.Job job) { return Result.COMPLETE; } }; }
 @Bean @ConditionalOnProperty(name="marketplace.worker.enabled",havingValue="true")
 ApplicationRunner confirmationScanStartup(NamedParameterJdbcTemplate jdbc,Outbox outbox,PlatformTransactionManager manager) { return args->new TransactionTemplate(manager).executeWithoutResult(t->start(jdbc,outbox,"P09-B-H03-20261030-OPTION2")); }
 public static void start(NamedParameterJdbcTemplate jdbc,Outbox outbox,String run) {
  AftercareLifecycle.transaction();if(run==null||!run.matches("[A-Za-z0-9_-]{1,100}")) { throw new IllegalArgumentException("INVALID_SCAN_RUN"); }
  var upper=jdbc.queryForList("SELECT id FROM marketplace.purchase_order ORDER BY id DESC LIMIT 1",Map.of(),UUID.class);if(upper.isEmpty()) { return; }
  jdbc.update("INSERT INTO marketplace.order_confirmation_scan(run_id,upper_id,started_at) VALUES(:run,:upper,clock_timestamp()) ON CONFLICT DO NOTHING",Map.of("run",run,"upper",upper.getFirst()));enqueue(jdbc,outbox,run,"START");
 }
 private static void enqueue(NamedParameterJdbcTemplate jdbc,Outbox outbox,String run,String cursor) { var at=jdbc.queryForObject("SELECT started_at FROM marketplace.order_confirmation_scan WHERE run_id=:run",Map.of("run",run),(r,n)->r.getTimestamp(1).toInstant());String key="confirmation-scan:"+run+":"+cursor;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("ORDER_CONFIRM_SCAN",key,run,run+"|"+cursor,at))); }
 @Bean JobHandler confirmationScan(NamedParameterJdbcTemplate jdbc,AftercareLifecycle lifecycle,Outbox outbox,JobLeases leases,PlatformTransactionManager manager) { return new JobHandler() {
  public String type() { return "ORDER_CONFIRM_SCAN"; }
  public Result handle(Jobs.Job job) {
   new TransactionTemplate(manager).executeWithoutResult(t->{var fields=job.command().payload().split("\\|",-1);if(fields.length!=2) { throw new IllegalArgumentException("INVALID_SCAN_PAYLOAD"); }String run=fields[0],cursor=fields[1];UUID upper=jdbc.queryForObject("SELECT upper_id FROM marketplace.order_confirmation_scan WHERE run_id=:run",Map.of("run",run),UUID.class);
    var orders=jdbc.queryForList("SELECT id FROM marketplace.purchase_order WHERE id<=:upper"+(cursor.equals("START")?"":" AND id>:cursor")+" ORDER BY id LIMIT 50",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("upper",upper).addValue("cursor",cursor.equals("START")?null:UUID.fromString(cursor)),UUID.class);
    for(var order:orders) { lifecycle.lockOrder(order);var units=jdbc.query("SELECT item_id,ordinal FROM marketplace.order_unit_lifecycle WHERE order_id=:id ORDER BY item_id::text,ordinal FOR UPDATE",Map.of("id",order),(r,n)->new com.example.marketplace.order.api.OrderConfirmations.Unit(r.getObject(1,UUID.class),r.getInt(2)));for(var unit:units) { lifecycle.schedule(order,unit,null); } }
    if(orders.size()==50) { enqueue(jdbc,outbox,run,orders.getLast().toString()); }leases.requireOwned(job.id(),job.ownerToken());
   });return Result.COMPLETE;
  }
 }; }
}
