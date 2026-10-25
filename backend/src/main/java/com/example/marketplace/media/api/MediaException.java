package com.example.marketplace.media.api;

public final class MediaException extends RuntimeException {
    private final int status;
    public MediaException(int status) { super("Media request rejected"); this.status=status; }
    public int status() { return status; }
}
