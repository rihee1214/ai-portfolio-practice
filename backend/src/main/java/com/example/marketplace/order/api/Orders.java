package com.example.marketplace.order.api;

import com.example.marketplace.promotion.api.Quotes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trusted application port. HTTP ownership checks and reservation orchestration belong to P08-C. */
public interface Orders {
    record Contact(String buyerName,String buyerPhone,String recipientName,String recipientPhone,
            String postalCode,String addressLine1,String addressLine2) { }
    record Draft(UUID purchaseSessionId,String requestKey,String requestHash,UUID memberId,UUID guestBuyerId,
            UUID guestAttemptId,String mode,Quotes.Quote quote,Contact contact,Instant createdAt,Instant expiresAt) { }
    record Item(UUID id,UUID skuId,int quantity) { }
    record SellerOrder(UUID id,UUID storeId,List<Item> items) { public SellerOrder { items=List.copyOf(items); } }
    record Snapshot(UUID id,UUID checkoutId,String orderNumber,UUID memberId,UUID guestBuyerId,String mode,String state,
            Quotes.Quote quote,Contact contact,Instant createdAt,Instant expiresAt,List<SellerOrder> sellers) {
        public Snapshot { sellers=List.copyOf(sellers); }
    }
    Snapshot save(Draft draft);
    Snapshot find(String orderNumber);
    record ReservationIds(List<UUID> inventory,List<UUID> coupons) {
        public ReservationIds { inventory=List.copyOf(inventory);coupons=List.copyOf(coupons); }
    }
    ReservationIds reservations(String orderNumber);
    /** Caller must hold a transaction. The same order lock fences approval, result and expiry. */
    Snapshot lock(String orderNumber);
    Snapshot authorize(String orderNumber,String browser,UUID member,Long authVersion,String guestToken);
    void transition(String orderNumber,String target);
}
