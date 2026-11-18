package com.example.marketplace.dbtest;

import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.api.ReturnFinances;
import com.example.marketplace.claim.api.ReturnInspections;
import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.fulfillment.api.Shipments;
import com.example.marketplace.platform.api.Jobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicitly selected offline experiment. The regular process-recovery group retains its existing workload. */
@TestPropertySource(properties = "marketplace.phone.mode=disabled")
class P13ReturnRefundExperiment extends PostgresContractFixture {
    private static final String SUBMIT = "REFUND_SUBMIT.v1";
    private static final String QUERY = "REFUND_QUERY_DUE.v1";
    private static final String RESULT = "REFUND_RESULT_RECORDED.v1";
    private static final String RESUME = "RETURN_FINANCE_RESUME.v1";
    private static final long REVISION = 3L;
    private static final long USER_COMMAND_LIMIT_NS = TimeUnit.SECONDS.toNanos(10);
    private static final String CONSENT_KEY = "child-prepared";
    private static final String WORKER_JVM_MODE = "P13_REFUND_WORKER_JVM_MODE";
    private static final String TIER1_ARGUMENT = "-XX:TieredStopAtLevel=1";
    private static final List<String> STARTING_TABLES = List.of("identity_user", "purchase_order", "order_item",
            "seller_order", "shipment", "shipment_unit", "payment_attempt", "payment_refund_attempt",
            "payment_refund_budget", "claim_return_request", "claim_fee_quote", "claim_fee_assessment",
            "claim_fee_bucket", "order_unit_hold", "inventory_balance", "coupon_reservation", "job",
            "job_claim_fact", "outbox_event");
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private final long origin = System.nanoTime();

    @Test
    void measurePreparedCrashAndSameAttemptRecovery() throws Exception {
        String trial = System.getenv("P13_REFUND_EXPERIMENT");
        assertTrue(trial != null && trial.matches("warmup|run-[1-9][0-9]*"), "Explicit P13 trial required");
        String workerJvmMode = System.getenv(WORKER_JVM_MODE);
        assertTrue("baseline".equals(workerJvmMode) || "tier1".equals(workerJvmMode), "Explicit worker JVM mode required");
        String database = System.getenv("D2_DB_NAME");
        Path evidence = Path.of(System.getenv("A3_EVIDENCE"));
        Path processes = Files.createDirectory(evidence.resolve("processes"));
        var report = new LinkedHashMap<String, Object>();
        report.put("trial", trial);
        report.put("workerJvmMode", workerJvmMode);
        report.put("database", database);
        report.put("media", evidence.resolve("media").toString());
        report.put("timeline", timeline);
        report.put("status", "failed");
        report.put("schemaVersion", 1);
        try {
            assertEquals(List.of(), P13RuntimeObservation.tieredStopAtLevelArguments(), "Parent JVM must keep default tiering");
            assertEquals(4, P13RuntimeObservation.tieredStopAtLevel(), "Parent JVM must retain the effective default tier");
            mark("fixture-started");
            var fixture = prepareReturn(report);
            mark("fixture-ready");
            assertTrue(Files.isDirectory(evidence.resolve("media")), "Fixture must use its private media path");
            assertEquals(database, jdbc.getJdbcTemplate().queryForObject("SELECT current_database()", String.class));
            report.put("startingScale", startingScale());
            var inspections = new ArrayList<Map<String, Object>>();
            report.put("parentRuntime", inspections);
            inspections.add(P13RuntimeObservation.capture("before-quote", "parent", database, source));
            try (var observer = new P13DatabaseObservation(evidence.resolve("postgres-samples.ndjson"), origin)) {
                runScenario(fixture, processes, report);
            }
            inspections.add(P13RuntimeObservation.capture("after-outcome", "parent", database, source));
            report.put("status", "passed");
        } finally {
            P13RuntimeObservation.write(evidence.resolve("trial.json"), report);
        }
    }

