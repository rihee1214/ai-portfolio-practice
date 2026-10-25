package com.example.marketplace.phone;

import com.example.marketplace.phone.application.PhoneProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneProviderTest {
    private final MutableClock clock = new MutableClock();
    private final FakeGateway gateway = new FakeGateway(clock);
    private final PhoneProvider provider = new PhoneProvider("local-test", "127.0.0.1", clock, gateway);

    @Test
    void normalizesOnlySupportedPhoneFormats() {
        assertEquals("+821012345678", PhoneProvider.normalize("010-1234-5678"));
        assertEquals("+821012345678", PhoneProvider.normalize("01012345678"));
        assertEquals("+821012345678", PhoneProvider.normalize("+821012345678"));
        for (String invalid : new String[] {"", "0101234567", "+8201012345678", "010 1234 5678", "０１０12345678", "01112345678"}) {
            assertThrows(IllegalArgumentException.class, () -> PhoneProvider.normalize(invalid));
        }
    }

    @Test
    void confirmationExpiresAtExactBoundaryAndConsumesSuccess() {
        var request = provider.issue("01012345678");
        clock.advance(299);
        assertEquals("+821012345678", provider.confirm(request.id(), "proof").phone());
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
        clock.advance(601);
        var expired = provider.issue("01012345678");
        clock.advance(300);
        assertThrows(IllegalStateException.class, () -> provider.confirm(expired.id(), "proof"));
    }

    @Test
    void resendInvalidatesOldRequestAndLimitsIssuanceAcrossFormats() {
        var first = provider.issue("01012345678");
        assertThrows(IllegalStateException.class, () -> provider.issue("+821012345678"));
        clock.advance(60);
        var second = provider.issue("010-1234-5678");
        assertThrows(IllegalStateException.class, () -> provider.confirm(first.id(), "proof"));
        clock.advance(60);
        provider.issue("01012345678");
        clock.advance(60);
        assertThrows(IllegalStateException.class, () -> provider.issue("01012345678"));
        assertThrows(IllegalStateException.class, () -> provider.confirm(second.id(), "proof"));
        clock.advance(720);
        assertNotNull(provider.issue("01012345678"));
    }

    @Test
    void failedConfirmationLocksNumberAndResendCannotResetFailures() {
        var request = provider.issue("01012345678");
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "invalid-proof"));
        }
        assertEquals(5, gateway.confirmations);
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
        clock.advance(60);
        assertThrows(IllegalStateException.class, () -> provider.issue("01012345678"));
        clock.advance(840);
        assertNotNull(provider.issue("01012345678"));
    }

    @Test
    void rejectsNonLocalModesAndAddresses() {
        for (String mode : new String[] {"production", "demo", "", "local-test,production"}) {
            assertThrows(IllegalArgumentException.class, () -> new PhoneProvider(mode, "127.0.0.1", clock, gateway));
        }
        assertThrows(IllegalArgumentException.class, () -> new PhoneProvider("local-test", "0.0.0.0", clock, gateway));
    }

    @Test
    void rejectsWrongNumberOrStaleProof() {
        var request = provider.issue("01012345678");
        gateway.phone = "+821099999999";
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
        gateway.phone = "+821012345678";
        gateway.authenticatedAt = clock.instant().minusSeconds(1);
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
    }

    @Test
    void failedCodeExchangesShareTheConfirmationFailureBudget() {
        var request = provider.issue("01012345678");
        for (int i = 0; i < 4; i++) {
            assertThrows(IllegalStateException.class, () -> provider.exchange(request.id(), "000000"));
        }
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "invalid-proof"));
        assertThrows(IllegalStateException.class, () -> provider.exchange(request.id(), "123456"));
        assertEquals(4, gateway.exchanges);
        clock.advance(60);
        assertThrows(IllegalStateException.class, () -> provider.issue("01012345678"));
    }

    @Test
    void concurrentConfirmationHasExactlyOneWinner() throws Exception {
        var request = provider.issue("01012345678");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> confirm = () -> {
                ready.countDown();
                start.await();
                try { provider.confirm(request.id(), "proof"); return true; }
                catch (IllegalStateException rejected) { return false; }
            };
            var first = pool.submit(confirm);
            var second = pool.submit(confirm);
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals(1, gateway.confirmations);
        }
    }

    @Test
    void rejectsExpiryDuringProviderCallAndFutureProof() {
        var request = provider.issue("01012345678");
        gateway.authenticatedAt = clock.instant().plusSeconds(1);
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
        gateway.authenticatedAt = clock.instant();
        gateway.delay = 300;
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
        assertEquals(2, gateway.confirmations);
    }

    @Test
    void issuanceFailureConsumesQuotaAndInvalidatesPreviousRequest() {
        var request = provider.issue("01012345678");
        gateway.failIssue = true;
        clock.advance(60);
        assertThrows(IllegalStateException.class, () -> provider.issue("01012345678"));
        assertThrows(IllegalStateException.class, () -> provider.confirm(request.id(), "proof"));
        clock.advance(60);
        assertThrows(IllegalStateException.class, () -> provider.issue("01012345678"));
        gateway.failIssue = false;
        clock.advance(60);
        assertThrows(IllegalStateException.class, () -> provider.issue("01012345678"));
        clock.advance(720);
        assertNotNull(provider.issue("01012345678"));
    }

    private static final class FakeGateway implements PhoneProvider.Gateway {
        private final Clock clock;
        private String phone = "+821012345678";
        private Instant authenticatedAt;
        private int confirmations;
        private int exchanges;
        private long delay;
        private boolean failIssue;
        FakeGateway(Clock clock) { this.clock = clock; }
        public String issue(String number) {
            if (failIssue) { throw new IllegalStateException("Unavailable"); }
            return "provider-" + clock.instant();
        }
        public String exchange(String session, String code) { exchanges++; if (!"123456".equals(code)) { throw new IllegalStateException("Rejected"); } return "custom-token"; }
        public PhoneProvider.Proof confirm(String session, String token) {
            confirmations++;
            ((MutableClock) clock).advance(delay);
            if (!"proof".equals(token)) { throw new IllegalStateException("Rejected"); }
            return new PhoneProvider.Proof(phone, authenticatedAt == null ? clock.instant() : authenticatedAt);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
