package com.example.marketplace.dbtest;
import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
public final class ShipmentWorkerProbe {
 private static UUID id;private static Instant now;private static Path marker;private static boolean block;
 public static void main(String[] args) throws Exception {
  now=Instant.parse(args[0]);id=UUID.fromString(args[1]);marker=Path.of(args[2]);block=Boolean.parseBoolean(args[3]);
  com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyBeforeMigration(System.getenv("D2_DB_URL"),System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"),System.getenv("D2_DB_NAME"));
        try(var context=new SpringApplicationBuilder(MarketplaceApplication.class,Config.class).profiles("db","worker","local-mailbox","local-http","shipment-probe").run("--spring.config.location=classpath:/","--spring.datasource.url="+System.getenv("D2_DB_URL"),"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--marketplace.media.root="+marker.getParent().resolve("media"),"--spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture")) {
   var jobs=context.getBean(Jobs.class);long end=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();while(System.nanoTime()<end&&!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { Thread.sleep(25); }if(!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { throw new IllegalStateException("Incomplete shipment job"); }Files.writeString(marker.resolveSibling("completed.txt"),"nonweb=true;pid="+ProcessHandle.current().pid());
  }
 }
 @TestConfiguration(proxyBeanMethods=false) @org.springframework.context.annotation.Profile("shipment-probe")
 static class Config {
  @Bean @Primary Jobs shipmentProbeJobs(DataSource source,PlatformTransactionManager manager) {
   var delegate=new JdbcJobs(new OneJobJdbc(source,id),manager,Clock.fixed(now,ZoneOffset.UTC));
   return new Jobs() {
    public UUID enqueue(Command c) { return delegate.enqueue(c); }
    public java.util.Optional<Job> claim(java.util.Set<String> types) { return delegate.claim(types); }
    public boolean heartbeat(UUID job,UUID owner) { return delegate.heartbeat(job,owner); }
    public boolean complete(UUID job,UUID owner) { if(block&&job.equals(id)) { try { Files.writeString(marker,"effect committed before ack;pid="+ProcessHandle.current().pid());new java.util.concurrent.CountDownLatch(1).await(); }catch(Exception e) { throw new IllegalStateException(e); } }return delegate.complete(job,owner); }
    public boolean fail(UUID job,UUID owner,boolean terminal,String error) { return delegate.fail(job,owner,terminal,error); }
    public java.util.Optional<Job> find(UUID job) { return delegate.find(job); }
    public java.util.List<Job> failed(int size) { return delegate.failed(size); }
   };
  }
 }
}
