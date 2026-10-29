package com.example.marketplace.order.api;

public final class OrderRejected extends IllegalArgumentException {
    private final int status;
    private final com.example.marketplace.promotion.api.Quotes.Quote quote;
    public OrderRejected(int status) { this(status,null); }
    public OrderRejected(int status,com.example.marketplace.promotion.api.Quotes.Quote quote) { super("ORDER_REQUEST_CONFLICT");this.status=status;this.quote=quote; }
    public int status() { return status; }
    public com.example.marketplace.promotion.api.Quotes.Quote quote() { return quote; }
}
