package com.example.marketplace.payment.api;

import java.util.UUID;

/** Customer refunds use their own immutable request and never late-approval compensation. */
public interface RefundProvider {
 record Request(UUID paymentId,UUID orderId,UUID refundAttemptId,String idempotencyKey,long amount,String mode,String marker,String fingerprint,String transactionKey) {
  public Request(UUID paymentId,UUID orderId,UUID refundAttemptId,String idempotencyKey,long amount,String mode) { this(paymentId,orderId,refundAttemptId,idempotencyKey,amount,mode,null,null,null); }
  @Override public String toString() { return "RefundRequest[redacted,mode="+mode+"]"; }
 }
 record Receipt(String provider,String mode,UUID paymentId,UUID orderId,long amount,long originalAmount,String merchantId,String currency,String transactionKey,String requestFingerprint,String responseFingerprint,String evidence) {
  @Override public String toString() { return "RefundReceipt[redacted,provider="+provider+"]"; }
 }
 record Outcome(String state,UUID refundAttemptId,long amount,String reference,Receipt receipt,String evidence) {
  public Outcome(String state,UUID refundAttemptId,long amount,String reference) { this(state,refundAttemptId,amount,reference,null,"{}"); }
  @Override public String toString() { return "RefundOutcome[state="+state+",redacted]"; }
 }
 default boolean supports(String mode) { return "DEMO".equals(mode); }
 Outcome submit(Request request);
 Outcome query(Request request);
}
