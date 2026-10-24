package com.example.marketplace.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SignupRules {
    private static final Pattern EMAIL = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
            + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private SignupRules() {
    }

    public static String email(String input) {
        if (input == null || input.length() > 320) {
            throw new IllegalArgumentException("Invalid email");
        }
        String stripped = input.strip();
        if (stripped.chars().anyMatch(character -> character > 127)) {
            throw new IllegalArgumentException("Invalid email");
        }
        String normalized = stripped.toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || normalized.indexOf('@') > 64 || !EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid email");
        }
        return normalized;
    }

    public static void password(String input) {
        if (input == null || input.length() > 256 || input.isBlank()) {
            throw new IllegalArgumentException("Invalid password length");
        }
        int count = input.codePointCount(0, input.length());
        if (count < 12 || count > 128 || input.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid password length");
        }
    }
}
