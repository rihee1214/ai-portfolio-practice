package com.example.marketplace.jdbcfixture;

public final class TargetContract {
    private static final String DATABASE = "c3_verify_0123456789abcdef0123456789abcdef";
    private static final String URL = "jdbc:postgresql://127.0.0.1:15433/" + DATABASE;

    private TargetContract() {
    }

    public static void main(String[] args) {
        int failures = 0;
        String[][] rejected = {
            {"jdbc:postgresql://127.0.0.1:15432/" + DATABASE, "marketplace_test", DATABASE},
            {"jdbc:postgresql://127.0.0.1:15433/marketplace_dev", "marketplace_test", "marketplace_dev"},
            {"jdbc:postgresql://127.0.0.1:15433/marketplace_test", "marketplace_test", "marketplace_test"},
            {"jdbc:postgresql://production:5432/" + DATABASE, "marketplace_test", DATABASE},
            {URL + "?currentSchema=public", "marketplace_test", DATABASE},
            {URL, "postgres", DATABASE},
            {URL, "marketplace_dev", DATABASE},
            {URL, "marketplace_test", ""},
            {URL, "marketplace_test", "c3_verify_ffffffffffffffffffffffffffffffff"},
            {"jdbc:postgresql://localhost:15433/" + DATABASE, "marketplace_test", DATABASE},
            {null, "marketplace_test", DATABASE},
            {URL, null, DATABASE}
        };
        for (int i = 0; i < rejected.length; i++) {
            try {
                DedicatedDatabaseTarget.validate(rejected[i][0], rejected[i][1], rejected[i][2]);
                System.out.println("FAIL target rejection " + i);
                failures++;
            } catch (IllegalArgumentException expected) {
                System.out.println("PASS target rejection " + i);
            }
        }
        DedicatedDatabaseTarget.validate(URL, "marketplace_test", DATABASE);
        System.out.println("PASS dedicated target accepted");
        if (failures != 0) {
            throw new AssertionError("Target guard failures: " + failures);
        }
    }
}
