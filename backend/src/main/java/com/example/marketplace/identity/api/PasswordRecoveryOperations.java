package com.example.marketplace.identity.api;

public interface PasswordRecoveryOperations {
    void request(String email);
    void reset(String token, String password);
}
