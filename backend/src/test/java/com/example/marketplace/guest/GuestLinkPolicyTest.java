package com.example.marketplace.guest;

import com.example.marketplace.guest.api.GuestLinkPolicy;
import com.example.marketplace.guest.api.GuestRejected;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuestLinkPolicyTest {
    @Test void requiresExplicitModeAndMatchingNormalizedPhoneWithoutFallback() {
        assertEquals(GuestLinkPolicy.NEW, GuestLinkPolicy.parse("NEW"));
        assertEquals(GuestLinkPolicy.EXISTING, GuestLinkPolicy.parse("EXISTING"));
        assertThrows(GuestRejected.class, () -> GuestLinkPolicy.parse(null));
        assertThrows(GuestRejected.class, () -> GuestLinkPolicy.parse("existing"));
        GuestLinkPolicy.requireSamePhone("010-1234-5678", "+821012345678");
        assertThrows(GuestRejected.class, () -> GuestLinkPolicy.requireSamePhone("01012345678", "01012345679"));
    }
}
