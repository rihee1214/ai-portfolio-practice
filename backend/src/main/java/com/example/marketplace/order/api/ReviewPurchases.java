package com.example.marketplace.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReviewPurchases {
    record Confirmation(UUID id, String cause, long revision, Instant confirmedAt) { }
    record Unit(int ordinal, String lifecycle, UUID holdId, UUID refundCause, Confirmation confirmation) { }
    record Item(UUID itemId, UUID memberId, UUID productId, UUID storeId, long orderRevision, List<Unit> units) {
        public Item { units = List.copyOf(units); }
    }

    Item get(String number, UUID itemId, OrderQueries.Authority authority);

    /** Public review annotation without buyer or transaction identifiers. */
    boolean fullyRefunded(UUID itemId);

    /** Retains the order lock through the caller's transaction. */
    Item lock(String number, UUID itemId, OrderQueries.Authority authority);
}
