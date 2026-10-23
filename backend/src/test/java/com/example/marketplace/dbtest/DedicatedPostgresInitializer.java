package com.example.marketplace.dbtest;

import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs before context refresh, so Flyway cannot reach an unverified target. */
public class DedicatedPostgresInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required integration-test setting: " + name);
        }
        return value;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String database = required("D2_DB_NAME");
        String url = required("D2_DB_URL");
        String username = required("D2_DB_USERNAME");
        DedicatedDatabaseTarget.validate(url, username, database);
        if (!database.startsWith("d2_it_")) {
            throw new IllegalArgumentException("D2 requires a new d2_it database");
        }
        for (String key : new String[] {"spring.flyway.url", "spring.flyway.user", "spring.flyway.password"}) {
            if (context.getEnvironment().containsProperty(key)) {
                throw new IllegalArgumentException("Separate Flyway connection settings are not allowed in D2");
            }
        }
        String password = required("D2_DB_PASSWORD");
        String adminPassword = required("D2_ADMIN_PASSWORD");
        try {
            // Authenticate the application role before creating any database.
            try (var connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15433/postgres",
                    username, password)) {
                DedicatedDatabaseTarget.verifyConnection(connection, "postgres");
            }
            try (var admin = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15433/postgres",
                    "postgres", adminPassword); var sql = admin.createStatement()) {
                // The strict allowlist above makes this identifier safe; collision is an error, never reset.
                sql.executeUpdate("CREATE DATABASE \"" + database + "\" OWNER marketplace_test");
                System.out.println("D2_CREATED_DATABASE=" + database);
            }
            try (var connection = DriverManager.getConnection(url, username, password)) {
                DedicatedDatabaseTarget.verifyConnection(connection, database);
            }
        } catch (SQLException failure) {
            // Do not put connection diagnostics or credentials in context failure reports.
            throw new IllegalStateException("PostgreSQL test preparation failed; SQLSTATE=" + failure.getSQLState());
        }
        TestPropertyValues.of(
                "DB_URL=" + url, "DB_USERNAME=" + username, "DB_PASSWORD=" + password, "DB_SCHEMA=marketplace",
                "spring.datasource.url=" + url, "spring.datasource.username=" + username,
                "spring.datasource.password=" + password).applyTo(context);
    }
}
