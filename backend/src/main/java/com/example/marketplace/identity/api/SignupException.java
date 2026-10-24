package com.example.marketplace.identity.api;

/** Fixed codes only; no rejected values or SQL/driver causes. */
public final class SignupException extends IllegalArgumentException {
    public SignupException(String code) {
        super(code);
    }
}
