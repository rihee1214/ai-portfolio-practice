package com.example.marketplace.order.api;

import java.util.List;
import java.util.UUID;

public interface SellerSettlementOrders {
    List<UUID> sellerOrdersForStore(UUID store);
}
