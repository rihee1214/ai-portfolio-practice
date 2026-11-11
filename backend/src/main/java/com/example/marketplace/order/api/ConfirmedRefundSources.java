package com.example.marketplace.order.api;

import java.util.UUID;

/** The confirmation is immutable. This port locks its owner and quantity without changing lifecycle. */
public interface ConfirmedRefundSources {
    record Source(UUID order,UUID sellerOrder,UUID item,int ordinal,UUID confirmation,long net,String policy) { }
    Source lock(String orderNumber,UUID sellerOrder,UUID item,int ordinal);
}
