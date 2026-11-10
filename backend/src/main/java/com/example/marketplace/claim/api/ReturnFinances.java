package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.util.UUID;

/** Final inspected financial terms for the current buyer; a quote is not consent or a reservation. */
public interface ReturnFinances {
 record Quote(UUID returnId,long returnRevision,ReturnFees.Quote feeQuote) { }
 record Consent(String key,Long expectedRevision,UUID quoteId,String quoteFingerprint,boolean consent,boolean legacyConsent) { }
 record Accepted(Returns.Progress progress,UUID assessmentId,UUID refundAttemptId) { }
 Quote quote(String number,UUID id,OrderQueries.Authority authority,long expectedRevision);
 Accepted consent(String number,UUID id,OrderQueries.Authority authority,Consent command);
}
