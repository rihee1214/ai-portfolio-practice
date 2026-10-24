package com.example.marketplace.jdbcfixture;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.UnexpectedRollbackException;

/** Executable C3 contract; separate from the future JUnit/Failsafe test suite. */
public final class JdbcContract {
    private static final List<String> RESULTS = new ArrayList<>();
    private static int failures;

    private JdbcContract() {
    }

    private static void check(String name, boolean success) {
        RESULTS.add((success ? "PASS " : "FAIL ") + name);
        if (!success) {
            failures++;
        }
    }

    @FunctionalInterface
    private interface Attempt {
        void run() throws Exception;
    }

    private static void rejects(String name, Class<? extends Exception> expected, Attempt action) {
        try {
            action.run();
            check(name, false);
        } catch (Exception actual) {
            check(name, expected.isInstance(actual));
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static void seed(NamedParameterJdbcTemplate jdbc, long id, String label, String note) {
        jdbc.update("INSERT INTO c3_fixture.account VALUES (:id, :label, :balance, :note)",
                new MapSqlParameterSource().addValue("id", id).addValue("label", label)
                        .addValue("balance", new BigDecimal("100.50")).addValue("note", note));
    }

    /** A new JDBC connection observes committed data, never the test's own transaction. */
    private static boolean balances(DriverManagerDataSource source, long from, long to,
            String fromBalance, String toBalance) throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT id, balance FROM c3_fixture.account WHERE id IN (?, ?) ORDER BY id")) {
            statement.setLong(1, from);
            statement.setLong(2, to);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) == from
                        && rows.getBigDecimal(2).compareTo(new BigDecimal(fromBalance)) == 0
                        && rows.next() && rows.getLong(1) == to
                        && rows.getBigDecimal(2).compareTo(new BigDecimal(toBalance)) == 0
                        && !rows.next();
            }
        }
    }

    private static long entries(DriverManagerDataSource source, String reference) throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM c3_fixture.transfer_entry WHERE reference = ?")) {
            statement.setString(1, reference);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("c3.output"));
        Files.createDirectories(output);
        try {
            run(output);
        } finally {
            Files.write(output.resolve("results.txt"), RESULTS);
            RESULTS.forEach(System.out::println);
        }
        if (failures > 0) {
            throw new AssertionError("C3 failed contracts: " + failures);
        }
    }

    private static void run(Path output) throws Exception {
        String database = "c3_verify_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:postgresql://127.0.0.1:15433/" + database;
        DedicatedDatabaseTarget.validate(url, "marketplace_test", database);
        // Creation is additive only. A collision fails; existing databases are never reused/reset.
        try (var admin = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15433/postgres",
                "postgres", required("C3_TEST_ADMIN_PASSWORD")); var sql = admin.createStatement()) {
            sql.executeUpdate("CREATE DATABASE \"" + database + "\" OWNER marketplace_test");
        }
        Files.writeString(output.resolve("target.txt"), database + "\n");
        var source = new DriverManagerDataSource(url, "marketplace_test", required("C3_TEST_DB_PASSWORD"));
        try (var connection = source.getConnection()) {
            rejects("actual database mismatch rejected", IllegalArgumentException.class,
                    () -> DedicatedDatabaseTarget.verifyConnection(connection, "marketplace_dev"));
            DedicatedDatabaseTarget.verifyConnection(connection, database);
            check("actual dedicated database and unprivileged user accepted", true);
        }
        try (var admin = DriverManager.getConnection(url, "postgres", required("C3_TEST_ADMIN_PASSWORD"))) {
            rejects("actual administrator connection rejected", IllegalArgumentException.class,
                    () -> DedicatedDatabaseTarget.verifyConnection(admin, database));
        }
        var flyway = Flyway.configure().dataSource(source).defaultSchema("marketplace").schemas("marketplace")
                .locations("classpath:db/migration", "classpath:db/c3fixture")
                .cleanDisabled(true).baselineOnMigrate(false).load();
        check("production V1/V3/V4/V5 plus test-only V2 migrate", flyway.migrate().migrationsExecuted == 5);
        check("migration repeat has no pending changes", flyway.migrate().migrationsExecuted == 0);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("c3-fixture");
            context.registerBean(DriverManagerDataSource.class, () -> source);
            context.register(FixtureConfiguration.class);
            context.refresh();
            var jdbc = context.getBean(NamedParameterJdbcTemplate.class);
            var repository = context.getBean(AccountRepository.class);
            var service = context.getBean(TransferService.class);
            seed(jdbc, 1, "구매자 O'Reilly", null);
            seed(jdbc, 2, "판매자", "설명");
            var account = repository.findByLabel("구매자 O'Reilly");
            check("named parameter quote unicode decimal null mapping", account.isPresent()
                    && account.get().id() == 1 && account.get().label().equals("구매자 O'Reilly")
                    && account.get().balance().equals(new BigDecimal("100.50")) && account.get().note() == null);
            check("non-null text mapping", repository.findByLabel("판매자")
                    .map(value -> "설명".equals(value.note())).orElse(false));
            check("missing row maps to empty", repository.findByLabel("missing").isEmpty());
            check("SQL injection remains a value", repository.findByLabel("' OR 1=1 --").isEmpty());
            service.transfer(1, 2, new BigDecimal("25.25"), "success");
            check("commit visible from independent connection", balances(source, 1, 2, "75.25", "125.75"));
            check("transfer entry committed", entries(source, "success") == 1);
            rejects("duplicate reference fails after both balance updates", DataIntegrityViolationException.class,
                    () -> service.transfer(1, 2, new BigDecimal("10.00"), "success"));
            check("constraint failure rolls back both updates", balances(source, 1, 2, "75.25", "125.75"));
            check("original committed entry survives failed retry", entries(source, "success") == 1);

            seed(jdbc, 21, "checked-from", null);
            seed(jdbc, 22, "checked-unrelated", null);
            rejects("missing recipient raises checked failure after debit", TransferRejectedException.class,
                    () -> service.transfer(21, 9999, new BigDecimal("30.00"), "checked"));
            check("checked exception rolls back debit", balances(source, 21, 22, "100.50", "100.50"));
            check("checked failure leaves no entry", entries(source, "checked") == 0);

            seed(jdbc, 41, "insufficient-from", null);
            seed(jdbc, 42, "insufficient-to", null);
            rejects("negative balance database constraint", DataIntegrityViolationException.class,
                    () -> service.transfer(41, 42, new BigDecimal("200.00"), "insufficient"));
            check("failed debit leaves both balances unchanged", balances(source, 41, 42, "100.50", "100.50"));
            rejects("zero amount rejected", TransferRejectedException.class,
                    () -> service.transfer(41, 42, BigDecimal.ZERO, "zero"));
            rejects("same account rejected", TransferRejectedException.class,
                    () -> service.transfer(41, 41, BigDecimal.ONE, "same"));

            seed(jdbc, 61, "batch-from", null);
            seed(jdbc, 62, "batch-to", null);
            rejects("caught REQUIRED failure prevents outer commit", UnexpectedRollbackException.class,
                    () -> context.getBean(BatchService.class).transferThenCatchFailure());
            check("REQUIRED rollback includes earlier successful transfer", balances(source, 61, 62, "100.50", "100.50"));
            check("REQUIRED rollback removes earlier transfer entry", entries(source, "batch-first") == 0);
            rejects("foreign key enforced", DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO c3_fixture.transfer_entry VALUES ('bad-fk', 9999, 1, 1)", Map.of()));
            check("foreign key failure leaves no entry", entries(source, "bad-fk") == 0);
        }
    }
}
