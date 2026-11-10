package com.example.marketplace.dbtest;
import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.payment.infrastructure.PaymentCoordinator;
import com.example.marketplace.payment.api.PaymentProvider;
import com.example.marketplace.payment.api.Payments;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
public final class PaymentApprovalProbe {
 private static String type,number,point;private static Instant now;private static Path marker;private static boolean block;private static Payments.Authority authority;
 public static void main(String[] args) throws Exception {
  now=Instant.parse(args[0]);type=args[1];marker=Path.of(args[2]);block=Boolean.parseBoolean(args[3]);UUID id=UUID.fromString(args[4]);number=args[5];point=args[6];authority=new Payments.Authority(args[7],UUID.fromString(args[8]),Long.valueOf(args[9]),null);
  com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyBeforeMigration(System.getenv("D2_DB_URL"),System.getenv("D2_DB_USERNAME"),System.getenv("D2_DB_PASSWORD"),System.getenv("D2_DB_NAME"));
        try(var context=new SpringApplicationBuilder(MarketplaceApplication.class,Config.class).profiles("db","worker","local-mailbox","local-http","zero-probe").run("--spring.config.location=classpath:/","--spring.datasource.url="+System.getenv("D2_DB_URL"),"--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),"--marketplace.media.root="+marker.getParent().resolve("media"),"--spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture")) {
   var jobs=context.getBean(Jobs.class);long end=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();while(System.nanoTime()<end&&!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { Thread.sleep(25); }if(!jobs.find(id).orElseThrow().status().equals("SUCCEEDED")) { throw new IllegalStateException("Incomplete zero approval job"); }Files.writeString(marker.resolveSibling("completed.txt"),"nonweb=true;pid="+ProcessHandle.current().pid());
  }
 }
 @TestConfiguration(proxyBeanMethods=false) @org.springframework.context.annotation.Profile("zero-probe")
 static class Config {
  @Bean @Primary Jobs probeJobs(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) { return new ScopedJobs(new JdbcJobs(jdbc,manager,Clock.fixed(now,ZoneOffset.UTC)),type); }
  @Bean @Primary PaymentProvider forbiddenProvider(com.example.marketplace.payment.infrastructure.MockPaymentProvider delegate) { return new PaymentProvider() {
   private Result forbidden() { try { Files.writeString(marker.resolveSibling("forbidden-provider.txt"),"external call"); }catch(java.io.IOException failure) { throw new IllegalStateException(failure); }throw new IllegalStateException("ZERO_PROVIDER_CALL_FORBIDDEN"); }
   public Result approve(Request r) { return r.amount()==0?forbidden():delegate.approve(r); }public Result query(Request r) { return r.amount()==0?forbidden():delegate.query(r); }public Result cancel(Request r) { return r.amount()==0?forbidden():delegate.cancel(r); }public Result queryCancellation(Request r) { return r.amount()==0?forbidden():delegate.queryCancellation(r); }
  }; }
  @Bean @Primary PaymentCoordinator probePayment(NamedParameterJdbcTemplate jdbc,com.example.marketplace.order.api.Orders orders,com.example.marketplace.inventory.api.Reservations inventory,com.example.marketplace.promotion.api.CouponReservations coupons,PaymentProvider provider,Jobs jobs,com.example.marketplace.platform.api.Outbox outbox,PlatformTransactionManager manager,com.example.marketplace.identity.api.AccessPolicy access,com.example.marketplace.identity.api.SellerMembership members,com.example.marketplace.identity.api.PurchaseSessions sessions) {
   return new PaymentCoordinator(jdbc,orders,inventory,coupons,provider,jobs,outbox,manager,access,members,sessions,Clock.systemUTC(),stage->{if(block&&stage.equals(point)) { Files.writeString(marker,"checkpoint="+point+";pid="+ProcessHandle.current().pid());new java.util.concurrent.CountDownLatch(1).await(); }});
  }
  @Bean JobHandler zeroProbeHandler(PaymentCoordinator coordinator) { return new JobHandler() {
   public String type() { return type; }
   public Result handle(Jobs.Job job) { coordinator.approve(number,authority);coordinator.query(number,0,0,false);return Result.COMPLETE; }
  }; }
 }
}