    private PhysicalReturn prepareReturn(Map<String, Object> report) throws Exception {
        var parties = returnParties("d-child-p");
        var order = parties.buyer().order();
        var lines = order.quote().stores().getFirst().lines();
        var policyLines = lines.stream().filter(line -> "3000".equals(line.returnPolicy().amount())).toList();
        assertEquals(1, policyLines.size(), "Select the covered order-time fee policy independently of generated item IDs");
        var sku = policyLines.getFirst().skuId();
        var item = order.sellers().getFirst().items().stream().filter(candidate -> sku.equals(candidate.skuId())).findFirst().orElseThrow();
        var shipment = parties.shipment();
        var target = shipment.units().stream().filter(unit -> item.id().equals(unit.orderItemId()) && unit.ordinal() == 0).findFirst().orElseThrow();
        var singleTarget = new Shipments.View(shipment.id(), shipment.sellerOrderId(), shipment.state(), shipment.version(), shipment.provider(),
                shipment.items(), shipment.events(), List.of(target), shipment.tracking(), shipment.trackingHistory(), shipment.exceptionStatus(),
                shipment.progressEpoch(), shipment.deliveredAt());
        var fixture = physicalReturn(new ReturnParties(parties.buyer(), parties.seller(), singleTarget), 1, true);
        var unit = fixture.accepted().units().getFirst();
        assertEquals(target.orderItemId(), unit.itemId());
        assertEquals(target.ordinal(), unit.ordinal());
        var inspected = context.getBean(ReturnInspections.class).inspect(fixture.accepted().returnId(), shipmentAuthority(parties.seller()),
                new ReturnInspections.Command("final-quote-inspect", 2L, List.of(new ReturnInspections.Unit(unit.itemId(), unit.ordinal(),
                        ReturnInspections.Condition.SALEABLE, Returns.Responsibility.BUYER)), "최종 견적 전 실제 검수/책임 제안"));
        assertEquals("AWAITING_CONSENT", inspected.state());
        assertEquals(REVISION, inspected.version());
        report.put("workload", Map.of("paymentMode", order.mode(), "orderQuoteLines", lines.size(), "shippedUnits", shipment.units().size(),
                "selectedItem", item.id(), "selectedOrdinal", target.ordinal(), "selectedOriginalPolicyFee", "3000",
                "inspectedReturnRevision", inspected.version(), "responsibility", "BUYER", "condition", "SALEABLE"));
        return fixture;
    }

