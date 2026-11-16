package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual process interruption around a genuine paid order's notification effect. */
public final class ReleaseLeaseProbe {
    public static void main(String[] args) throws Exception {
        UUID id=UUID.fromString(args[0]);Path scope=Path.of(args[1]);String point=args[2];
        var application=new SpringApplication(MarketplaceApplication.class);
        try(var context=application.run("--spring.profiles.active=db,worker","--marketplace.worker.enabled=false","--server.address=127.0.0.1",
                "--marketplace.runtime.mode=local-full","--marketplace.phone.mode=disabled")) {
            var delegate=new JdbcJobs(new OneJobJdbc(context.getBean(javax.sql.DataSource.class),id),context.getBean(PlatformTransactionManager.class),Clock.systemUTC());
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            var jobs=new Jobs() {
                public UUID enqueue(Command command) {return delegate.enqueue(command);}
                public java.util.Optional<Job> claim(Set<String> types) {
                    var previous=delegate.find(id).orElseThrow();var observed=Clock.systemUTC().instant();
                    var claimed=delegate.claim(types);
                    try {
                        var attempt=new java.util.LinkedHashMap<String,Object>();attempt.put("hostTimeBeforeClaim",observed);attempt.put("priorStatus",previous.status());attempt.put("priorLeaseUntil",previous.leaseUntil());attempt.put("priorAttempts",previous.attempts());attempt.put("claimed",claimed.isPresent());attempt.put("pid",ProcessHandle.current().pid());
                        if(point.equals("NORMAL")) {attempt.put("originalExpiredLease",Files.readString(scope.resolve("go")));}
                        Files.writeString(scope.resolve("claim-clock.json"),json.writeValueAsString(attempt));
                    } catch(Exception failure) {throw new IllegalStateException(failure);}
                    claimed.ifPresent(job->{try {Files.writeString(scope.resolve("claimed.json"),json.writeValueAsString(java.util.Map.of("id",job.id(),"ownerToken",job.ownerToken(),"leaseUntil",job.leaseUntil(),"attempts",job.attempts(),"pid",ProcessHandle.current().pid())));}catch(Exception failure) {throw new IllegalStateException(failure);}});
                    return claimed;
                }
                public boolean heartbeat(UUID job,UUID token) {return delegate.heartbeat(job,token);}
                public boolean complete(UUID job,UUID token) {if(point.equals("AFTER")) {block("effect-committed");}return delegate.complete(job,token);}
                public boolean fail(UUID job,UUID token,boolean terminal,String error) {return delegate.fail(job,token,terminal,error);}
                public java.util.Optional<Job> find(UUID job) {return delegate.find(job);}
                public List<Job> failed(int count) {return delegate.failed(count);}
                private void block(String name) {try {Files.writeString(scope.resolve(name),"pid="+ProcessHandle.current().pid());new java.util.concurrent.CountDownLatch(1).await();}catch(Exception failure) {throw new IllegalStateException(failure);}}
            };
            String type=delegate.find(id).orElseThrow().command().type();
            var real=context.getBeansOfType(JobHandler.class).values().stream().filter(handler->handler.type().equals(type)).findFirst().orElseThrow();
            var handler=new JobHandler() {
                public String type() {return real.type();}
                public Result handle(Jobs.Job job) throws Exception {
                    if(point.equals("BEFORE")) {Files.writeString(scope.resolve("claimed-before-effect"),"pid="+ProcessHandle.current().pid());new java.util.concurrent.CountDownLatch(1).await();}
                    return real.handle(job);
                }
            };
            Files.writeString(scope.resolve("ready"),Long.toString(ProcessHandle.current().pid()));
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while(!Files.exists(scope.resolve("go"))&&System.nanoTime()<end) {Thread.sleep(20);}
            if(!Files.exists(scope.resolve("go"))) {throw new IllegalStateException("Finite resume-command wait expired");}
            try(var engine=new WorkerEngine(jobs,List.of(handler))) {
                boolean ran=engine.runOnce();Files.writeString(scope.resolve("result.json"),json.writeValueAsString(java.util.Map.of("claimed",ran,"status",delegate.find(id).orElseThrow().status(),"pid",ProcessHandle.current().pid(),"nonWeb",!context.containsBean("dispatcherServlet"))));
            }
        }
    }
}
