package com.example.marketplace.identity.api;

public final class AccessRejected extends RuntimeException {
    public AccessRejected() { super("ACCESS_DENIED"); }
}
