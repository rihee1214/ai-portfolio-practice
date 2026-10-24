package com.example.marketplace.identity.domain;

import java.time.Instant;

public interface VerificationMailbox {
    Prepared prepare(String recipient, String token, Instant expiresAt);

    interface Prepared {
        /** In-memory publication must not perform fallible external I/O after DB commit. */
        void publish();

        void discard();
    }
}
