package com.example.marketplace.jdbcfixture;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DedicatedDatabaseTargetTest {
    private static final String DATABASE = "c3_verify_0123456789abcdef0123456789abcdef";
    private static final String URL = "jdbc:postgresql://127.0.0.1:15433/" + DATABASE;

    static Stream<Arguments> unsafeTargets() {
        return Stream.of(
                Arguments.of("jdbc:postgresql://127.0.0.1:15432/" + DATABASE, "marketplace_test", DATABASE),
                Arguments.of("jdbc:postgresql://127.0.0.1:15433/marketplace_dev", "marketplace_test", "marketplace_dev"),
                Arguments.of("jdbc:postgresql://127.0.0.1:15433/marketplace_test", "marketplace_test", "marketplace_test"),
                Arguments.of("jdbc:postgresql://production:5432/" + DATABASE, "marketplace_test", DATABASE),
                Arguments.of(URL + "?currentSchema=public", "marketplace_test", DATABASE),
                Arguments.of(URL, "postgres", DATABASE),
                Arguments.of(URL, "marketplace_dev", DATABASE),
                Arguments.of(URL, "marketplace_test", ""),
                Arguments.of(URL, "marketplace_test", "c3_verify_ffffffffffffffffffffffffffffffff"),
                Arguments.of("jdbc:postgresql://localhost:15433/" + DATABASE, "marketplace_test", DATABASE),
                Arguments.of(null, "marketplace_test", DATABASE),
                Arguments.of(URL, null, DATABASE));
    }

    @ParameterizedTest
    @MethodSource("unsafeTargets")
    void rejectsUnsafeTargetBeforeConnection(String url, String user, String confirmation) {
        assertThrows(IllegalArgumentException.class,
                () -> DedicatedDatabaseTarget.validate(url, user, confirmation));
    }

    @Test
    void acceptsExplicitDedicatedTargetWithoutConnecting() {
        assertDoesNotThrow(() -> DedicatedDatabaseTarget.validate(URL, "marketplace_test", DATABASE));
    }
}
