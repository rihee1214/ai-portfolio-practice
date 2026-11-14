package com.example.marketplace.jdbcfixture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserChildWatchdogTest {
    private static final long EPOCH = 1700000000000L;
    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

    @Test
    void accumulatedSuiteDurationCanExceed390SecondsWithIndividualOperationsWithinTen() throws Exception {
        var state = new BrowserChildWatchdog.State(0);
        long elapsed = 0;
        for (int index = 0; index < 50; index++) {
            String id = UUID.randomUUID().toString();
            state.accept(event("start", id, "operation", EPOCH + elapsed, 10000, null, null), EPOCH + elapsed, nanos(elapsed));
            elapsed += 9000;
            state.accept(event("end", id, "operation", EPOCH + elapsed - 9000, 10000, EPOCH + elapsed, "passed"), EPOCH + elapsed, nanos(elapsed));
            state.check(nanos(elapsed));
        }
        assertDoesNotThrow(state::finish);
    }

    @Test
    void lateOperationCannotPassAndUnfinishedOperationHasFiniteMonotonicWatchdog() throws Exception {
        var state = new BrowserChildWatchdog.State(0);
        String id = UUID.randomUUID().toString();
        state.accept(event("start", id, "operation", EPOCH, 10000, null, null), EPOCH, 0);
        assertDoesNotThrow(() -> state.check(nanos(15000)));
        assertThrows(AssertionError.class, () -> state.check(nanos(15001)));
        assertThrows(AssertionError.class, () -> state.accept(event("end", id, "operation", EPOCH, 10000, EPOCH + 10001, "passed"), EPOCH + 10001, nanos(10001)));
    }

    @Test
    void concurrentPreparationDoesNotRenewOriginalOperationDeadline() throws Exception {
        var state = new BrowserChildWatchdog.State(0);
        state.accept(event("start", UUID.randomUUID().toString(), "operation", EPOCH, 10000, null, null), EPOCH, 0);
        for (int index = 1; index < 15; index++) {
            long elapsed = index * 1000L;
            String id = UUID.randomUUID().toString();
            state.accept(event("start", id, "prepare", EPOCH + elapsed, 60000, null, null), EPOCH + elapsed, nanos(elapsed));
            state.accept(event("end", id, "prepare", EPOCH + elapsed, 60000, EPOCH + elapsed, "passed"), EPOCH + elapsed, nanos(elapsed));
        }
        assertThrows(AssertionError.class, () -> state.check(nanos(15001)));
    }

    @Test
    void bootstrapIdleCleanupAndReportingHaveFiniteBudgets() throws Exception {
        assertThrows(AssertionError.class, () -> new BrowserChildWatchdog.State(0).check(nanos(60001)));
        for (String kind : java.util.List.of("cleanup", "lifecycle", "prepare")) {
            var state = new BrowserChildWatchdog.State(0);
            long budget = kind.equals("cleanup") ? 10000 : 60000;
            state.accept(event("start", UUID.randomUUID().toString(), kind, EPOCH, budget, null, null), EPOCH, 0);
            assertThrows(AssertionError.class, () -> state.check(nanos(budget + 5001)));
        }
        var state = new BrowserChildWatchdog.State(0);
        String id = UUID.randomUUID().toString();
        state.accept(event("start", id, "operation", EPOCH, 10000, null, null), EPOCH, 0);
        state.accept(event("end", id, "operation", EPOCH, 10000, EPOCH + 1, "passed"), EPOCH + 1, nanos(1));
        assertThrows(AssertionError.class, () -> state.check(nanos(30002)));
    }

    @Test
    void malformedDuplicateUnknownAndUnpairedEventsFailClosed() throws Exception {
        String id = UUID.randomUUID().toString();
        var state = new BrowserChildWatchdog.State(0);
        assertThrows(AssertionError.class, () -> state.accept("", EPOCH, 0));
        assertThrows(RuntimeException.class, () -> state.accept("{", EPOCH, 0));
        assertThrows(AssertionError.class, () -> state.accept(event("start", id, "heartbeat", EPOCH, 1, null, null), EPOCH, 0));
        assertThrows(AssertionError.class, () -> state.accept(event("end", id, "operation", EPOCH, 10000, EPOCH, "passed"), EPOCH, 0));
        state.accept(event("start", id, "operation", EPOCH, 10000, null, null), EPOCH, 0);
        assertThrows(AssertionError.class, () -> state.accept(event("start", id, "operation", EPOCH, 10000, null, null), EPOCH, 0));
        assertThrows(AssertionError.class, state::finish);
    }

    @Test
    void immutableDeadlinesStaleEventsAndCompletionCannotBeReopened() throws Exception {
        String id = UUID.randomUUID().toString();
        var state = new BrowserChildWatchdog.State(0);
        assertThrows(AssertionError.class, () -> state.accept(event("start", id, "operation", EPOCH, 10001, null, null), EPOCH, 0));
        assertThrows(AssertionError.class, () -> state.accept(event("start", UUID.randomUUID().toString(), "operation", EPOCH, 10000, null, null), EPOCH + 15001, 0));
        state.accept(event("start", id, "operation", EPOCH, 10000, null, null), EPOCH, 0);
        assertThrows(AssertionError.class, () -> state.accept(event("end", id, "operation", EPOCH, 9999, EPOCH, "passed"), EPOCH, 0));
        var completed = new BrowserChildWatchdog.State(0);
        String finalId = UUID.randomUUID().toString();
        completed.accept(event("start", finalId, "operation", EPOCH, 10000, null, null), EPOCH, 0);
        completed.accept(event("end", finalId, "operation", EPOCH, 10000, EPOCH, "passed"), EPOCH, 0);
        completed.finish();
        assertThrows(AssertionError.class, () -> completed.accept(event("start", UUID.randomUUID().toString(), "operation", EPOCH, 10000, null, null), EPOCH, 0));
    }

    @Test
    void failedPhasesCannotBeReportedAsSuccessfulChildAndLateEventsCannotRenewIdleDeadline() throws Exception {
        String id = UUID.randomUUID().toString();
        var state = new BrowserChildWatchdog.State(0);
        state.accept(event("start", id, "operation", EPOCH, 10000, null, null), EPOCH, 0);
        state.accept(event("end", id, "operation", EPOCH, 10000, EPOCH + 10001, "failed"), EPOCH + 10001, nanos(10001));
        assertThrows(AssertionError.class, state::finish);
        assertThrows(AssertionError.class, () -> state.accept(event("start", UUID.randomUUID().toString(), "operation", EPOCH + 40002, 10000, null, null), EPOCH + 40002, nanos(40002)));
    }

    @Test
    void realChildProgressAllowsPartialWritesButRejectsMissingOrUnfinishedEvidence() throws Exception {
        for (String mode : java.util.List.of("complete", "partial", "empty")) {
            var folder = Files.createDirectory(Path.of("../.local-tools/browser-watchdog-" + UUID.randomUUID()).toAbsolutePath());
            var progress = folder.resolve("browser-progress.ndjson");
            var args = folder.resolve("child.args");
            // This protocol-only child uses JDK classes. Keep its boot path independent
            // of the application's dependency scan; long classpaths have their own guard regression.
            String cp = Path.of(ProgressChild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString().replace('\\', '/');
            Files.writeString(args, "-cp\n\"" + cp + "\"\n" + ProgressChild.class.getName() + "\n" + mode + "\n\"" + progress.toString().replace('\\', '/') + "\"\n");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "@" + args)
                    .redirectErrorStream(true).redirectOutput(folder.resolve("child.txt").toFile()).start();
            try {
                if (mode.equals("complete")) { assertDoesNotThrow(() -> BrowserChildWatchdog.await(child, progress)); }
                else { assertThrows(AssertionError.class, () -> BrowserChildWatchdog.await(child, progress)); }
            } finally { BrowserChildWatchdog.terminate(child); }
            assertFalse(child.isAlive());
            Files.writeString(folder.resolve("termination-result.json"), "{\"childAliveAfterOwnedCleanup\":false}\n");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void actualHungChildDeadlineExpiresAndOwnedCleanupTerminatesIt() throws Exception {
        var probe = nodeProbe("hang");
        try {
            var failure = assertThrows(AssertionError.class, () -> BrowserChildWatchdog.await(probe.child(), probe.progress()));
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("browser phase stalled: operation"), failure.getMessage());
            var start = MAPPER.readTree(Files.readString(probe.progress()));
            org.junit.jupiter.api.Assertions.assertEquals(100, start.get("deadlineAt").asLong() - start.get("startedAt").asLong());
            long elapsed = System.currentTimeMillis() - start.get("startedAt").asLong();
            org.junit.jupiter.api.Assertions.assertTrue(elapsed >= 5100 && elapsed < 8000,
                    "actual phase plus unchanged five-second recovery grace must be finite: " + elapsed);
            assertFalse(probe.child().isAlive(), "await's owned cleanup must terminate the unresponsive child");
            Files.writeString(probe.folder().resolve("watchdog-result.json"), MAPPER.writeValueAsString(Map.of(
                    "phaseBudgetMillis", 100, "watchdogElapsedMillis", elapsed, "reason", failure.getMessage(), "childAliveAfterOwnedCleanup", false)));
        } finally { BrowserChildWatchdog.terminate(probe.child()); }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void observedDescendantIsReclaimedAfterParentExitsAndOriginalFailureSurvives() throws Exception {
        var probe = nodeProbe("orphan");
        ProcessHandle descendant = null;
        try {
            var failure = assertThrows(AssertionError.class, () -> BrowserChildWatchdog.await(probe.child(), probe.progress()));
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("browser exited without complete phase evidence"), failure.getMessage());
            var identity = MAPPER.readTree(Files.readString(probe.folder().resolve("descendant.json")));
            org.junit.jupiter.api.Assertions.assertEquals(probe.child().pid(), identity.get("parent").asLong());
            long pid = identity.get("pid").asLong();
            var observation = Files.readAllLines(probe.folder().resolve("browser-owned-processes.ndjson")).stream()
                    .map(MAPPER::readTree).filter(row -> row.get("pid").asLong() == pid).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(probe.child().pid(), observation.get("rootPid").asLong());
            var barrier = MAPPER.readTree(Files.readString(probe.folder().resolve("parent-exit-barrier.json")));
            org.junit.jupiter.api.Assertions.assertEquals(observation.get("observedAt").asLong(), barrier.get("observedAt").asLong());
            org.junit.jupiter.api.Assertions.assertTrue(barrier.get("exitRequestedAt").asLong() >= observation.get("observedAt").asLong());
            descendant = ProcessHandle.of(pid).orElse(null);
            assertFalse(descendant != null && descendant.isAlive(), "a descendant observed before its parent exited must be reclaimed");
            assertFalse(probe.child().isAlive());
            Files.writeString(probe.folder().resolve("watchdog-result.json"), MAPPER.writeValueAsString(Map.of(
                    "reason", failure.getMessage(), "observedDescendantPid", pid, "descendantAliveAfterParentExit", false,
                    "parentExitCode", probe.child().exitValue())));
        } finally {
            BrowserChildWatchdog.terminate(probe.child());
            // Only the exact handle whose origin the probe recorded may need fallback cleanup on a failed assertion.
            if (descendant != null && descendant.isAlive()) { descendant.destroyForcibly(); }
        }
        originalFailureRetainsSuppressedCleanupFailure(probe.folder());
    }

    private static void originalFailureRetainsSuppressedCleanupFailure(Path folder) {
        // In-memory Process protocol stub: no PID lookup, attach, signal or real process.
        var handle = (ProcessHandle) java.lang.reflect.Proxy.newProxyInstance(ProcessHandle.class.getClassLoader(),
                new Class<?>[]{ProcessHandle.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "isAlive" -> true;
                    case "destroyForcibly" -> throw new IllegalStateException("controlled cleanup failure");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var child = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
            @Override public boolean isAlive() { return false; }
            @Override public long pid() { return 1; }
            @Override public ProcessHandle toHandle() { return handle; }
            @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
        };
        var failure = assertThrows(AssertionError.class, () -> BrowserChildWatchdog.await(child, folder.resolve("absent-progress.ndjson")));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("browser exited without complete phase evidence"));
        org.junit.jupiter.api.Assertions.assertEquals(1, failure.getSuppressed().length);
        org.junit.jupiter.api.Assertions.assertEquals("controlled cleanup failure", failure.getSuppressed()[0].getMessage());
    }

    private static NodeProbe nodeProbe(String mode) throws Exception {
        var folder = Files.createDirectory(Path.of("../.local-tools/browser-watchdog-node-" + UUID.randomUUID()).toAbsolutePath());
        var progress = folder.resolve("browser-progress.ndjson");
        var script = folder.resolve("probe.cjs");
        Files.writeString(script, """
                const fs = require('node:fs'), path = require('node:path'), { spawn } = require('node:child_process');
                const progress = process.argv[3], mode = process.argv[2];
                const start = () => {
                  const now = Date.now();
                  fs.writeFileSync(progress, JSON.stringify({ event:'start', id:require('node:crypto').randomUUID(),
                    kind:mode==='hang'?'operation':'prepare', name:'actual unresponsive child', startedAt:now,
                    deadlineAt:now+(mode==='hang'?100:60000), pid:process.pid })+'\\n');
                };
                if (mode==='hang') { start(); setInterval(()=>{},1000); }
                else {
                  const ready = path.join(path.dirname(progress),'descendant-ready.txt');
                  const descendant = spawn(process.execPath,['-e',
                    "require('node:fs').writeFileSync(process.argv[1],String(process.pid));setInterval(()=>{},1000)",ready],{stdio:'ignore'});
                  fs.writeFileSync(path.join(path.dirname(progress),'descendant.json'),JSON.stringify({parent:process.pid,pid:descendant.pid}));
                  const ledger=path.join(path.dirname(progress),'browser-owned-processes.ndjson');
                  const readiness = setInterval(()=>{
                    if (!fs.existsSync(ready)||!fs.existsSync(ledger)) return;
                    const owned=fs.readFileSync(ledger,'utf8').trim().split('\\n').filter(Boolean).map(line=>JSON.parse(line))
                      .find(row=>row.pid===descendant.pid&&row.rootPid===process.pid);
                    if (owned) { clearInterval(readiness); start();
                      fs.writeFileSync(path.join(path.dirname(progress),'parent-exit-barrier.json'),JSON.stringify({observedAt:owned.observedAt,exitRequestedAt:Date.now()}));
                      process.exit(23); }
                  },20);
                }
                """);
        var child = new ProcessBuilder(Path.of("../.local-tools/node-v24.22.0-win-x64/node.exe").toAbsolutePath().toString(),
                script.toString(), mode, progress.toString()).redirectErrorStream(true).redirectOutput(folder.resolve("child.txt").toFile()).start();
        return new NodeProbe(child, folder, progress);
    }

    private record NodeProbe(Process child, Path folder, Path progress) { }

    /** The child has no Jackson/JUnit initialization that could consume the tiny probe deadline. */
    public static final class ProgressChild {
        private ProgressChild() { }

        public static void main(String[] args) throws Exception {
            if (args[0].equals("empty")) { return; }
            Path progress = Path.of(args[1]);
            String id = UUID.randomUUID().toString();
            boolean hang = args[0].equals("hang");
            long now = System.currentTimeMillis(), deadline = now + (hang ? 100 : 60000);
            String fields = "\"id\":\"" + id + "\",\"kind\":\"" + (hang ? "operation" : "prepare")
                    + "\",\"name\":\"test phase\",\"startedAt\":" + now + ",\"deadlineAt\":" + deadline + ",\"pid\":" + ProcessHandle.current().pid();
            String start = "{\"event\":\"start\"," + fields + "}\n";
            if (hang) { Files.writeString(progress, start); Thread.sleep(60000); return; }
            Files.writeString(progress, start.substring(0, start.length() / 2));
            if (args[0].equals("partial")) { return; }
            Thread.sleep(25);
            Files.writeString(progress, start.substring(start.length() / 2), StandardOpenOption.APPEND);
            Files.writeString(progress, "{\"event\":\"end\"," + fields + ",\"endedAt\":" + System.currentTimeMillis()
                    + ",\"status\":\"passed\"}\n", StandardOpenOption.APPEND);
        }
    }

    private static String event(String event, String id, String kind, long started, long budget, Long ended, String status) throws Exception {
        var row = new java.util.HashMap<String, Object>(Map.of("event", event, "id", id, "kind", kind,
                "name", "test phase", "startedAt", started, "deadlineAt", started + budget, "pid", 1));
        if (ended != null) { row.put("endedAt", ended); row.put("status", status); }
        return MAPPER.writeValueAsString(row);
    }

    private static long nanos(long millis) { return TimeUnit.MILLISECONDS.toNanos(millis); }
}
