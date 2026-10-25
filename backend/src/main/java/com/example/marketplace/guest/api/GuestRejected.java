package com.example.marketplace.guest.api;

public final class GuestRejected extends RuntimeException {
    private final int status;
    public GuestRejected(int status) { super("Guest verification rejected"); this.status = status; }
    public int status() { return status; }
}
