package com.example.marketplace.guest.api;
public record GuestPassword(String value, String confirmation) {
    public GuestPassword {
        if (value == null || !value.equals(confirmation) || value.length() > 256
                || value.codePointCount(0,value.length()) < 12 || value.codePointCount(0,value.length()) > 128
                || value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid guest password");
        }
    }
    @Override public String toString() { return "GuestPassword[REDACTED]"; }
}
