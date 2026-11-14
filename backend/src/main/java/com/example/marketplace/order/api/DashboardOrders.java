package com.example.marketplace.order.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Order-owned allocations are immutable; payment supplies their success date independently. */
public interface DashboardOrders {
    record Created(LocalDate day, UUID storeId, long count) { }
    record Allocation(UUID orderId, UUID sellerOrderId, UUID storeId, long subtotal,
                      long productDiscount, long couponDiscount, long chargedShipping, long total) { }
    record Confirmed(LocalDate day, UUID storeId, java.math.BigInteger net) { }
    List<Created> created();
    List<Allocation> allocations();
    List<Confirmed> confirmed();
}
