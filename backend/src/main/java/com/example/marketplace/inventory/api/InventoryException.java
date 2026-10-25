package com.example.marketplace.inventory.api;
public final class InventoryException extends RuntimeException {
    private final int status;
    public InventoryException(int status) { super("Inventory request rejected"); this.status=status; }
    public int status() { return status; }
}
