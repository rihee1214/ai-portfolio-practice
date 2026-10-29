package com.example.marketplace.payment.api;
import java.util.UUID;
/** Adapter port: stable attempt identity is the provider idempotency key, amount/mode are persisted server values. */
public interface PaymentProvider {
 enum Result { SUCCEEDED, FAILED, UNKNOWN }
 record Request(UUID attemptId,UUID orderId,long amount,String mode) { }
 Result approve(Request request);
 Result query(Request request);
 Result cancel(Request request);
 Result queryCancellation(Request request);
}
