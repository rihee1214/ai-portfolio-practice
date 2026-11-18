package com.example.marketplace.dbtest;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Read-only observation of the trial database through a connection outside the application pool. */
final class P13DatabaseObservation implements AutoCloseable {
    private static final String ACTIVITY = """
            WITH activity AS MATERIALIZED (
                SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid) AS blockers
                FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'
            )
            SELECT json_build_object('database', current_database(), 'observedAt', clock_timestamp(),
                'activity', COALESCE((SELECT json_agg(a ORDER BY pid) FROM activity a), '[]'::json),
                'locks', COALESCE((SELECT json_agg(l ORDER BY pid, locktype, mode) FROM (
                    SELECT pid, locktype, mode, granted, count(*) AS count FROM pg_locks
                    WHERE pid IN (SELECT pid FROM activity) GROUP BY pid, locktype, mode, granted) l), '[]'::json),
                'databaseStats', (SELECT json_build_object('commits', xact_commit, 'rollbacks', xact_rollback,
                    'blocksRead', blks_read, 'blocksHit', blks_hit, 'deadlocks', deadlocks, 'statsReset', stats_reset)
                    FROM pg_stat_database WHERE datname = current_database()))::jsonb
            """;

    private final Connection connection;
    private final Statement statement;
    private final BufferedWriter output;
    private final ScheduledExecutorService executor;
    private final long origin;
    private volatile Exception failure;
    private int samples;

    P13DatabaseObservation(Path path, long origin) throws Exception {
        this.origin = origin;
        String database = System.getenv("D2_DB_NAME");
        String url = System.getenv("D2_DB_URL");
        String user = System.getenv("D2_DB_USERNAME");
        com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.validate(url, user, database);
        connection = DriverManager.getConnection(url, user, System.getenv("D2_DB_PASSWORD"));
        try {
            com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget.verifyConnection(connection, database);
            connection.setReadOnly(true);
            statement = connection.createStatement();
            statement.setQueryTimeout(2);
            output = Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Exception setupFailure) {
            connection.close();
            throw setupFailure;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("p13-db-observer").factory());
        executor.scheduleWithFixedDelay(this::sample, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void sample() {
        if (failure != null) {
            return;
        }
        long started = System.nanoTime() - origin;
        try (var result = statement.executeQuery(ACTIVITY)) {
            if (!result.next()) {
                throw new IllegalStateException("Missing trial database observation");
            }
            String row = Objects.requireNonNull(result.getString(1));
            if (row.indexOf('\n') >= 0 || row.indexOf('\r') >= 0) {
                throw new IllegalStateException("Trial database sample must occupy one NDJSON line");
            }
            output.write("{\"startedNs\":" + started + ",\"finishedNs\":" + (System.nanoTime() - origin)
                    + ",\"collectorUtc\":\"" + Instant.now() + "\",\"postgres\":" + row + "}");
            output.newLine();
            output.flush();
            samples++;
        } catch (Exception observationFailure) {
            failure = observationFailure;
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        try (connection; statement; output) {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Trial database observer did not stop");
            }
            sample();
            if (failure != null) {
                throw new IllegalStateException("Trial database observation failed", failure);
            }
            if (samples < 2) {
                throw new IllegalStateException("Missing trial database samples");
            }
        }
    }
}
