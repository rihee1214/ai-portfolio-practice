package com.example.marketplace.promotion.api;
public final class PromotionRejected extends RuntimeException {
    private final int status;
    public PromotionRejected(int status) { super("PROMOTION_REJECTED"); this.status=status; }
    public int status() { return status; }
}
