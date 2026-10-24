package com.example.marketplace.identity.domain;

import java.time.Instant;
import java.util.UUID;

public interface IdentityStore {
    void insert(UUID id, String email, String passwordHash, String tokenHash, Instant now, Instant expiresAt);

    boolean consumeAndActivate(String tokenHash, Instant now);
}