    private void runScenario(PhysicalReturn fixture, Path processes, Map<String, Object> report) throws Exception {
        var api = context.getBean(ReturnFinances.class);
        var buyer = fixture.parties().buyer();
        var authority = shipmentAuthority(buyer);
        String number = buyer.order().orderNumber();
        UUID returnId = fixture.accepted().returnId();
        long userStarted = mark("quote-started");
        var quoted = api.quote(number, returnId, authority, REVISION);
        mark("quote-returned");
        var quote = quoted.feeQuote();
        assertEquals(returnId, quoted.returnId());
        assertEquals(REVISION, quoted.returnRevision());
        assertEquals(1, quote.units().size());
        var command = new ReturnFinances.Consent(CONSENT_KEY, REVISION, quote.id(), quote.fingerprint(), true, false);
        mark("consent-started");
        var accepted = api.consent(number, returnId, authority, command);
        mark("consent-returned");
        UUID attempt = accepted.refundAttemptId();
        UUID jobId = jobId(SUBMIT, attempt);
        var jobs = context.getBean(Jobs.class);
        var before = jobs.find(jobId).orElseThrow();
        assertEquals("READY", before.status());
        assertEquals(0, before.attempts());
        long userChecked = mark("user-command-verified");
        report.put("userCommandDeadline", Map.of("limitNs", USER_COMMAND_LIMIT_NS, "elapsedNs", userChecked - userStarted,
                "from", "quote-started", "through", "user-command-verified"));
        assertTrue(userChecked - userStarted <= USER_COMMAND_LIMIT_NS,
                "The shared quote and consent deadline includes locating its durable job");
        report.put("identities", Map.of("orderId", buyer.order().id(), "returnId", returnId, "holdId", fixture.accepted().holdId(),
                "quoteId", quote.id(), "quoteFingerprint", quote.fingerprint(), "assessmentId", accepted.assessmentId(),
                "refundAttemptId", attempt, "jobId", jobId));
        Instant firstClock = before.nextRunAt().plusSeconds(1);
        var stopped = killAndRestart(processes, jobs, jobId, attempt, firstClock, report);

        UUID queryId = jdbc.queryForObject("""
                SELECT j.id FROM marketplace.job j JOIN marketplace.payment_refund_query_slot s ON s.job_id = j.id
                WHERE j.type = :type AND j.cause_key = :attempt AND s.attempt_id = :id AND s.slot = 1 AND j.status = 'READY'
                """, Map.of("type", QUERY, "attempt", attempt.toString(), "id", attempt), UUID.class);
        var query = jobs.find(queryId).orElseThrow();
        report.put("queryClock", Map.of("scheduledAt", query.nextRunAt().toString(),
                "injectedAt", query.nextRunAt().plusSeconds(1).toString()));
        mark("query-started");
        runScheduledRefundQuery(attempt);
        mark("query-completed");
        mark("result-consumer-started");
        runCancellationJob(RESULT, attempt);
        mark("result-consumer-completed");
        mark("return-consumer-started");
        runCancellationJob(RESUME, returnId);
        mark("return-consumer-completed");
        var outcome = outcome(returnId, attempt);
        mark("outcome-observed");
        var claims = jdbc.queryForList("""
                SELECT job_id, attempt, owner_token, previous_owner, previous_attempt, eligibility_kind,
                       claimed_at::text, required_at::text, initial_lease_until::text,
                       (claimed_at >= required_at) AS eligible,
                       (claimed_at = required_at + interval '1 microsecond') AS one_microsecond_after_lease
                FROM marketplace.job_claim_fact WHERE job_id = :id ORDER BY attempt
                """, Map.of("id", jobId));
        var consumers = jdbc.queryForList("""
                SELECT j.id, j.type, j.cause_key, j.status, j.attempts, c.slot, c.decision FROM marketplace.job j
                LEFT JOIN marketplace.payment_refund_query_consumption c ON c.job_id = j.id
                WHERE j.id = :query OR (j.type = 'REFUND_RESULT_RECORDED.v1' AND j.cause_key = :attempt)
                    OR (j.type = 'RETURN_FINANCE_RESUME.v1' AND j.cause_key = :return)
                ORDER BY j.type
                """, Map.of("query", queryId, "attempt", attempt.toString(), "return", returnId.toString()));
        report.put("outcome", outcome);
        report.put("claims", claims);
        report.put("consumers", consumers);
        assertOutcome(fixture, accepted, quote, outcome);
        assertClaims(stopped, claims);
        assertEquals(List.of(QUERY, RESULT, RESUME), consumers.stream().map(row -> row.get("type")).toList());
        assertTrue(consumers.stream().allMatch(row -> "SUCCEEDED".equals(row.get("status")) && Integer.valueOf(1).equals(row.get("attempts"))));
        assertEquals(1, consumers.getFirst().get("slot"));
        assertEquals("CALL", consumers.getFirst().get("decision"));
        assertEquals(attempt.toString(), consumers.getFirst().get("cause_key"));
        assertEquals(attempt.toString(), consumers.get(1).get("cause_key"));
        assertEquals(returnId.toString(), consumers.get(2).get("cause_key"));
    }

