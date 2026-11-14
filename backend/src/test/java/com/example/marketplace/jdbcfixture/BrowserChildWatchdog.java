package com.example.marketplace.jdbcfixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounds stalled browser phases without imposing a duration on the entire suite. */
public final class BrowserChildWatchdog {
    private BrowserChildWatchdog() { }

    public static void await(Process child, Path progress) throws IOException, InterruptedException {
        var owned = new java.util.LinkedHashSet<ProcessHandle>();
        owned.add(child.toHandle());
        Throwable original = null;
        try {
            recordOwned(progress, child.pid(), child.pid());
            awaitTracked(child, progress, owned);
        }
        catch (IOException | InterruptedException | RuntimeException | Error failure) { original = failure; throw failure; }
        finally {
            try {
                owned.addAll(child.descendants().toList());
                terminate(owned);
            } catch (InterruptedException | RuntimeException | Error cleanupFailure) {
                if (original != null) { original.addSuppressed(cleanupFailure); }
                else { throw cleanupFailure; }
            }
        }
    }

    private static void awaitTracked(Process child, Path progress, java.util.Set<ProcessHandle> owned)
            throws IOException, InterruptedException {
        var state = new State(System.nanoTime());
        var pending = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            for (var descendant : child.descendants().toList()) {
                if (owned.add(descendant)) { recordOwned(progress, child.pid(), descendant.pid()); }
            }
            boolean exited = !child.isAlive();
            if (Files.exists(progress)) {
                try (var input = FileChannel.open(progress, StandardOpenOption.READ)) {
                    require(input.size() >= offset, "progress file was truncated");
                    input.position(offset);
                    var buffer = ByteBuffer.allocate(8192);
                    int count;
                    while ((count = input.read(buffer)) > 0) {
                        offset += count;
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            byte value = buffer.get();
                            if (value == '\n') {
                                String line = StandardCharsets.UTF_8.newDecoder()
                                        .decode(ByteBuffer.wrap(pending.toByteArray())).toString();
                                state.accept(line, System.currentTimeMillis(), System.nanoTime());
                                pending.reset();
                            } else {
                                pending.write(value);
                                require(pending.size() <= 65536, "oversized progress event");
                            }
                        }
                        buffer.clear();
                    }
                }
            }
            state.check(System.nanoTime());
            if (exited) {
                require(pending.size() == 0, "child exited with an incomplete progress event");
                state.finish();
                return;
            }
            child.waitFor(200, TimeUnit.MILLISECONDS);
        }
    }

    /** Terminate only this invocation's child and descendants, preserving their evidence. */
    public static void terminate(Process child) throws InterruptedException {
        var owned = new java.util.ArrayList<>(child.descendants().toList());
        owned.add(child.toHandle());
        terminate(owned);
    }

    private static void terminate(java.util.Collection<ProcessHandle> owned) throws InterruptedException {
        owned.forEach(handle -> { if (handle.isAlive()) { handle.destroyForcibly(); } });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        require(owned.stream().noneMatch(ProcessHandle::isAlive), "owned browser process did not terminate within cleanup budget");
    }

    private static void recordOwned(Path progress, long rootPid, long pid) throws IOException {
        Files.writeString(progress.resolveSibling("browser-owned-processes.ndjson"),
                "{\"rootPid\":" + rootPid + ",\"pid\":" + pid + ",\"observedAt\":" + System.currentTimeMillis() + "}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static final class State {
        private static final Map<String, Long> BUDGETS = Map.of(
                "operation", 10000L, "prepare", 60000L, "cleanup", 10000L, "lifecycle", 60000L);
        private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        private final Map<String, Phase> active = new HashMap<>();
        private final HashSet<String> seen = new HashSet<>();
        private long idleDeadline;
        private boolean finished;
        private boolean failed;

        State(long nowNanos) { idleDeadline = nowNanos + TimeUnit.SECONDS.toNanos(60); }

        void accept(String line, long nowMillis, long nowNanos) throws IOException {
            require(!finished && !line.isBlank(), "empty event or event after completion");
            check(nowNanos);
            var row = mapper.readTree(line);
            require(row != null && row.isObject(), "progress must be a JSON object");
            String event = text(row, "event"), id = text(row, "id"), kind = text(row, "kind"), name = text(row, "name");
            try { require(java.util.UUID.fromString(id).toString().equals(id), "noncanonical progress id"); }
            catch (IllegalArgumentException failure) { throw new AssertionError("Invalid browser progress id", failure); }
            require(BUDGETS.containsKey(kind), "unknown phase kind: " + kind);
            long started = number(row, "startedAt"), deadline = number(row, "deadlineAt"), pid = number(row, "pid");
            require(started > 0 && started <= nowMillis + 1000 && pid > 0, "invalid phase clock or pid");
            require(deadline > started && deadline - started <= BUDGETS.get(kind), "phase budget exceeded: " + name);
            if (event.equals("start")) {
                require(seen.add(id), "duplicate phase start: " + id);
                require(!row.has("endedAt") && !row.has("status"), "start contains completion fields");
                long remaining = deadline - nowMillis + 5000;
                require(remaining > 0, "stale phase start: " + name);
                active.put(id, new Phase(kind, name, started, deadline, pid,
                        nowNanos + TimeUnit.MILLISECONDS.toNanos(remaining)));
            } else if (event.equals("end")) {
                var phase = active.remove(id);
                require(phase != null && phase.kind().equals(kind) && phase.name().equals(name)
                        && phase.started() == started && phase.deadline() == deadline && phase.pid() == pid,
                        "end has no matching immutable start: " + id);
                long ended = number(row, "endedAt");
                String status = text(row, "status");
                require(ended >= started && ended <= nowMillis + 1000, "invalid phase end time");
                require(status.equals("passed") || status.equals("failed"), "unknown phase result");
                require(!status.equals("passed") || ended <= deadline, "late phase cannot pass: " + name);
                failed |= status.equals("failed");
                if (active.isEmpty()) { idleDeadline = nowNanos + TimeUnit.SECONDS.toNanos(30); }
            } else { throw new AssertionError("Unknown browser progress event: " + event); }
        }

        void check(long nowNanos) {
            if (active.isEmpty()) {
                require(nowNanos <= idleDeadline, seen.isEmpty() ? "browser bootstrap stalled" : "browser progress/exit gap stalled");
            } else {
                active.values().forEach(phase -> require(nowNanos <= phase.watchdogDeadline(),
                        "browser phase stalled: " + phase.kind() + ": " + phase.name()));
            }
        }

        void finish() {
            require(!seen.isEmpty() && active.isEmpty(), "browser exited without complete phase evidence");
            require(!failed, "browser exited with a failed phase");
            finished = true;
        }

        private static String text(tools.jackson.databind.JsonNode row, String field) {
            var value = row.get(field);
            require(value != null && value.isString() && !value.asText().isBlank(), "missing text field: " + field);
            return value.asText();
        }

        private static long number(tools.jackson.databind.JsonNode row, String field) {
            var value = row.get(field);
            require(value != null && value.isIntegralNumber() && value.canConvertToLong(), "missing integer field: " + field);
            return value.asLong();
        }

        private record Phase(String kind, String name, long started, long deadline, long pid, long watchdogDeadline) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError("Browser watchdog: " + message); }
    }
}
