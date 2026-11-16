package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Development regressions exercise real owned processes; only phase clocks are synthetic. */
class ReleaseOperationLifecycleTest {
    private static final long WALL=Instant.parse("2026-11-16T00:00:00Z").toEpochMilli();
    private static final long NANO=TimeUnit.SECONDS.toNanos(1);

    @Test void actualOwnedChildAndDescendantAndRequestFinishBeforeTargetCleanup() throws Exception {
        var folder=probe();var lifecycle=new ReleaseOperationLifecycle(folder);var started=new CountDownLatch(1);
        var process=new AtomicReference<Process>();var requestFinished=new AtomicBoolean();var interrupted=new AtomicBoolean();
        var sentinel=builder(folder,"sentinel").start();
        var task=lifecycle.executor().submit(()->{
            try(var request=lifecycle.enterRequest();var owned=lifecycle.start(builder(folder,"tree"))) {
                process.set(owned.process());started.countDown();new CountDownLatch(1).await();
            } catch(InterruptedException expected) {interrupted.set(true);}
            catch(Exception failure) {throw new IllegalStateException("Actual operator request failed",failure);}
            finally {requestFinished.set(true);}
        });
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS),"Actual owned operator must start");awaitFile(folder.resolve("tree-ready.json"));awaitFile(folder.resolve("sentinel-ready.txt"));
            var identities=ReleaseSnapshotContract.JSON.readTree(Files.readString(folder.resolve("tree-ready.json")));
            long leafPid=identities.path("descendant").asLong();var leaf=ProcessHandle.of(leafPid).orElseThrow();
            assertEquals(process.get().pid(),identities.path("root").asLong());assertTrue(process.get().isAlive()&&leaf.isAlive()&&sentinel.isAlive());
            assertEquals(1,lifecycle.activeOperations());assertEquals(1,lifecycle.activeRequests());
            long cancellationStarted=System.nanoTime();lifecycle.cancelAndJoin();
            assertTrue(System.nanoTime()-cancellationStarted<TimeUnit.SECONDS.toNanos(10),"Cancellation must retain its original shared ten-second budget");
            assertEquals(0,lifecycle.activeOperations());assertEquals(0,lifecycle.activeRequests());assertTrue(lifecycle.executor().isTerminated());
            assertTrue(task.isDone()&&requestFinished.get()&&interrupted.get(),"Request work must finish before target cleanup, without a test-side join");task.get(0,TimeUnit.MILLISECONDS);
            assertFalse(process.get().isAlive());assertFalse(leaf.isAlive());assertTrue(sentinel.isAlive(),"Unregistered independent process must survive cancellation");
            var audit=ReleaseSnapshotContract.JSON.readTree(Files.readString(lifecycle.cancellationEvidence()));
            assertEquals("passed",audit.path("status").asText());assertEquals(10000,audit.path("limitMs").asInt());assertTrue(audit.path("executorTerminated").asBoolean());
            assertEquals(0,audit.path("activeOperations").asInt());assertEquals(0,audit.path("activeRequests").asInt());
            var observed=new java.util.HashSet<Long>();audit.path("observedHandles").forEach(row->{observed.add(row.path("pid").asLong());assertFalse(row.path("alive").asBoolean());});
            assertTrue(observed.contains(process.get().pid())&&observed.contains(leafPid));assertFalse(observed.contains(sentinel.pid()));
            audit.path("handlerThreads").forEach(row->assertFalse(row.path("alive").asBoolean()));
            // This marker stands for the first DB/media/proxy cleanup action; every guard above must already hold.
            Files.writeString(folder.resolve("simulated-target-cleanup-started.txt"),Instant.now().toString());
            assertFalse(Instant.parse(Files.readString(folder.resolve("simulated-target-cleanup-started.txt"))).isBefore(Instant.parse(audit.path("ended").asText())));
        } finally {
            try {if(!lifecycle.executor().isTerminated()||lifecycle.activeOperations()!=0||lifecycle.activeRequests()!=0) {lifecycle.cancelAndJoin();}}
            finally {terminate(sentinel);terminate(process.get());if(Files.exists(folder.resolve("leaf-ready.txt"))) {terminate(Long.parseLong(Files.readString(folder.resolve("leaf-ready.txt"))));}}
        }
    }
    @Test void refusesSelfJoinAndNewAdmissionsBeforeLaunchingAnyCleanupRace() throws Exception {
        var lifecycle=new ReleaseOperationLifecycle(probe());
        try {
            try(var request=lifecycle.enterRequest()) {
                assertEquals(1,lifecycle.activeRequests());var failure=assertThrows(AssertionError.class,lifecycle::cancelAndJoin);assertTrue(failure.getMessage().contains("own request"));
            }
            lifecycle.stopAccepting();assertThrows(AssertionError.class,lifecycle::enterRequest);
            assertThrows(AssertionError.class,()->lifecycle.start(new ProcessBuilder(node().toString(),"-e","process.exit(0)")));
            assertEquals(0,lifecycle.activeOperations());assertEquals(0,lifecycle.activeRequests());
        } finally {lifecycle.cancelAndJoin();}
    }
    @Test void readinessProgressAndSpawnReceiptsCannotEndOrRenewTheOuterPhase() {
        var forwarder=new ReleasePhaseForwarder(WALL,NANO);var start=forwarder.accept(start("api-readiness",WALL,30000),WALL+1000,NANO).getFirst();
        long deadline=forwarder.deadlineNanos();assertEquals(29000L,start.get("limitMs"));
        var observation=forwarder.accept(event("state","progress","phase","api-readiness-probe","parentPhase","api-readiness","attempt",1,"exitCode",1,"attemptState","failed","deadline",iso(WALL+60000)),WALL+2000,NANO+TimeUnit.SECONDS.toNanos(1));
        assertEquals(1,observation.size());assertEquals("progress",observation.getFirst().get("event"));assertEquals(start.get("id"),observation.getFirst().get("id"));assertEquals("1",observation.getFirst().get("exitCode").toString());
        var spawn=forwarder.accept(event("state","progress","phase","start-host-api","observation","owned-role-spawned"),WALL+2500,NANO+TimeUnit.MILLISECONDS.toNanos(1500));
        assertEquals("progress",spawn.getFirst().get("event"));
        assertTrue(forwarder.accept(event("state","spawned","phase","api-readiness-probe","pid",123),WALL+3000,NANO+TimeUnit.SECONDS.toNanos(2)).isEmpty());
        assertTrue(forwarder.accept(event("state","observed","phase","api-readiness-probe"),WALL+4000,NANO+TimeUnit.SECONDS.toNanos(3)).isEmpty());
        assertTrue(forwarder.active());assertEquals(deadline,forwarder.deadlineNanos());assertThrows(AssertionError.class,()->forwarder.check(deadline+1));
    }
    @Test void onlyMatchingOuterCompletionEndsTheOriginalPhase() {
        var forwarder=new ReleasePhaseForwarder(WALL,NANO);var started=forwarder.accept(start("postgres-ready",WALL,30000),WALL,NANO).getFirst();long deadline=forwarder.deadlineNanos();
        assertThrows(AssertionError.class,()->forwarder.accept(event("state","completed","phase","postgres-ready-probe"),WALL+1,NANO+1));
        assertTrue(forwarder.active());assertEquals(deadline,forwarder.deadlineNanos());
        var end=forwarder.accept(event("state","completed","phase","postgres-ready"),WALL+100,NANO+TimeUnit.MILLISECONDS.toNanos(100));
        assertEquals(1,end.size());assertEquals(Map.of("event","end","id",started.get("id"),"name","postgres-ready","status","passed"),end.getFirst());assertFalse(forwarder.active());forwarder.finish();
    }
    @Test void overlappingOuterPhaseStartsAreRejectedWithoutReplacingTheOriginalDeadline() {
        var forwarder=new ReleasePhaseForwarder(WALL,NANO);forwarder.accept(start("dump",WALL,60000),WALL,NANO);long deadline=forwarder.deadlineNanos();
        assertThrows(AssertionError.class,()->forwarder.accept(start("restore",WALL+1,60000),WALL+1,NANO+1));assertTrue(forwarder.active());assertEquals(deadline,forwarder.deadlineNanos());
    }
    @Test void readinessProgressRequiresItsMatchingOuterPhaseOrExplicitRoleSpawn() {
        var forwarder=new ReleasePhaseForwarder(WALL,NANO);forwarder.accept(start("worker-readiness",WALL,30000),WALL,NANO);long deadline=forwarder.deadlineNanos();
        assertThrows(AssertionError.class,()->forwarder.accept(event("state","progress","phase","unrelated-probe","parentPhase","api-readiness"),WALL+1,NANO+1));
        assertThrows(AssertionError.class,()->forwarder.accept(event("state","progress","phase","start-host-api","observation","owned-role-spawned"),WALL+2,NANO+2));
        assertTrue(forwarder.active());assertEquals(deadline,forwarder.deadlineNanos());
    }
    @Test void producerAbsoluteDeadlinesRejectExpiredAndUnboundedPhases() {
        var unbounded=new ReleasePhaseForwarder(WALL,NANO);assertThrows(AssertionError.class,()->unbounded.accept(start("dump",WALL,60001),WALL,NANO));
        var expired=new ReleasePhaseForwarder(WALL,NANO);assertThrows(AssertionError.class,()->expired.accept(start("dump",WALL-30001,30000),WALL,NANO));
        var late=new ReleasePhaseForwarder(WALL,NANO);late.accept(start("dump",WALL,1000),WALL,NANO);
        assertThrows(AssertionError.class,()->late.accept(event("state","completed","phase","dump"),WALL+1001,NANO+TimeUnit.MILLISECONDS.toNanos(1001)));
    }
    @Test void actualNonzeroOwnedCommandJournalRemainsATerminalFailure() throws Exception {
        var folder=probe();var lifecycle=new ReleaseOperationLifecycle(folder);
        try {
            try(var request=lifecycle.enterRequest();var owned=lifecycle.start(builder(folder,"failure"))) {
                assertTrue(owned.process().waitFor(5,TimeUnit.SECONDS));assertEquals(23,owned.process().exitValue());
            }
            var lines=Files.readAllLines(folder.resolve("failure-phases.jsonl"));assertEquals(2,lines.size());
            long now=System.currentTimeMillis(),nano=System.nanoTime();var forwarder=new ReleasePhaseForwarder(now,nano);
            var start=forwarder.accept(ReleaseSnapshotContract.JSON.readTree(lines.getFirst()),now,nano).getFirst();
            var failed=forwarder.accept(ReleaseSnapshotContract.JSON.readTree(lines.getLast()),now,nano).getFirst();
            assertEquals("end",failed.get("event"));assertEquals("failed",failed.get("status"));assertEquals(start.get("id"),failed.get("id"));assertFalse(forwarder.active());forwarder.finish();
        } finally {lifecycle.cancelAndJoin();}
    }
    @Test void sequentialFinitePhasesDoNotAcquireAnAggregateScenarioTimeout() {
        var forwarder=new ReleasePhaseForwarder(WALL,NANO);var ids=new java.util.HashSet<Object>();
        for(int index=0;index<3;index++) {
            long elapsed=index*59000L;String name="phase-"+index;
            var started=forwarder.accept(start(name,WALL+elapsed,59000),WALL+elapsed,NANO+TimeUnit.MILLISECONDS.toNanos(elapsed)).getFirst();assertTrue(ids.add(started.get("id")));
            forwarder.accept(event("state","completed","phase",name),WALL+elapsed+58000,NANO+TimeUnit.MILLISECONDS.toNanos(elapsed+58000));
        }
        assertEquals(3,ids.size());assertFalse(forwarder.active());forwarder.finish();
    }
    @Test void unfinishedOuterPhaseCannotFinishAndMatchingTimeoutIsFailed() {
        var forwarder=new ReleasePhaseForwarder(WALL,NANO);var started=forwarder.accept(start("api-readiness",WALL,30000),WALL,NANO).getFirst();assertThrows(AssertionError.class,forwarder::finish);
        var timeout=forwarder.accept(event("state","timeout","phase","api-readiness"),WALL+100,NANO+TimeUnit.MILLISECONDS.toNanos(100)).getFirst();
        assertEquals("failed",timeout.get("status"));assertEquals(started.get("id"),timeout.get("id"));assertFalse(forwarder.active());forwarder.finish();
    }

    private static JsonNode start(String phase,long at,long budget) {return event("state","started","phase",phase,"at",iso(at),"deadline",iso(at+budget));}
    private static String iso(long millis) {return Instant.ofEpochMilli(millis).toString();}
    private static JsonNode event(Object... fields) {
        var values=new LinkedHashMap<String,Object>();for(int index=0;index<fields.length;index+=2) {values.put(fields[index].toString(),fields[index+1]);}return ReleaseSnapshotContract.JSON.readTree(ReleaseSnapshotContract.JSON.writeValueAsString(values));
    }
    private static Path probe() throws Exception {
        var build=Path.of(System.getProperty("marketplace.build.directory","target")).toAbsolutePath();Files.createDirectories(build);var folder=Files.createDirectory(build.resolve("release-lifecycle-unit-"+UUID.randomUUID()));
        Files.writeString(folder.resolve("probe.cjs"),"""
                const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
                const folder=process.argv[2],mode=process.argv[3];
                if(mode==='failure') {
                  const at=new Date(),phase='actual-command-failure',journal=path.join(folder,'failure-phases.jsonl');
                  fs.writeFileSync(journal,JSON.stringify({phase,state:'started',at:at.toISOString(),deadline:new Date(at.getTime()+10000).toISOString()})+'\\n');
                  fs.appendFileSync(journal,JSON.stringify({phase,state:'failed',at:new Date().toISOString(),exitCode:23})+'\\n');process.exit(23);
                } else if(mode==='sentinel') {
                  fs.writeFileSync(path.join(folder,'sentinel-ready.txt'),String(process.pid));setInterval(()=>{},1000);
                } else {
                  const ready=path.join(folder,'leaf-ready.txt');
                  const leaf=spawn(process.execPath,['-e',"require('node:fs').writeFileSync(process.argv[1],String(process.pid));setInterval(()=>{},1000)",ready],{stdio:'ignore'});
                  const waiting=setInterval(()=>{if(fs.existsSync(ready)){fs.writeFileSync(path.join(folder,'tree-ready.json'),JSON.stringify({root:process.pid,descendant:leaf.pid}));clearInterval(waiting);}},10);
                  setInterval(()=>{},1000);
                }
                """);return folder;
    }
    private static ProcessBuilder builder(Path folder,String mode) {return new ProcessBuilder(node().toString(),folder.resolve("probe.cjs").toString(),folder.toString(),mode).redirectErrorStream(true).redirectOutput(folder.resolve(mode+"-output.txt").toFile());}
    private static Path node() {return Path.of("../.local-tools/node-v24.22.0-win-x64/node.exe").toAbsolutePath().normalize();}
    private static void awaitFile(Path path) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(path)&&System.nanoTime()<deadline) {Thread.sleep(10);}assertTrue(Files.exists(path),"Actual child readiness evidence required: "+path.getFileName());
    }
    private static void terminate(Process process) throws Exception {if(process!=null&&process.isAlive()) {process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS),"Fresh probe process cleanup must complete");}}
    private static void terminate(long pid) throws Exception {
        var handle=ProcessHandle.of(pid);if(handle.isPresent()&&handle.get().isAlive()) {handle.get().destroyForcibly();handle.get().onExit().get(3,TimeUnit.SECONDS);}
    }
}
