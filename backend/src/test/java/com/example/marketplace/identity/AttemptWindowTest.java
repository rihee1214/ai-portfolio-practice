package com.example.marketplace.identity;

import com.example.marketplace.identity.web.AttemptWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttemptWindowTest {
    @Test
    void windowLimitsAttemptsAndExpiresAtBoundary() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        var limiter = new AttemptWindow(clock);
        for (int i = 0; i < 5; i++) { assertTrue(limiter.allow("account", 5)); }
        assertFalse(limiter.allow("account", 5));
        assertTrue(limiter.allow("another", 5));
        when(clock.millis()).thenReturn(60999L);
        assertFalse(limiter.allow("account", 5));
        when(clock.millis()).thenReturn(61000L);
        assertTrue(limiter.allow("account", 5));
    }

    @Test
    void capacityIsBoundedAndFailsClosed() {
        var limiter = new AttemptWindow(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 10000; i++) { assertTrue(limiter.allow("key-" + i, 5)); }
        assertFalse(limiter.allow("overflow", 5));
    }
}
