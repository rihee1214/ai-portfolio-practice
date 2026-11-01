package com.example.marketplace.order.api;

import java.util.List;
import java.util.UUID;

/** Original, order-owned monetary and dispatch eligibility facts. Caller retains transaction locks. */
public interface CancellationOrders {
 record Unit(UUID itemId,int ordinal,UUID skuId,long net) { }
 record Selection(UUID orderId,UUID sellerOrderId,String mode,long revision,List<Unit> units,long originalShipping) {
  public Selection { units=List.copyOf(units); }
 }
 Selection lock(String number,OrderQueries.Authority authority,List<OrderConfirmations.Unit> units,long expectedRevision);
 /** Trusted result worker; retains parent and canonical original-unit locks before claim/payment locks. */
 void lockUnits(UUID orderId,List<OrderConfirmations.Unit> units);
 void requireSellerUnits(UUID orderId,UUID sellerOrderId,List<OrderConfirmations.Unit> units);
 boolean allCanceled(UUID sellerOrderId);
}
