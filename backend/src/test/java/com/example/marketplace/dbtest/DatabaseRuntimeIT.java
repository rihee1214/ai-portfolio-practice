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
