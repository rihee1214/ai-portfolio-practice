package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import java.lang.reflect.Method;

/** Monotonic timings; exclusive durations avoid double-counting nested fixture work. */
public final class ContractTiming implements InvocationInterceptor {
    private static final ThreadLocal<ArrayDeque<Scope>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<String> TEST = ThreadLocal.withInitial(() -> "initialization-or-worker");
    private static final java.util.concurrent.atomic.LongAdder EVIDENCE_NANOS = new java.util.concurrent.atomic.LongAdder();

    public static void initialization(long started) {
        long elapsed = System.nanoTime() - started;
        if (!STACK.get().isEmpty()) { STACK.get().peek().children += elapsed; }
        write(Map.of("test", "initialization", "category", "initialization", "operation", "database-and-context-refresh",
                "inclusiveNanos", elapsed, "exclusiveNanos", elapsed, "thread", Thread.currentThread().getName()));
    }

    public static void finishEvidence() {
        // Already included in parent elapsed time; an overlapping diagnostic, never added again.
        write(Map.of("category", "evidence-overlap", "writeNanos", EVIDENCE_NANOS.sum()));
    }

    public static void identity(int port, String url) {
        var file = Path.of(System.getenv("A3_EVIDENCE"), "execution-identity.json");
        var value = Map.of("pid", ProcessHandle.current().pid(), "httpPort", port,
                "database", System.getenv("D2_DB_NAME"), "jdbcUrl", url,
                "media", Path.of(System.getenv("A3_EVIDENCE"), "media").toString());
        try {
            String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
            if (Files.exists(file)) {
                if (!Files.readString(file).equals(json)) { throw new IllegalStateException("Execution scope changed within group"); }
            } else { Files.writeString(file, json, StandardOpenOption.CREATE_NEW); }
        } catch (java.io.IOException failure) { throw new IllegalStateException("Scope identity evidence failed", failure); }
    }

    public static Scope measure(String category, String operation) {
        var scope = new Scope(category, operation);
        STACK.get().push(scope);
        return scope;
    }

    public static boolean awaitProcess(Process process, long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
        try (var scope = measure("process-wait", "waitFor")) { return process.waitFor(timeout, unit); }
    }

    public static final class Scope implements AutoCloseable {
        private final String category;
        private final String operation;
        private final long started = System.nanoTime();
        private long children;
        private Scope(String category, String operation) {
            this.category = category;
            this.operation = operation;
        }
        @Override public void close() {
            long elapsed = System.nanoTime() - started;
            var stack = STACK.get();
            if (stack.pop() != this) { throw new IllegalStateException("Timing scope mismatch"); }
            if (!stack.isEmpty()) { stack.peek().children += elapsed; }
            write(Map.of("test", TEST.get(), "category", category, "operation", operation,
                    "inclusiveNanos", elapsed, "exclusiveNanos", elapsed - children,
                    "thread", Thread.currentThread().getName()));
        }
    }

    private static synchronized void write(Map<String,Object> row) {
        long started = System.nanoTime();
        String evidence = System.getenv("A3_EVIDENCE");
        if (evidence == null) { throw new IllegalStateException("Timing requires fresh evidence"); }
        try {
            Files.writeString(Path.of(evidence, "contract-timing.ndjson"),
                    new tools.jackson.databind.ObjectMapper().writeValueAsString(row) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Timing evidence write failed", failure); }
        finally { EVIDENCE_NANOS.add(System.nanoTime() - started); }
    }

    private void timed(Invocation<Void> invocation, ExtensionContext context) throws Throwable {
        TEST.set(context.getRequiredTestClass().getSimpleName() + "#" + context.getRequiredTestMethod().getName());
        try (var scope = measure("execution", context.getDisplayName())) { invocation.proceed(); }
        finally { TEST.remove(); }
    }

    @Override public void interceptTestMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        timed(invocation, context);
    }

    @Override public void interceptTestTemplateMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        timed(invocation, context);
    }
}
