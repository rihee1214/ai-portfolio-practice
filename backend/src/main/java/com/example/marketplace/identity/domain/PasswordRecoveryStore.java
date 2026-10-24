package com.example.marketplace.identity.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

public interface PasswordRecoveryStore {
    UUID lockEligibleMember(String email);
    void replaceToken(UUID member, String hash, Instant createdAt, Instant expiresAt);
    boolean consume(String tokenHash, String passwordHash, Supplier<Instant> currentTime);
}
