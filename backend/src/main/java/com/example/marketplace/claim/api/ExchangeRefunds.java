package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.util.UUID;

/** Buyer-authorized conversion of the actual inspected EXCHANGE, never an unshipped cancellation. */
public interface ExchangeRefunds {
 record Quote(UUID exchangeId,long revision,ReturnFees.Quote quote) { }
 record Consent(String key,Long expectedRevision,UUID quoteId,String quoteFingerprint,boolean consent,boolean legacyConsent,String reason) { }
 record Accepted(Exchanges.Progress progress,UUID assessmentId,UUID refundAttemptId) { }
 Quote quote(String number,UUID exchangeId,OrderQueries.Authority authority,long expectedRevision);
 Accepted consent(String number,UUID exchangeId,OrderQueries.Authority authority,Consent command);
}
