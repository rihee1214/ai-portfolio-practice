package com.example.marketplace.guest;
import com.example.marketplace.guest.api.RecoverySecrets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
class RecoverySecretsTest {
    @Test void usesRandomBoundedSecretsAndConstantHashComparison() {
        String first=RecoverySecrets.generate(),second=RecoverySecrets.generate();
        assertEquals(43,first.length());assertNotEquals(first,second);
        String hash=RecoverySecrets.hash(first);assertEquals(64,hash.length());assertFalse(hash.contains(first));
        assertTrue(RecoverySecrets.matches(first,hash));assertFalse(RecoverySecrets.matches(second,hash));
        assertFalse(RecoverySecrets.matches(null,hash));assertFalse(RecoverySecrets.matches("short",hash));assertFalse(RecoverySecrets.matches(first,null));
    }
}

