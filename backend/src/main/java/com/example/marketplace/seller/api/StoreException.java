package com.example.marketplace.seller.api;

public final class StoreException extends RuntimeException {
    private final int status;
    public StoreException(int status) { super("STORE_REJECTED"); this.status = status; }
    public int status() { return status; }
}
