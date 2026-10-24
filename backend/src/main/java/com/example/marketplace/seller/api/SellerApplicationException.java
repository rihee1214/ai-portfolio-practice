package com.example.marketplace.seller.api;

public final class SellerApplicationException extends RuntimeException {
    private final int status;
    public SellerApplicationException(int status) { super("SELLER_APPLICATION_REJECTED"); this.status = status; }
    public int status() { return status; }
}
