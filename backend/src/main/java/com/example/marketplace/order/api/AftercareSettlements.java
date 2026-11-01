package com.example.marketplace.order.api;

import java.util.List;
import java.util.UUID;

/** Trusted irreversible payment-result facts. Order owns terminal evaluation and never reads claim tables. */
public interface AftercareSettlements {
 record Item(UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units,UUID paymentId,UUID refundAttemptId,String reference) { }
 record Shipping(UUID orderId,UUID sellerOrderId,UUID causeId,UUID paymentId,UUID refundAttemptId,long amount,String reference) { }
 void record(Item fact);
 void record(Shipping fact);
 void evaluate(UUID orderId);
 boolean customerCancellationSettled(UUID orderId,UUID paymentId);
}
