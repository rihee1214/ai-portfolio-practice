package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owned child JVM only. Fixture handler/configuration are never packaged in the application JAR. */
public final class WorkerProcessProbe {
    private static Instant now;
    private static String type;
    private static Path marker;
    private static boolean block;
    public static void main(String[] args) throws Exception {
        now=Instant.parse(args[0]);type=args[1];marker=Path.of(args[2]);block=Boolean.parseBoolean(args[3]);UUID job=UUID.fromString(args[4]);
        try(var context=new SpringApplicationBuilder(MarketplaceApplication.class,ProbeConfiguration.class)
                .profiles("db","worker","local-mailbox","local-http","worker-probe")
                .run("--spring.config.location=classpath:/","--spring.datasource.url="+System.getenv("D2_DB_URL"),
                        "--spring.datasource.username="+System.getenv("D2_DB_USERNAME"),"--spring.datasource.password="+System.getenv("D2_DB_PASSWORD"),
                        "--marketplace.media.root="+marker.getParent().resolve("media"),"--spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture")) {
            if(context.getBeansOfType(WorkerEngine.class).size()!=1||context.getClass().getName().contains("Servlet")) { throw new IllegalStateException("Worker profile did not activate without web server"); }
            var jobs=context.getBean(Jobs.class);long end=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
            while(System.nanoTime()<end&&!jobs.find(job).orElseThrow().status().equals("SUCCEEDED")) { Thread.sleep(25); }
            if(!jobs.find(job).orElseThrow().status().equals("SUCCEEDED")) { throw new IllegalStateException("Job did not finish"); }
            Files.writeString(marker.resolveSibling("completed.txt"),"pid="+ProcessHandle.current().pid()+";nonweb=true;status=SUCCEEDED");
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("worker-probe")
    static class ProbeConfiguration {
        @Bean @Primary Jobs probeJobs(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) { return new ScopedJobs(new JdbcJobs(jdbc,manager,Clock.fixed(now,ZoneOffset.UTC)),type); }
        @Bean JobHandler durableProbe(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) {
            return new JobHandler() {
                public String type() { return type; }
                public Result handle(Jobs.Job job) throws Exception {
                    if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Handler ran in a transaction"); }
                    new TransactionTemplate(manager).executeWithoutResult(status->jdbc.update("INSERT INTO marketplace.worker_probe_effect(cause_key,effects) VALUES(:cause,1) ON CONFLICT DO NOTHING",Map.of("cause",job.command().causeKey())));
                    Files.writeString(marker,"effect committed;pid="+ProcessHandle.current().pid());
                    if(block) { new CountDownLatch(1).await(); }
                    return Result.COMPLETE;
                }
            };
        }
    }
}

