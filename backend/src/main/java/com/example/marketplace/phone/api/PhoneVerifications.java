package com.example.marketplace.phone.api;

import java.time.Instant;

/** Provider proof only; callers must bind it to their own purpose and session. */
public interface PhoneVerifications {
    record Request(String id, String session, Instant expiresAt) { }
    record Proof(String phone, Instant authenticatedAt) { }
    Request issue(String phone);
    String exchange(String id, String code);
    Proof confirm(String id, String token);
    static String normalize(String phone) {
        if (phone == null) { throw new IllegalArgumentException("Invalid phone"); }
        if (phone.matches("010-[0-9]{4}-[0-9]{4}")) { phone = phone.replace("-", ""); }
        if (phone.matches("010[0-9]{8}")) { return "+82" + phone.substring(1); }
        if (phone.matches("\\+8210[0-9]{8}")) { return phone; }
        throw new IllegalArgumentException("Invalid phone");
    }
}
