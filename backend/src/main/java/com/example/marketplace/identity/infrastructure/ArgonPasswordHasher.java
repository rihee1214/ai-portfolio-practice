package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.domain.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class ArgonPasswordHasher implements PasswordHasher {
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    @Override
    public String encode(String password) {
        return "{argon2id}" + encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return hash != null && hash.startsWith("{argon2id}") && encoder.matches(password, hash.substring(10));
    }
}
