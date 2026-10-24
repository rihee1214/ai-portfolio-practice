package com.example.marketplace.identity;

import com.example.marketplace.identity.domain.SignupRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SignupRulesTest {
    @Test
    void normalizesEmailWithoutProviderSpecificAliases() {
        assertEquals("buyer+tag@example.com", SignupRules.email("  Buyer+Tag@EXAMPLE.com  "));
    }

    @Test
    void rejectsAmbiguousOrInvalidEmail() {
        assertThrows(IllegalArgumentException.class, () -> SignupRules.email("\u212A@example.com"));
        for (String value : new String[] {"a..b@example.com", ".a@example.com", "a@-example.com",
                "a@example", "a b@example.com", "a@example.com\nInjected", "한글@example.com", "a".repeat(65) + "@a.com"}) {
            assertThrows(IllegalArgumentException.class, () -> SignupRules.email(value));
        }
        assertThrows(IllegalArgumentException.class, () -> SignupRules.email(null));
    }

    @Test
    void passwordBoundariesCountCodePointsAndDoNotTrim() {
        assertDoesNotThrow(() -> SignupRules.password("가".repeat(12)));
        assertDoesNotThrow(() -> SignupRules.password("😀".repeat(128)));
        assertDoesNotThrow(() -> SignupRules.password(" pass phrase "));
        for (String value : new String[] {"x".repeat(11), "😀".repeat(129), " ".repeat(12), "x".repeat(11) + '\uD800'}) {
            assertThrows(IllegalArgumentException.class, () -> SignupRules.password(value));
        }
        assertThrows(IllegalArgumentException.class, () -> SignupRules.password(null));
    }
}
