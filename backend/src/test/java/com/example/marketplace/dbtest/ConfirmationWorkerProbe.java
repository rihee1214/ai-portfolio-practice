package com.example.marketplace.dbtest;
import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import com.example.marketplace.order.infrastructure.JdbcOrderConfirmations;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** Non-web test child executes actual confirmation/scan handlers with production job SQL. */
public final class ConfirmationWorkerProbe {
 private static UUID id;private static Instant now;private static Path marker;private static String mode;private static JdbcJobs delegate;
 public static void main(String[] args) throws Exception {
  now=Instant.parse(args[0]);id=UUID.fromString(args[1]);marker=Path.of(args[2]);mode=args[3];String database=System.getenv("D2_DB_NAME");com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.validate(System.getenv("D2_DB_URL"),System.getenv("D2_DB_USERNAME"),database);
  String locations=args.length>4&&args[4].equals("production")?"classpath:db/migration":"classpath:db/migration,classpath:db/c3fixture";
  try(var context=new SpringApplicationBuilder(MarketplaceApplication.class,Config.class).profiles("db","worker","local-mailbox","local-http","confirmation-probe").run("--spring.config.location=classpath:/","--spring.datasource.url="+System.getenv("D2_DB_URL"),"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--marketplace.media.root="+marker.getParent().resolve(marker.getFileName()+"-media"),"--marketplace.worker.enabled=false","--spring.flyway.locations="+locations)) {
   try(var connection=context.getBean(DataSource.class).getConnection()) {com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyConnection(connection,database);}
   var jobs=context.getBean(Jobs.class);try(var engine=new WorkerEngine(jobs,List.copyOf(context.getBeansOfType(JobHandler.class).values()))) {if(!engine.runOnce()) {throw new IllegalStateException("TARGET_NOT_CLAIMED");}}
   if(!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) {throw new IllegalStateException("CONFIRMATION_INCOMPLETE");}Files.writeString(marker.resolveSibling(marker.getFileName()+"-completed"),"nonweb=true;pid="+ProcessHandle.current().pid());
  }
 }
 private static void pause(String stage) {
  try {Files.writeString(marker,stage+";pid="+ProcessHandle.current().pid());if(mode.equals("ACK")) {new java.util.concurrent.CountDownLatch(1).await();}else {long until=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();while(!Files.exists(marker.resolveSibling(marker.getFileName()+"-release"))&&System.nanoTime()<until) {Thread.sleep(20);}if(!Files.exists(marker.resolveSibling(marker.getFileName()+"-release"))) {throw new IllegalStateException("RELEASE_NOT_RECEIVED");}}}catch(Exception failure) {throw new IllegalStateException(failure);}
 }
 @TestConfiguration(proxyBeanMethods=false) @org.springframework.context.annotation.Profile("confirmation-probe")
 static class Config {
  @Bean @Primary Jobs confirmationProbeJobs(DataSource source,PlatformTransactionManager manager) {
   delegate=new JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(now,ZoneOffset.UTC));
   return new Jobs() {
    public UUID enqueue(Command c) {return delegate.enqueue(c);}
    public java.util.Optional<Job> claim(java.util.Set<String> types) {var result=delegate.claim(types);if(mode.equals("CLAIM")&&result.isPresent()) {pause("claimed before business guard");}return result;}
    public boolean heartbeat(UUID job,UUID owner) {return delegate.heartbeat(job,owner);}
    public boolean complete(UUID job,UUID owner) {if(mode.equals("ACK")&&job.equals(id)) {pause("confirmation committed before ack");}return delegate.complete(job,owner);}
    public boolean fail(UUID job,UUID owner,boolean terminal,String error) {boolean accepted=delegate.fail(job,owner,terminal,error);if(mode.equals("CLAIM")&&!accepted) {try {Files.writeString(marker.resolveSibling(marker.getFileName()+"-stale-rejected"),"business guard rejected stale worker; fail ack rejected");}catch(Exception failure) {throw new IllegalStateException(failure);}}return accepted;}
    public java.util.Optional<Job> find(UUID job) {return delegate.find(job);}
    public java.util.List<Job> failed(int size) {return delegate.failed(size);}
   };
  }
  @Bean @Primary JobLeases confirmationProbeLeases(Jobs jobs) {return (job,owner)->delegate.requireOwned(job,owner);}
  @Bean @Primary JdbcOrderConfirmations confirmationProbeApi(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,com.example.marketplace.order.api.OrderQueries queries,com.example.marketplace.order.api.Orders orders,com.example.marketplace.order.infrastructure.AftercareLifecycle lifecycle,com.example.marketplace.platform.api.Outbox outbox,JobLeases leases,PlatformTransactionManager manager) {return new JdbcOrderConfirmations(jdbc,queries,orders,lifecycle,outbox,leases,manager,Clock.fixed(now,ZoneOffset.UTC),stage->{ });}
 }
}
