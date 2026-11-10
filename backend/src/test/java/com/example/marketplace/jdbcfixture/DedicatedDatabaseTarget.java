package com.example.marketplace.jdbcfixture;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;

/** Safety gate for dedicated fixture databases, never part of the application JAR. */
public final class DedicatedDatabaseTarget {
    private DedicatedDatabaseTarget() {
    }

    public static void validate(String url, String username, String confirmation) {
        validate(url, username, confirmation, configured());
    }

    public record Endpoint(int port, String instance) {
        public Endpoint {
            if (port < 1024 || port > 65535 || port == 15432
                    || (port == 15433 ? !"default".equals(instance)
                    : instance == null || !Pattern.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", instance))) {
                throw new IllegalArgumentException("Unsafe dedicated PostgreSQL endpoint");
            }
        }

        public String confirmation() { return "127.0.0.1:" + port + "|" + instance; }
        public String url(String database) { return "jdbc:postgresql://127.0.0.1:" + port + "/" + database; }
    }

    public static Endpoint endpoint(String port, String instance, String confirmation, boolean required) {
        Endpoint target;
        try {
            target = new Endpoint(port == null || port.isBlank() ? 15433 : Integer.parseInt(port),
                    instance == null || instance.isBlank() ? "default" : instance);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Unsafe dedicated PostgreSQL endpoint");
        }
        if ((required || target.port() != 15433 || confirmation != null)
                && !target.confirmation().equals(confirmation)) {
            throw new IllegalArgumentException("Missing or mismatched dedicated endpoint confirmation");
        }
        return target;
    }

    public static Endpoint configured() {
        return endpoint(System.getenv("D2_TEST_POSTGRES_PORT"), System.getenv("D2_TEST_POSTGRES_INSTANCE"),
                System.getenv("D2_DB_ENDPOINT_CONFIRMATION"), System.getenv("D2_DB_NAME") != null);
    }

    public static String databaseUrl(String database) {
        if (database == null || !Pattern.matches("(?:c3_verify|d2_it)_[0-9a-f]{32}", database)) {
            throw new IllegalArgumentException("Dedicated database name required");
        }
        return configured().url(database);
    }

    public static String maintenanceUrl() { return configured().url("postgres"); }

    public static void validate(String url, String username, String confirmation, Endpoint endpoint) {
        if (confirmation == null || !Pattern.matches("(?:c3_verify|d2_it)_[0-9a-f]{32}", confirmation)
                || !endpoint.url(confirmation).equals(url)
                || !"marketplace_test".equals(username)) {
            throw new IllegalArgumentException("Only an explicitly confirmed dedicated test target is allowed");
        }
    }

    public static void verifyConnection(Connection connection, String database) throws SQLException {
        verifyConnection(connection, database, configured());
    }

    static void verifyConnection(Connection connection, String database, Endpoint endpoint) throws SQLException {
        verifyEndpoint(connection, database, endpoint);
        try (var statement = connection.createStatement();
                var row = statement.executeQuery("""
                    SELECT current_database(), current_user, rolsuper, rolcreatedb, rolcreaterole
                    FROM pg_roles WHERE rolname = current_user
                    """)) {
            if (!row.next() || !database.equals(row.getString(1))
                    || !"marketplace_test".equals(row.getString(2))
                    || row.getBoolean(3) || row.getBoolean(4) || row.getBoolean(5)) {
                throw new IllegalArgumentException("Connected database identity or role privileges are unsafe");
            }
        }
    }

    private static void verifyEndpoint(Connection connection, String database, Endpoint endpoint) throws SQLException {
        if (!endpoint.url(database).equals(connection.getMetaData().getURL())) {
            throw new IllegalArgumentException("Connected endpoint differs from explicitly selected endpoint");
        }
        if (endpoint.port() != 15433) {
            try (var query = connection.createStatement();
                    var row = query.executeQuery("SELECT current_setting('marketplace.verification_instance', true)")) {
                if (!row.next() || !endpoint.instance().equals(row.getString(1))) {
                    throw new IllegalArgumentException("Connected PostgreSQL instance is not the selected test instance");
                }
            }
        }
    }

    /** Must precede CREATE DATABASE, role changes and Flyway. */
    public static void verifyAdminConnection(Connection connection) throws SQLException {
        verifyAdminConnection(connection, configured());
    }

    static void verifyAdminConnection(Connection connection, Endpoint endpoint) throws SQLException {
        verifyEndpoint(connection, "postgres", endpoint);
        try (var query = connection.createStatement(); var row = query.executeQuery("SELECT current_database(), current_user")) {
            if (!row.next() || !"postgres".equals(row.getString(1)) || !"postgres".equals(row.getString(2))) {
                throw new IllegalArgumentException("Unsafe maintenance connection");
            }
        }
    }

    /** Child processes authenticate the exact instance and low role before Spring can run Flyway. */
    public static void verifyBeforeMigration(String url, String user, String password, String database) throws SQLException {
        // A child must never recreate the default endpoint after losing all inherited routing settings.
        if (System.getenv("D2_DB_ENDPOINT_CONFIRMATION") == null) {
            throw new IllegalArgumentException("Child endpoint confirmation required before migration");
        }
        validate(url, user, database);
        for (String key : new String[] {"url", "user", "password"}) {
            if (System.getProperty("spring.flyway." + key) != null
                    || System.getenv("SPRING_FLYWAY_" + key.toUpperCase(java.util.Locale.ROOT)) != null) {
                throw new IllegalArgumentException("Separate Flyway connection settings are not allowed");
            }
        }
        try (var connection = DriverManager.getConnection(url, user, password)) {
            verifyConnection(connection, database);
        }
    }
}