    private Jobs.Job killAndRestart(Path folder, Jobs jobs, UUID jobId, UUID attempt, Instant firstClock,
            Map<String, Object> report) throws Exception {
        Process first = null;
        Process restart = null;
        var observed = new LinkedHashMap<String, Object>();
        report.put("processRecovery", observed);
        try {
            mark("first-start-requested");
            first = startWorker(folder, "first", firstClock, jobId, "PREPARED");
            mark("first-started");
            observed.put("firstPid", first.pid());
            observed.put("firstInjectedClock", firstClock.toString());
            var firstChild = first;
            Path prepared = folder.resolve("first-effect");
            awaitJobCondition(() -> Files.exists(prepared) || !firstChild.isAlive(), "Refund must reach PREPARED in child JVM");
            mark("prepared-observed");
            assertTrue(Files.exists(prepared) && first.isAlive(), "PREPARED must occur in the running child");
            awaitJobCondition(() -> Files.exists(folder.resolve("first-effect-runtime-prepared.json")) || !firstChild.isAlive(),
                    "Must preserve first child metrics before forced exit");
            assertTrue(first.isAlive(), "First worker must still be alive at the kill boundary");
            var stoppingState = jdbc.queryForMap("""
                    SELECT a.state, (SELECT count(*) FROM marketplace.payment_refund_mock m WHERE m.attempt_id = a.id) AS mock_rows
                    FROM marketplace.payment_refund_attempt a WHERE a.id = :id
                    """, Map.of("id", attempt));
            observed.put("atPrepared", stoppingState);
            assertEquals("PROCESSING", stoppingState.get("state"));
            assertEquals(0L, stoppingState.get("mock_rows"));
            var stopped = jobs.find(jobId).orElseThrow();
            assertEquals("RUNNING", stopped.status());
            assertEquals(1, stopped.attempts());
            mark("kill-requested");
            first.destroyForcibly();
            assertTrue(ContractTiming.awaitProcess(first, 10, TimeUnit.SECONDS), "First owned worker must exit after kill");
            mark("killed");
            assertFalse(first.isAlive());
            observed.put("firstExitCode", first.exitValue());
            assertNotEquals(0, first.exitValue());
            Instant restartClock = stopped.leaseUntil().plusNanos(1_000);
            observed.put("storedLeaseUntil", stopped.leaseUntil().toString());
            observed.put("restartInjectedClock", restartClock.toString());
            mark("restart-requested");
            restart = startWorker(folder, "restart", restartClock, jobId, "NORMAL");
            mark("restart-started");
            observed.put("restartPid", restart.pid());
            assertNotEquals(first.pid(), restart.pid());
            assertTrue(ContractTiming.awaitProcess(restart, 40, TimeUnit.SECONDS), "Restart JVM must finish the stored job");
            mark("restart-exited");
            observed.put("restartExitCode", restart.exitValue());
            assertEquals(0, restart.exitValue(), "Read preserved restart.log on failure");
            assertTrue(Files.exists(folder.resolve("restart-effect-completed")));
            var after = jobs.find(jobId).orElseThrow();
            observed.put("jobStatus", after.status());
            observed.put("jobAttempts", after.attempts());
            assertEquals("SUCCEEDED", after.status());
            assertEquals(2, after.attempts());
            assertRuntime(folder, "first", "first-worker", first.pid(), "ready");
            assertRuntime(folder, "first", "first-worker", first.pid(), "prepared");
            assertRuntime(folder, "restart", "second-worker", restart.pid(), "ready");
            assertRuntime(folder, "restart", "second-worker", restart.pid(), "completed");
            return stopped;
        } finally {
            try {
                stopOwned(first);
            } finally {
                stopOwned(restart);
            }
        }
    }

