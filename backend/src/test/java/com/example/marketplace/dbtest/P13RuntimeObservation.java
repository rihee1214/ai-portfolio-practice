package com.example.marketplace.dbtest;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import tools.jackson.databind.json.JsonMapper;

/** P13-only runtime checkpoints. The worker entry delegates all business work to the existing probe. */
public final class P13RuntimeObservation {
    private P13RuntimeObservation() {
    }

    static Map<String, Object> capture(String phase, String role, String database, DataSource source) {
        long started = System.nanoTime();
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("phase", phase);
        snapshot.put("role", role);
        snapshot.put("database", database);
        snapshot.put("pid", ProcessHandle.current().pid());
        snapshot.put("capturedAt", Instant.now().toString());
        snapshot.put("monotonicNanos", started);
        snapshot.put("pool", source == null ? Map.of("available", false) : pool(source, database));
        snapshot.put("jvm", jvm());
        snapshot.put("captureNanos", System.nanoTime() - started);
        return snapshot;
    }

    private static Map<String, Object> pool(DataSource source, String database) {
        HikariDataSource hikari;
        try {
            hikari = source.unwrap(HikariDataSource.class);
        } catch (SQLException failure) {
            throw new IllegalStateException("P13 observation requires a Hikari DataSource", failure);
        }
        var properties = Driver.parseURL(hikari.getJdbcUrl(), null);
        if (properties == null || !database.equals(PGProperty.PG_DBNAME.getOrDefault(properties))) {
            throw new IllegalStateException("P13 observation DataSource targets a different database");
        }
        var pool = hikari.getHikariPoolMXBean();
        if (pool == null) {
            throw new IllegalStateException("P13 observation requires an initialized Hikari pool");
        }
        return Map.of(
                "available", true,
                "database", PGProperty.PG_DBNAME.getOrDefault(properties),
                "name", hikari.getPoolName(),
                "maximum", hikari.getMaximumPoolSize(),
                "active", pool.getActiveConnections(),
                "idle", pool.getIdleConnections(),
                "total", pool.getTotalConnections(),
                "awaiting", pool.getThreadsAwaitingConnection(),
                "connectionTimeoutMillis", hikari.getConnectionTimeout());
    }

    private static Map<String, Object> jvm() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var memory = ManagementFactory.getMemoryMXBean();
        var threads = ManagementFactory.getThreadMXBean();
        var compilation = ManagementFactory.getCompilationMXBean();
        var jit = new LinkedHashMap<String, Object>();
        jit.put("timeMonitoringSupported", compilation != null && compilation.isCompilationTimeMonitoringSupported());
        if (compilation != null) {
            jit.put("name", compilation.getName());
            if (compilation.isCompilationTimeMonitoringSupported()) {
                jit.put("totalCompilationMillis", compilation.getTotalCompilationTime());
            }
        }
        var jvm = new LinkedHashMap<String, Object>();
        jvm.put("startEpochMillis", runtime.getStartTime());
        jvm.put("uptimeMillis", runtime.getUptime());
        jvm.put("javaVersion", Runtime.version().toString());
        jvm.put("vmName", runtime.getVmName());
        jvm.put("vmVersion", runtime.getVmVersion());
        jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        jvm.put("heap", memory(memory.getHeapMemoryUsage()));
        jvm.put("nonHeap", memory(memory.getNonHeapMemoryUsage()));
        jvm.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(P13RuntimeObservation::collector).toList());
        jvm.put("jit", jit);
        jvm.put("threads", Map.of(
                "live", threads.getThreadCount(),
                "daemon", threads.getDaemonThreadCount(),
                "peak", threads.getPeakThreadCount(),
                "totalStarted", threads.getTotalStartedThreadCount()));
        return jvm;
    }

    private static Map<String, Long> memory(MemoryUsage usage) {
        return Map.of("usedBytes", usage.getUsed(), "committedBytes", usage.getCommitted(),
                "maxBytes", usage.getMax(), "initialBytes", usage.getInit());
    }

    private static Map<String, Object> collector(GarbageCollectorMXBean gc) {
        return Map.of("name", gc.getName(), "count", gc.getCollectionCount(), "millis", gc.getCollectionTime());
    }

    static void write(Path path, Map<String, Object> snapshot) throws IOException {
        Path staging = path.resolveSibling(path.getFileName() + ".partial");
        if (Files.exists(path)) {
            throw new IOException("P13 observation file already exists");
        }
        Files.writeString(staging, JsonMapper.builder().build().writeValueAsString(snapshot),
                StandardOpenOption.CREATE_NEW);
        Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) throws Exception {
        String run = System.getenv("P13_REFUND_EXPERIMENT");
        if (args.length != 4 || run == null || run.isBlank()) {
            throw new IllegalArgumentException("P13 worker requires its experiment run and four probe arguments");
        }
        Path root = Path.of(System.getenv("A3_EVIDENCE")).toRealPath();
        Path marker = Path.of(args[2]);
        Path directory = marker.toAbsolutePath().getParent().toRealPath();
        if (!directory.equals(root.resolve("processes"))) {
            throw new IllegalArgumentException("P13 worker evidence must stay in its experiment process directory");
        }
        String name = marker.getFileName().toString();
        boolean first = name.equals("first-effect") && args[3].equals("PREPARED");
        if (!first && !(name.equals("restart-effect") && args[3].equals("NORMAL"))) {
            throw new IllegalArgumentException("Unexpected P13 worker marker or mode");
        }
        var listener = new WorkerObservation(directory.resolve(name), first, run, System.getenv("D2_DB_NAME"));
        SpringApplication.withHook(application -> listener, () -> {
            RefundWorkerProbe.main(args);
            return null;
        });
    }

    private record WorkerObservation(Path marker, boolean first, String run, String database)
            implements SpringApplicationRunListener {
        @Override
        public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
            var source = context.getBean(DataSource.class);
            save("ready", source, Map.of("springReadyMillis", timeTaken.toMillis()));
            if (first) {
                Thread.ofPlatform().daemon().name("p13-prepared-observation")
                        .start(() -> waitForPrepared(source));
            } else {
                context.addApplicationListener(event -> {
                    if (event instanceof ContextClosedEvent closing && closing.getApplicationContext() == context
                            && Files.isRegularFile(marker.resolveSibling(marker.getFileName() + "-completed"))) {
                        save("completed", source, Map.of());
                    }
                });
            }
        }

        private void waitForPrepared(DataSource source) {
            try {
                while (!Files.isRegularFile(marker)
                        || !Files.readString(marker).startsWith("refund prepared before external submit;pid=")) {
                    Thread.sleep(5);
                }
                save("prepared", source, Map.of());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private void save(String phase, DataSource source, Map<String, Object> details) {
            var snapshot = capture(phase, first ? "first-worker" : "second-worker", database, source);
            snapshot.put("experimentRun", run);
            snapshot.putAll(details);
            try {
                write(marker.resolveSibling(marker.getFileName() + "-runtime-" + phase + ".json"), snapshot);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
