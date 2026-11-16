package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReleaseWorkerRecovery {
    static void exercise(ReleaseContractSupport support,Path jar,String url,String user,String password,Path media,int port) throws Exception {
        var api=support.jar(jar,"lease-producer-api",url,user,password,media,port,"api");
        try {
            var jdbc=support.jdbc(url,user,password);var manager=new JdbcTransactionManager(jdbc.getJdbcTemplate().getDataSource());
            var observations=new ArrayList<Object>();
            var purchases=new java.util.LinkedHashMap<String,ReleaseContractSupport.Purchase>();
            for(String point:List.of("BEFORE","AFTER")) {purchases.put(point,support.purchase("http://127.0.0.1:"+port,"desktop","lease-"+point.toLowerCase(java.util.Locale.ROOT)));}
            support.stop(api);assertFalse(api.isAlive(),"The real producer API must exit before independent worker recovery begins");
            for(String point:List.of("BEFORE","AFTER")) {
                String phase="prepare-owned-worker-after-api-exit";Instant phaseStart=Instant.now();long phaseNanos=System.nanoTime();
                timing(support,point,phase,"start",phaseStart,phaseNanos,60,"running");
                try {
                var purchase=purchases.get(point);
                UUID id=jdbc.queryForObject("SELECT j.id FROM marketplace.job j JOIN marketplace.notification_event e ON j.cause_key=e.id::text WHERE j.type='NOTIFICATION_DELIVER.v1' AND e.kind='PAYMENT_BUYER' AND e.target_id=CAST(:order AS uuid)",Map.of("order",purchase.id()),UUID.class);
                UUID event=jdbc.queryForObject("SELECT cause_key::uuid FROM marketplace.job WHERE id=:id",Map.of("id",id),UUID.class);
                Path scope=Files.createDirectory(support.folder.resolve("worker-lease-"+point));Path first=Files.createDirectory(scope.resolve("first"));
                var original=start(support,first,id,point,url,user,password);await(first.resolve("ready"),original,30);
                timing(support,point,phase,"end",phaseStart,phaseNanos,60,"passed");phase="business-initial-claim";phaseStart=Instant.now();phaseNanos=System.nanoTime();timing(support,point,phase,"start",phaseStart,phaseNanos,10,"running");Files.writeString(first.resolve("go"),"resume");
                await(first.resolve(point.equals("BEFORE")?"claimed-before-effect":"effect-committed"),original,10);
                var old=support.json.readTree(Files.readString(first.resolve("claimed.json")));UUID oldToken=UUID.fromString(old.path("ownerToken").asText());
                assertEquals(point.equals("BEFORE")?0:1,effectCount(jdbc,event));
                timing(support,point,phase,"end",phaseStart,phaseNanos,10,"passed");phase="cleanup-crashed-worker";phaseStart=Instant.now();phaseNanos=System.nanoTime();timing(support,point,phase,"start",phaseStart,phaseNanos,10,"running");
                original.destroyForcibly();assertTrue(original.waitFor(10,TimeUnit.SECONDS));
                timing(support,point,phase,"end",phaseStart,phaseNanos,10,"passed");phase="prepare-two-workers-and-real-lease-expiry";phaseStart=Instant.now();phaseNanos=System.nanoTime();timing(support,point,phase,"start",phaseStart,phaseNanos,60,"running");
                long preparationEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
                Path left=Files.createDirectory(scope.resolve("takeover-left")),right=Files.createDirectory(scope.resolve("takeover-right"));
                var a=start(support,left,id,"NORMAL",url,user,password);var b=start(support,right,id,"NORMAL",url,user,password);
                await(left.resolve("ready"),a,30);await(right.resolve("ready"),b,30);
                // JdbcJobs compares the persisted lease with the JVM clock. The Docker DB clock
                // can be slightly ahead, so DB expiry alone cannot authorize the host workers.
                Map<String,Object> eligible;
                while(true) {
                    eligible=jdbc.queryForMap("SELECT status,attempts,lease_until,clock_timestamp() AS database_time,(SELECT count(*) FROM marketplace.job_claim_fact f WHERE f.job_id=j.id AND f.attempt=j.attempts AND f.owner_token=j.owner_token) AS claim_facts FROM marketplace.job j WHERE id=:id",Map.of("id",id));
                    Instant hostTime=Clock.systemUTC().instant(),lease=((java.sql.Timestamp)eligible.get("lease_until")).toInstant();
                    eligible.put("hostTime",hostTime);eligible.put("leaseUntil",lease);
                    assertEquals("RUNNING",eligible.get("status"));assertEquals(1,((Number)eligible.get("attempts")).intValue());assertEquals(1,((Number)eligible.get("claim_facts")).intValue());
                    assertTrue(System.nanoTime()<preparationEnd,"Real stored lease did not expire in finite preparation");
                    if(!hostTime.isBefore(lease)&&!((java.sql.Timestamp)eligible.get("database_time")).toInstant().isBefore(lease)) {break;}
                    Thread.sleep(100);
                }
                Files.writeString(scope.resolve("lease-eligibility.json"),support.json.writeValueAsString(eligible));
                timing(support,point,phase,"end",phaseStart,phaseNanos,60,"passed");phase="business-resume-claim-effect-and-fencing";phaseStart=Instant.now();phaseNanos=System.nanoTime();timing(support,point,phase,"start",phaseStart,phaseNanos,10,"running");
                Instant resume=Instant.now();long businessEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                Files.writeString(left.resolve("go"),old.path("leaseUntil").asText());Files.writeString(right.resolve("go"),old.path("leaseUntil").asText());
                while((a.isAlive()||b.isAlive())&&System.nanoTime()<businessEnd) {Thread.sleep(20);}
                assertFalse(a.isAlive());assertFalse(b.isAlive());assertEquals(0,a.exitValue());assertEquals(0,b.exitValue());
                var resultA=support.json.readTree(Files.readString(left.resolve("result.json")));var resultB=support.json.readTree(Files.readString(right.resolve("result.json")));
                for(Path contender:List.of(left,right)) {
                    var clock=support.json.readTree(Files.readString(contender.resolve("claim-clock.json")));
                    assertEquals(old.path("leaseUntil").asText(),clock.path("originalExpiredLease").asText());
                    assertFalse(Instant.parse(clock.path("hostTimeBeforeClaim").asText()).isBefore(Instant.parse(old.path("leaseUntil").asText())));
                }
                assertNotEquals(resultA.path("claimed").asBoolean(),resultB.path("claimed").asBoolean());assertTrue(resultA.path("nonWeb").asBoolean());assertTrue(resultB.path("nonWeb").asBoolean());
                var jobs=new JdbcJobs(jdbc,manager,Clock.systemUTC());var job=jobs.find(id).orElseThrow();assertEquals("SUCCEEDED",job.status());assertEquals(2,job.attempts());
                assertFalse(jobs.complete(id,oldToken));assertFalse(jobs.fail(id,oldToken,false,"STALE_WORKER"));assertFalse(jobs.heartbeat(id,oldToken));
                assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->jobs.requireOwned(id,oldToken)));
                var notification=new com.example.marketplace.notification.infrastructure.NotificationDelivery(jdbc,new com.example.marketplace.platform.infrastructure.JdbcOutbox(jdbc,manager,jobs),jobs);
                var stale=new com.example.marketplace.platform.api.Jobs.Job(id,job.command(),"RUNNING",1,job.nextRunAt(),oldToken,Instant.parse(old.path("leaseUntil").asText()),null);
                assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->notification.handle(stale)));
                assertEquals(1,effectCount(jdbc,event));assertTrue(System.nanoTime()<businessEnd,"Resume, claims, business effect and stale-token assertions must share ten seconds");
                timing(support,point,phase,"end",phaseStart,phaseNanos,10,"passed");
                var observation=new java.util.LinkedHashMap<String,Object>();observation.put("point",point);observation.put("job",id);observation.put("oldClaim",old);observation.put("takeoverLeft",resultA);observation.put("takeoverRight",resultB);observation.put("resumedAt",resume);observation.put("effects",1);observation.put("attempts",2);observation.put("realLeaseSeconds",30);observation.put("staleCompleteFailHeartbeatAndWriteRejected",true);observation.put("producerApiPid",api.pid());observation.put("producerApiTerminatedBeforeWorkerCommands",!api.isAlive());observations.add(observation);
                } catch(Exception|AssertionError failure) {timing(support,point,phase,"end",phaseStart,phaseNanos,phase.startsWith("business")||phase.startsWith("cleanup")?10:60,"failed");throw failure;}
            }
            Files.writeString(support.folder.resolve("real-lease-recovery.json"),support.json.writeValueAsString(observations));
        } finally {if(api.isAlive()) {support.stop(api);}}
    }
    private static void timing(ReleaseContractSupport support,String point,String phase,String event,Instant started,long nanos,int seconds,String status) throws Exception {
        long elapsed=System.nanoTime()-nanos;
        Files.writeString(support.folder.resolve("real-lease-timing.ndjson"),support.json.writeValueAsString(Map.of("point",point,"phase",phase,"event",event,"started",started,
                "observed",Instant.now(),"deadline",started.plusSeconds(seconds),"limitMs",seconds*1000,"elapsedNanos",elapsed,"status",status))+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        if(event.equals("end")&&status.equals("passed")) {assertTrue(elapsed<TimeUnit.SECONDS.toNanos(seconds),phase+" exceeded its shared finite deadline");}
    }
    private static int effectCount(NamedParameterJdbcTemplate jdbc,UUID event) {return jdbc.queryForObject("SELECT count(*) FROM marketplace.notification_inbox WHERE event_id=:id",Map.of("id",event),Integer.class);}
    private static Process start(ReleaseContractSupport support,Path scope,UUID job,String point,String url,String user,String password) throws Exception {
        Path args=scope.resolve("probe.args");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace('\\','/');
        Files.writeString(args,"-cp\n\""+cp+"\"\ncom.example.marketplace.dbtest.ReleaseLeaseProbe\n"+job+"\n\""+scope.toString().replace('\\','/')+"\"\n"+point+"\n");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args).redirectErrorStream(true).redirectOutput(scope.resolve("process.log").toFile());
        var env=builder.environment();env.keySet().removeIf(name->name.startsWith("TOSS_")||name.startsWith("MARKETPLACE_RUNTIME_")||name.startsWith("FIREBASE_")||name.startsWith("GCLOUD_")||name.equals("D2_ADMIN_PASSWORD"));
        env.put("DB_URL",url);env.put("DB_USERNAME",user);env.put("DB_PASSWORD",password);
        var child=builder.start();support.children.add(child);return child;
    }
    private static void await(Path marker,Process child,int seconds) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!Files.exists(marker)&&child.isAlive()&&System.nanoTime()<end) {Thread.sleep(50);}
        assertTrue(Files.exists(marker),"Inspect preserved worker process log for "+marker);
    }
}