    private Process startWorker(Path folder, String phase, Instant clock, UUID jobId, String mode) throws Exception {
        Path arguments = folder.resolve(phase + ".args");
        Path marker = folder.resolve(phase + "-effect");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).replace('\\', '/');
        String workerOptions = "tier1".equals(System.getenv(WORKER_JVM_MODE)) ? TIER1_ARGUMENT + "\n" : "";
        Files.writeString(arguments, workerOptions + "-cp\n\"" + classpath + "\"\n" + P13RuntimeObservation.class.getName()
                + "\n" + clock + "\n" + jobId + "\n\"" + marker.toString().replace('\\', '/') + "\"\n" + mode + "\n",
                StandardOpenOption.CREATE_NEW);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(folder.resolve(phase + ".log").toFile());
        builder.environment().remove("D2_ADMIN_PASSWORD");
        return builder.start();
    }

    private void assertRuntime(Path folder, String prefix, String role, long pid, String phase) throws Exception {
        var snapshot = authMapper.readTree(Files.readString(folder.resolve(prefix + "-effect-runtime-" + phase + ".json")));
        assertEquals(pid, snapshot.path("pid").asLong());
        assertEquals(role, snapshot.path("role").asText());
        assertEquals(phase, snapshot.path("phase").asText());
        assertEquals(System.getenv("D2_DB_NAME"), snapshot.path("database").asText());
        assertEquals(System.getenv("D2_DB_NAME"), snapshot.path("pool").path("database").asText());
        assertEquals(5, snapshot.path("pool").path("maximum").asInt());
        assertTrue(snapshot.path("pool").path("total").asInt() > 0);
        var jvm = snapshot.path("jvm");
        boolean workerTier1 = "tier1".equals(System.getenv(WORKER_JVM_MODE));
        assertEquals(workerTier1 ? List.of(TIER1_ARGUMENT) : List.of(),
                authMapper.convertValue(jvm.required("tieredStopAtLevelArguments"),
                        new tools.jackson.core.type.TypeReference<List<String>>() {}), role + " JVM tiering arguments");
        assertEquals(workerTier1 ? 1 : 4, jvm.required("tieredStopAtLevel").asInt(), role + " effective tier");
        assertTrue(jvm.path("heap").path("usedBytes").asLong() > 0);
        assertTrue(jvm.path("threads").path("live").asInt() > 0);
        assertFalse(jvm.path("gc").isEmpty());
    }

    private void stopOwned(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertTrue(ContractTiming.awaitProcess(process, 10, TimeUnit.SECONDS), "Owned experiment worker did not stop");
        }
    }

    private void assertClaims(Jobs.Job stopped, List<Map<String, Object>> claims) {
        assertEquals(2, claims.size());
        var first = claims.getFirst();
        var second = claims.getLast();
        assertEquals(List.of("READY", "TAKEOVER"), claims.stream().map(row -> row.get("eligibility_kind")).toList());
        assertTrue(claims.stream().allMatch(row -> stopped.id().equals(row.get("job_id")) && Boolean.TRUE.equals(row.get("eligible"))));
        assertEquals(1, first.get("attempt"));
        assertEquals(2, second.get("attempt"));
        assertEquals(1, second.get("previous_attempt"));
        assertEquals(stopped.ownerToken(), first.get("owner_token"));
        assertEquals(first.get("owner_token"), second.get("previous_owner"));
        assertNotEquals(first.get("owner_token"), second.get("owner_token"));
        assertEquals(first.get("initial_lease_until"), second.get("required_at"));
        assertEquals(Boolean.TRUE, second.get("one_microsecond_after_lease"));
    }

    private Map<String, Object> outcome(UUID returnId, UUID attempt) {
        return jdbc.queryForMap("""
                SELECT a.id AS attempt, a.amount AS attempt_amount, a.state AS refund_state, a.version AS refund_revision,
                       a.purpose, a.sequence, a.original_reference AS quote_fingerprint, r.order_id,
                       r.state AS return_state, r.version AS return_revision, h.id AS hold_id, h.state AS hold_state,
                       c.request_key AS consent_key, c.original_revision AS consent_revision, c.explicit_consent,
                       fee.id AS assessment_id, fee.quote_id, fee.state AS assessment_state,
                       fee.refund AS assessed_refund, fee.increment AS assessed_increment,
                       b.paid, b.reserved, b.succeeded, bucket.reserved_increment,
                       coalesce(m.submit_calls, 0) AS provider_submissions, coalesce(m.effects, 0) AS provider_effects,
                       (SELECT count(*) FROM marketplace.payment_refund_attempt a2 WHERE a2.claim_id = r.id) AS attempts,
                       (SELECT count(*) FROM marketplace.claim_fee_application f WHERE f.assessment_id = fee.id) AS applied_fees,
                       (SELECT count(*) FROM marketplace.claim_return_completion t WHERE t.return_id = r.id) AS completed_returns
                FROM marketplace.claim_return_request r
                JOIN marketplace.claim_return_financial_consent c ON c.return_id = r.id
                JOIN marketplace.payment_refund_attempt a ON a.id = c.attempt_id AND a.claim_id = r.id
                JOIN marketplace.claim_fee_assessment fee ON fee.id = c.assessment_id
                JOIN marketplace.claim_fee_bucket bucket ON bucket.active_assessment_id = fee.id
                JOIN marketplace.payment_refund_budget b ON b.payment_id = a.payment_id AND b.order_id = r.order_id
                JOIN marketplace.order_unit_hold h ON h.id = r.hold_id
                LEFT JOIN marketplace.payment_refund_mock m ON m.attempt_id = a.id
                WHERE r.id = :return AND a.id = :attempt
                """, Map.of("return", returnId, "attempt", attempt));
    }

    private void assertOutcome(PhysicalReturn fixture, ReturnFinances.Accepted accepted, ReturnFees.Quote quote,
            Map<String, Object> outcome) {
        assertEquals(accepted.refundAttemptId(), outcome.get("attempt"));
        assertEquals(accepted.assessmentId(), outcome.get("assessment_id"));
        assertEquals(fixture.accepted().holdId(), outcome.get("hold_id"));
        assertEquals(fixture.parties().buyer().order().id(), outcome.get("order_id"));
        assertEquals(quote.id(), outcome.get("quote_id"));
        assertEquals(quote.fingerprint(), outcome.get("quote_fingerprint"));
        assertEquals("ITEM", outcome.get("purpose"));
        assertEquals(1, outcome.get("sequence"));
        assertEquals(CONSENT_KEY, outcome.get("consent_key"));
        assertEquals(REVISION, outcome.get("consent_revision"));
        assertEquals(Boolean.TRUE, outcome.get("explicit_consent"));
        assertEquals("UNKNOWN", outcome.get("refund_state"));
        assertEquals("REVIEW_REQUIRED", outcome.get("return_state"));
        assertEquals("ACTIVE", outcome.get("hold_state"));
        assertEquals("BOUND", outcome.get("assessment_state"));
        assertEquals(43_000L, outcome.get("paid"));
        assertEquals(7_000L, Long.parseLong(quote.refund()));
        assertEquals(3_000L, Long.parseLong(quote.increment()));
        assertEquals(Long.parseLong(quote.refund()), outcome.get("attempt_amount"));
        assertEquals(Long.parseLong(quote.refund()), outcome.get("assessed_refund"));
        assertEquals(Long.parseLong(quote.refund()), outcome.get("reserved"));
        assertEquals(Long.parseLong(quote.increment()), outcome.get("assessed_increment"));
        assertEquals(Long.parseLong(quote.increment()), outcome.get("reserved_increment"));
        assertEquals(0L, outcome.get("succeeded"));
        assertEquals(1L, outcome.get("attempts"));
        assertEquals(0, outcome.get("provider_submissions"));
        assertEquals(0, outcome.get("provider_effects"));
        assertEquals(0L, outcome.get("applied_fees"));
        assertEquals(0L, outcome.get("completed_returns"));
    }

    private UUID jobId(String type, UUID cause) {
        return jdbc.queryForObject("SELECT j.id FROM marketplace.job j WHERE j.type = :type AND j.cause_key = :cause",
                Map.of("type", type, "cause", cause.toString()), UUID.class);
    }

    private Map<String, Object> startingScale() {
        var scale = jdbc.getJdbcTemplate().queryForMap("""
                SELECT pg_database_size(current_database()) AS database_bytes, current_setting('server_version') AS postgres_version,
                       (SELECT count(*) FROM pg_indexes WHERE schemaname = 'marketplace') AS indexes,
                       (SELECT encode(sha256(convert_to(string_agg(indexdef, E'\\n' ORDER BY indexname), 'UTF8')), 'hex')
                            FROM pg_indexes WHERE schemaname = 'marketplace') AS index_sha256,
                       (SELECT count(*) FROM marketplace.flyway_schema_history WHERE success) AS successful_migrations,
                       (SELECT encode(sha256(convert_to(string_agg(script || ':' || coalesce(checksum::text, ''),
                                    E'\\n' ORDER BY installed_rank), 'UTF8')), 'hex')
                            FROM marketplace.flyway_schema_history WHERE success) AS migration_sha256
                """);
        var rows = new LinkedHashMap<String, Long>();
        STARTING_TABLES.forEach(table -> rows.put(table, jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM marketplace." + table, Long.class)));
        scale.put("rows", rows);
        assertEquals(2L, rows.get("purchase_order"), "Buyer transaction and separate seller fixture order");
        assertEquals(1L, rows.get("claim_return_request"));
        assertEquals(0L, rows.get("payment_refund_attempt"));
        return scale;
    }

    private long mark(String phase) {
        long elapsed = System.nanoTime() - origin;
        timeline.add(Map.of("phase", phase, "elapsedNs", elapsed, "observedUtc", Instant.now().toString()));
        return elapsed;
    }
}
