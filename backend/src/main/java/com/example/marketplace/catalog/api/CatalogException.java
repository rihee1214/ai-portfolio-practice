package com.example.marketplace.catalog.api;

public final class CatalogException extends RuntimeException {
    private final int status;
    public CatalogException(int status) { super("CATALOG_REJECTED"); this.status=status; }
    public int status() { return status; }
}
