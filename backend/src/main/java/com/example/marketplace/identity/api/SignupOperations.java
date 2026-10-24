package com.example.marketplace.identity.api;

import java.util.UUID;

/** Application contract only; HTTP/session adapters are a later step. */
public interface SignupOperations {
    UUID register(String email, String password);

    void verifyEmail(String token);
}
