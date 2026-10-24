package com.example.marketplace.identity.domain;

public interface PasswordHasher {
    String encode(String password);

    boolean matches(String password, String hash);
}
