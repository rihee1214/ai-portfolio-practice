package com.example.marketplace.cart.api;
public final class CartException extends RuntimeException {
    private final int status;
    public CartException(int status) { this.status=status; }
    public int status() { return status; }
}
