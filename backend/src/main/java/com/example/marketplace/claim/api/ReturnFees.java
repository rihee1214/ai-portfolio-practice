package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Common financial contract for a future coordinator. This API has no RETURN web binding or completion effects. */
public interface ReturnFees {
 enum Responsibility { BUYER, SELLER }
 record Target(UUID itemId,int ordinal,Responsibility responsibility) { }
 record UnitQuote(UUID itemId,int ordinal,UUID originalShipmentId,String net,String policyFee,String policySource,String policyVersion,long policyRevision,Responsibility responsibility,String deducted) { }
 record BucketQuote(UUID originalShipmentId,long revision,String assessedTotal,String selectedMaximum,String target,String increment,String deducted,String additionalDue) { }
 record Quote(UUID id,String fingerprint,String orderNumber,UUID sellerOrderId,long orderRevision,Instant quotedAt,List<UnitQuote> units,List<BucketQuote> buckets,String net,String increment,String refund,String additionalDue,boolean legacyConsentRequired) {
  public Quote { units=List.copyOf(units);buckets=List.copyOf(buckets); }
 }
 Quote quote(String number,OrderQueries.Authority authority,List<Target> targets);
 record Accepted(UUID id,UUID quoteId,UUID intentId,String refund,String additionalDue,Instant acceptedAt) { }
 Accepted accept(String number,OrderQueries.Authority authority,UUID quoteId,String fingerprint,String key,UUID intentId,boolean legacyConsent);
 void bindRefund(String number,OrderQueries.Authority authority,UUID assessmentId,UUID attemptId);
 record Apply(UUID assessmentId,UUID attemptId,long resultVersion,UUID jobId,UUID ownerToken) { }
 record Applied(UUID assessmentId,UUID attemptId,long resultVersion,String reference,String increment,String deducted,String additionalDue,Instant appliedAt) { }
 record Withdrawn(UUID assessmentId,UUID attemptId,Instant withdrawnAt) { }
 Applied apply(Apply command);
 Withdrawn withdraw(String number,OrderQueries.Authority authority,UUID assessmentId);
 record SupportReceipt(UUID commandId,UUID assessmentId,UUID refundAttemptId,String action,long revision,Instant acceptedAt) { }
 /** Explicit definitive-failure retry; no RETURN HTTP endpoint. */
 SupportReceipt retryFailed(String number,OrderQueries.Authority operator,UUID assessmentId,ClaimSupport.Command command);
}
