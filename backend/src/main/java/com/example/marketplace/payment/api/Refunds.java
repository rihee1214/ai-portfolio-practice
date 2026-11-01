package com.example.marketplace.payment.api;

import java.time.Instant;
import java.util.UUID;

/** Trusted cancellation coordinator. All reads/writes retain parent-order then payment locks in the caller transaction. */
public interface Refunds {
 record Original(UUID paymentId,UUID orderId,long amount,String mode) { }
 record Reserve(UUID attemptId,UUID causeId,UUID paymentId,UUID orderId,UUID sellerOrderId,UUID claimId,String purpose,int sequence,long amount,String originalReference) { }
 record Attempt(UUID id,UUID paymentId,UUID orderId,UUID sellerOrderId,UUID claimId,String purpose,int sequence,long amount,String state,long version,String resultReference,Instant resultAt) { }
 Original original(UUID orderId);
 Attempt reserve(Reserve command);
 Attempt get(UUID attemptId);
 String originalReference(UUID attemptId);
 Attempt cancelUnsent(UUID attemptId);
 Attempt retryFailed(UUID attemptId,UUID newAttemptId,UUID newCauseId,String reason);
 Attempt requestQuery(UUID attemptId,UUID causeId,String reason);
}
