package com.example.marketplace.identity.api;
public interface PasswordHashes {
    String encode(String password);
    boolean matches(String password, String hash);
}
