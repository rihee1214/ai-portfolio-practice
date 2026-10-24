package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.domain.VerificationMailbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Separate local mailbox prevents mixing email verification and password recovery purposes. */
public final class LocalPasswordResetMailbox implements VerificationMailbox {
    private final LocalTestMailbox delegate;
    public LocalPasswordResetMailbox(Clock clock) { delegate = new LocalTestMailbox(clock); }
    @Override public Prepared prepare(String recipient, String token, Instant expiresAt) {
        return delegate.prepare(recipient, token, expiresAt);
    }
    public List<LocalTestMailbox.Message> messages() { return delegate.messages(); }
}
