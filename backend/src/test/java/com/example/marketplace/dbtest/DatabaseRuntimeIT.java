package com.example.marketplace.dbtest;


import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import com.example.marketplace.jdbcfixture.TransferRejectedException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseRuntimeIT extends PostgresContractFixture {
    @Test
    void batchedParticipantSnapshotsPreserveEveryTableAndValue() throws Exception {
        var orderExpected = java.util.List.of("guest_checkout", "guest_attempt", "guest_buyer", "guest_access_session",
                "guest_order_reference", "order_checkout_attempt", "purchase_order", "seller_order", "order_item",
                "order_unit", "order_contact", "order_reservation", "order_event", "inventory_balance",
                "inventory_reservation", "inventory_reservation_event", "coupon_reservation_batch", "issued_coupon",
                "coupon_reservation", "coupon_reservation_event", "job", "outbox_event");
        var aftercareExpected = java.util.List.of("order_aftercare_policy", "order_policy_migration", "order_confirmation_state",
                "order_unit_lifecycle", "order_confirmation", "order_confirmation_unit", "order_unit_hold", "order_hold_unit",
                "order_lifecycle_event", "order_dispatch", "order_dispatch_unit", "order_confirmation_schedule",
                "order_confirmation_scan", "order_initial_delivery", "order_initial_delivery_unit", "shipment", "shipment_item",
                "shipment_unit", "shipment_event", "shipment_audit", "shipment_preparation", "shipment_command", "shipment_tracking_history");
        var returnExpected = java.util.List.of("order_return_application", "claim_return_request", "claim_return_unit",
                "claim_return_history", "claim_return_command", "claim_return_collection", "claim_return_inspection",
                "claim_return_inspection_unit", "inventory_return_restitution", "inventory_restitution_unit",
                "inventory_restitution_movement", "claim_return_fee_quote_context", "claim_return_financial_consent",
                "claim_return_financial_result", "claim_return_support_command", "order_return_physical_unit",
                "claim_return_physical_source", "inventory_return_physical_restitution", "claim_return_item_ready",
                "claim_return_ready_unit", "claim_seller_shipping_decision", "claim_seller_shipping_current",
                "claim_return_shipping_binding", "order_return_completion", "order_return_completion_unit", "order_shipping_decision",
                "order_shipping_current", "order_aftercare_shipping", "claim_shipping_result", "claim_return_completion", "claim_shipping_support_command");
        assertEquals(orderExpected, ParticipantSnapshots.ORDER_TABLES, "Original complete 22-table contract");
        assertEquals(aftercareExpected, ParticipantSnapshots.AFTERCARE_TABLES, "Original complete 23-table contract");
        assertEquals(returnExpected, ParticipantSnapshots.RETURN_APPLICATION_TABLES, "Original complete 31-table contract");
        var sets = java.util.Map.of("order",orderExpected,"aftercare",aftercareExpected,"return",returnExpected);
        var expected = java.util.stream.Stream.of(orderExpected,aftercareExpected,returnExpected).flatMap(java.util.List::stream).distinct().toList();
        assertEquals(76,expected.size());
        String schema = "snapshot_probe_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var oldNanos = new java.util.ArrayList<Long>();
        var newNanos = new java.util.ArrayList<Long>();
        var rawPairs = new java.util.ArrayList<Map<String,Object>>();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                jdbc.getJdbcTemplate().execute("CREATE SCHEMA " + schema);
                for (String table : expected) {
                    jdbc.getJdbcTemplate().execute("CREATE TABLE " + schema + "." + table
                            + " (id integer, amount numeric(18,4), note text, active boolean, payload jsonb)");
                }
                var empty = ParticipantSnapshots.read(jdbc.getJdbcTemplate(), schema, expected);
                assertEquals(76, empty.size());
                assertTrue(empty.values().stream().allMatch("[]"::equals));
                for (String table : expected) {
                    jdbc.getJdbcTemplate().execute("INSERT INTO " + schema + "." + table
                            + " VALUES (2,12.3400,NULL,true,'{\"b\":[2,null],\"a\":1}'),"
                            + "(1,-4.1200,'한글 인용 '' 값',false,'{\"empty\":{}}')");
                }
                java.util.function.Supplier<Map<String,String>> legacyRead = () -> {
                    var value = new java.util.TreeMap<String,String>();
                    for (String table : expected) {
                        value.put(table, jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t)"
                                + " ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + schema + "." + table + " t", String.class));
                    }
                    return value;
                };
                var legacy = legacyRead.get();
                assertEquals(legacy, ParticipantSnapshots.read(jdbc.getJdbcTemplate(), schema, expected));
                for(var set:sets.values()) {
                    var expectedSet=new java.util.TreeMap<String,String>();set.forEach(table -> expectedSet.put(table,legacy.get(table)));
                    assertEquals(expectedSet,ParticipantSnapshots.read(jdbc.getJdbcTemplate(),schema,set));
                }
                for (String table : expected) {
                    Object savepoint = status.createSavepoint();
                    jdbc.getJdbcTemplate().update("UPDATE " + schema + "." + table + " SET note='changed' WHERE id=2");
                    var changed = ParticipantSnapshots.read(jdbc.getJdbcTemplate(), schema, expected);
                    org.junit.jupiter.api.Assertions.assertNotEquals(legacy.get(table), changed.get(table), table);
                    for (String other : expected) { if (!other.equals(table)) { assertEquals(legacy.get(other), changed.get(other)); } }
                    for(var set:sets.values()) {
                        var expectedSet=new java.util.TreeMap<String,String>();set.forEach(name -> expectedSet.put(name,changed.get(name)));
                        assertEquals(expectedSet,ParticipantSnapshots.read(jdbc.getJdbcTemplate(),schema,set));
                    }
                    status.rollbackToSavepoint(savepoint);status.releaseSavepoint(savepoint);
                    assertEquals(legacy, ParticipantSnapshots.read(jdbc.getJdbcTemplate(), schema, expected));
                }
                for (int iteration = 0; iteration < 24; iteration++) {
                    Map<String,String> before, after;
                    long oldElapsed, newElapsed, start = System.nanoTime();
                    if (iteration % 2 == 0) {
                        before = legacyRead.get();oldElapsed = System.nanoTime() - start;
                        start = System.nanoTime();after = ParticipantSnapshots.read(jdbc.getJdbcTemplate(), schema, expected);
                        newElapsed = System.nanoTime() - start;
                    } else {
                        after = ParticipantSnapshots.read(jdbc.getJdbcTemplate(), schema, expected);newElapsed = System.nanoTime() - start;
                        start = System.nanoTime();before = legacyRead.get();oldElapsed = System.nanoTime() - start;
                    }
                    assertEquals(before, after);
                    rawPairs.add(Map.of("iteration",iteration,"warmup",iteration<4,"first",iteration%2==0?"legacy":"batched",
                            "legacyNanos",oldElapsed,"batchedNanos",newElapsed));
                    if (iteration >= 4) { oldNanos.add(oldElapsed);newNanos.add(newElapsed); }
                }
            } finally { status.setRollbackOnly(); }
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=:name", Map.of("name",schema), Integer.class));
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"participant-snapshot-comparison.json"),
                authMapper.writeValueAsString(Map.of("scope","76 representative typed tables across exact22/23/31 sets; helper equivalence, not replacement business coverage",
                        "tables",expected,"mutationsDetected",76,"schemaRolledBack",true,"warmupPairs",4,"measuredPairs",20,
                        "legacyNanos",oldNanos,"batchedNanos",newNanos,"rawPairs",rawPairs)));
    }

    @Test
    void databaseSettingsValidatorAndActualDataSourceCoexist() {
        assertTrue(context.containsBean("requiredDatabaseSettings"));
        assertEquals(System.getenv("D2_DB_URL"), context.getEnvironment().getProperty("spring.datasource.url"));
        assertEquals(System.getenv("D2_DB_USERNAME"), context.getEnvironment().getProperty("spring.datasource.username"));
        assertEquals("none", context.getEnvironment().getProperty("management.endpoints.access.default"));
        assertEquals("read-only", context.getEnvironment().getProperty("management.endpoint.health.access"));
        assertTrue(context.containsBean("dbHealthContributor"));
    }

    @Test
    void invalidSettingsFailBeforeSingletonsWithRealDatabaseAutoConfigurationEnabled() {
        for (String key : new String[] {"url", "username", "password"}) {
            var singletonCreated = new AtomicBoolean();
            var databaseCreationAttempted = new AtomicBoolean();
            new ApplicationContextRunner().withInitializer(candidate -> {
                candidate.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
                        if (beanName.equals("dataSource") || beanName.equals("flyway")) {
                            databaseCreationAttempted.set(true);
                        }
                        return null;
                    }
                });
                candidate.getEnvironment().setConversionService(new ApplicationConversionService());
                candidate.getEnvironment().getPropertySources().remove("systemEnvironment");
                candidate.getEnvironment().getPropertySources().remove("systemProperties");
                candidate.getEnvironment().setActiveProfiles("db");
            }).withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(MarketplaceApplication.class)
                    .withPropertyValues("spring.config.location=classpath:/",
                            "DB_URL=" + System.getenv("D2_DB_URL"), "DB_USERNAME=marketplace_test",
                            "DB_PASSWORD=unused-synthetic-password", "spring.datasource." + key + "= ")
                    .withBean("singletonProbe", Object.class, () -> {
                        singletonCreated.set(true);
                        return new Object();
                    }).run(candidate -> {
                        assertThat(candidate).hasFailed();
                        assertThat(candidate.getStartupFailure())
                                .hasStackTraceContaining("Required database settings are missing, blank or unresolved:")
                                .hasStackTraceContaining("spring.datasource." + key);
                        assertFalse(singletonCreated.get());
                        assertFalse(databaseCreationAttempted.get());
                    });
        }
    }

    @Test
    void realDatabaseHealthPreservesStatusOnlyAndRequestId() throws Exception {
        var response = request("GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertTrue(response.headers().firstValue("X-Request-ID").orElse("")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        var head = request("HEAD", "/actuator/health");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(405, request("POST", "/actuator/health").statusCode());
    }

    @Test
    void databaseProfileDoesNotExposeManagementDetailsOrJmx() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/configprops",
                "/actuator/metrics", "/actuator/beans", "/actuator/health/db",
                "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertEquals(404, request("GET", path).statusCode(), path);
        }
        assertTrue(mBeanServer.queryNames(new ObjectName("com.example.marketplace.dbfollowup:*"), null).isEmpty());
        assertFalse(context.containsBean("heapDumpWebEndpoint"));
    }

    @Test
    void bootUsesVerifiedPostgresAndUnprivilegedRole() throws Exception {
        try (var connection = source.getConnection()) {
            DedicatedDatabaseTarget.verifyConnection(connection, System.getenv("D2_DB_NAME"));
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(18, connection.getMetaData().getDatabaseMajorVersion());
        }
    }

    @Test
    void sameProductionV1AndFixtureV2MigrateOnce() {
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.flyway_schema_history WHERE version IN ('1','2') AND success",
                Map.of(), Integer.class));
        assertEquals("initialize schema", jdbc.queryForObject(
                "SELECT description FROM marketplace.flyway_schema_history WHERE version = '1'", Map.of(), String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void parametersAndMappingHandleQuotesUnicodeDecimalAndNull() {
        seed(101, "구매자 O'Reilly", null);
        seed(102, "판매자", "설명");
        var account = accounts.findByLabel("구매자 O'Reilly").orElseThrow();
        assertEquals(101, account.id());
        assertEquals("구매자 O'Reilly", account.label());
        assertEquals(new BigDecimal("100.50"), account.balance());
        assertNull(account.note());
        assertEquals("설명", accounts.findByLabel("판매자").orElseThrow().note());
        assertTrue(accounts.findByLabel("missing").isEmpty());
        assertTrue(accounts.findByLabel("' OR 1=1 --").isEmpty());
    }

    @Test
    void successCommitsBothBalancesAndEntry() throws Exception {
        seed(201, "commit-from", null);
        seed(202, "commit-to", null);
        transfers.transfer(201, 202, new BigDecimal("25.25"), "commit");
        balance(201, "75.25");
        balance(202, "125.75");
        entryCount("commit", "1");
    }

    @Test
    void duplicateReferenceRollsBackUpdatesAlreadyMade() throws Exception {
        seed(301, "duplicate-from", null);
        seed(302, "duplicate-to", null);
        transfers.transfer(301, 302, new BigDecimal("10.00"), "duplicate");
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(301, 302, new BigDecimal("20.00"), "duplicate"));
        balance(301, "90.50");
        balance(302, "110.50");
        entryCount("duplicate", "1");
    }

    @Test
    void checkedFailureAfterDebitRollsBack() throws Exception {
        seed(401, "checked-from", null);
        assertThrows(TransferRejectedException.class,
                () -> transfers.transfer(401, 9999, new BigDecimal("30.00"), "checked"));
        balance(401, "100.50");
        entryCount("checked", "0");
    }

    @Test
    void databaseConstraintsPreventNegativeBalanceAndOrphanEntry() throws Exception {
        seed(501, "constraint-from", null);
        seed(502, "constraint-to", null);
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(501, 502, new BigDecimal("200.00"), "insufficient"));
        balance(501, "100.50");
        balance(502, "100.50");
        entryCount("insufficient", "0");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO c3_fixture.transfer_entry VALUES ('orphan', 9999, 501, 1)", Map.of()));
        entryCount("orphan", "0");
    }

    @Test
    void caughtRequiredFailureRollsBackEarlierSuccessfulTransfer() throws Exception {
        seed(61, "batch-from", null);
        seed(62, "batch-to", null);
        assertThrows(UnexpectedRollbackException.class, () -> batches.transferThenCatchFailure());
        balance(61, "100.50");
        balance(62, "100.50");
        entryCount("batch-first", "0");
    }
}
