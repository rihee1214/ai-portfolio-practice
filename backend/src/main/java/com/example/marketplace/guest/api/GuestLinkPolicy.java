package com.example.marketplace.guest.api;

import com.example.marketplace.phone.api.PhoneVerifications;

public enum GuestLinkPolicy {
    NEW, EXISTING;
    public static GuestLinkPolicy parse(String value) {
        try { return valueOf(value); } catch (IllegalArgumentException | NullPointerException invalid) { throw new GuestRejected(400); }
    }
    public static void requireSamePhone(String registered, String verified) {
        if (!PhoneVerifications.normalize(registered).equals(PhoneVerifications.normalize(verified))) { throw new GuestRejected(409); }
    }
}
