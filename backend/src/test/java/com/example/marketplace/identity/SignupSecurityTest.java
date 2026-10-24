package com.example.marketplace.identity;

import com.example.marketplace.identity.application.TokenSecrets;
import com.example.marketplace.identity.infrastructure.ArgonPasswordHasher;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupSecurityTest {
    @Test
    void saltedArgonMatchesUnicodeWithoutTruncationAndRecordsCostOnly() {
        var hasher = new ArgonPasswordHasher();
        String password = "😀".repeat(128);
        long start = System.nanoTime();
        String first = hasher.encode(password);
        long encoded = System.nanoTime();
        assertTrue(hasher.matches(password, first));
        long verified = System.nanoTime();
        System.out.println("A1_ARGON_ENCODE_MS=" + (encoded - start) / 1_000_000);
        System.out.println("A1_ARGON_VERIFY_MS=" + (verified - encoded) / 1_000_000);
        assertFalse(hasher.matches(password + "x", first));
        assertNotEquals(first, hasher.encode(password));
        assertTrue(first.contains("m=19456,t=2,p=1"));
    }

    @Test
    void tokensAreIndependentAndOnlyDigestIsStored() {
        String first = TokenSecrets.generate();
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, TokenSecrets.generate());
        assertTrue(TokenSecrets.hash(first).matches("[0-9a-f]{64}"));
        assertNotEquals(first, TokenSecrets.hash(first));
    }

    @Test
    void localMailboxHidesReservationsAndCanDiscardRollback() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var mailbox = new LocalTestMailbox(Clock.fixed(now, ZoneOffset.UTC));
        var discarded = mailbox.prepare("local@example.com", "token-one", now.plusSeconds(1800));
        assertTrue(mailbox.messages().isEmpty());
        discarded.discard();
        assertTrue(mailbox.messages().isEmpty());
        mailbox.prepare("local@example.com", "token-two", now.plusSeconds(1800)).publish();
        assertEquals(1, mailbox.messages().size());
        assertEquals("token-two", mailbox.messages().getFirst().token());
        assertFalse(mailbox.messages().toString().contains("token-two"));
    }

    @Test
    void mailboxExpiryAndCapacityAreBounded() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var mailbox = new LocalTestMailbox(Clock.fixed(now, ZoneOffset.UTC));
        mailbox.prepare("a@example.com", "expired", now).publish();
        assertTrue(mailbox.messages().isEmpty());
        for (int i = 0; i < 1000; i++) {
            mailbox.prepare("a@example.com", "synthetic", now.plusSeconds(1800));
        }
        assertThrows(IllegalArgumentException.class,
                () -> mailbox.prepare("a@example.com", "synthetic", now.plusSeconds(1800)));
    }
}
