package com.example.marketplace.guest.api;

import com.example.marketplace.phone.api.PhoneVerifications;

public record GuestInput(String name, String phone, String purpose) {
    public GuestInput {
        if (!"PURCHASE".equals(purpose) || name == null) { throw new IllegalArgumentException("Invalid purchase input"); }
        name = name.strip();
        if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>')) {
            throw new IllegalArgumentException("Invalid purchase input");
        }
        phone = PhoneVerifications.normalize(phone);
    }
}
