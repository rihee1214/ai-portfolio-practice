package com.example.marketplace.jdbcfixture;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/** Safety gate for dedicated fixture databases, never part of the application JAR. */
public final class DedicatedDatabaseTarget {
    private DedicatedDatabaseTarget() {
    }

    public static void validate(String url, String username, String confirmation) {
        if (confirmation == null || !Pattern.matches("(?:c3_verify|d2_it)_[0-9a-f]{32}", confirmation)
                || !("jdbc:postgresql://127.0.0.1:15433/" + confirmation).equals(url)
                || !"marketplace_test".equals(username)) {
            throw new IllegalArgumentException("Only an explicitly confirmed dedicated test target is allowed");
        }
    }

    public static void verifyConnection(Connection connection, String database) throws SQLException {
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
}
