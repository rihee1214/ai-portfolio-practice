package com.example.marketplace.guest;
import com.example.marketplace.phone.infrastructure.LocalPhonePolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
class GuestLocalPolicyTest {
    @Test void requiresExplicitLoopbackModeAndRejectsProductionProfiles() {
        assertDoesNotThrow(() -> LocalPhonePolicy.check("disabled", "0.0.0.0", Set.of("prod")));
        assertDoesNotThrow(() -> LocalPhonePolicy.check("local-test", "127.0.0.1", Set.of("db")));
        assertThrows(IllegalArgumentException.class, () -> LocalPhonePolicy.check("local-test", "0.0.0.0", Set.of("db")));
        assertThrows(IllegalArgumentException.class, () -> LocalPhonePolicy.check("production", "127.0.0.1", Set.of("db")));
        for (String profile : Set.of("prod", "production")) {
            assertThrows(IllegalArgumentException.class, () -> LocalPhonePolicy.check("local-test", "127.0.0.1", Set.of(profile)));
        }
    }
}
