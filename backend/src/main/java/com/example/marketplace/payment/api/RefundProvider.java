package com.example.marketplace.payment.api;

import java.util.UUID;

/** Customer partial refunds are independent of late-approval whole-payment compensation. */
public interface RefundProvider {
 record Request(UUID paymentId,UUID orderId,UUID refundAttemptId,String idempotencyKey,long amount,String mode) { }
 record Outcome(String state,UUID refundAttemptId,long amount,String reference) { }
 Outcome submit(Request request);
 Outcome query(Request request);
}
