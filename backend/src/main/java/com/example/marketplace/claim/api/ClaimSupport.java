package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.UUID;

/** Explicit current SUPPORT commands. They never set a provider result or create an automatic retry policy. */
public interface ClaimSupport {
 record Command(String key,Long expectedRevision,String reason) { }
 record Receipt(UUID commandId,UUID claimId,UUID refundAttemptId,String purpose,String action,long revision,Instant acceptedAt) { }
 Receipt queryRefund(String number,OrderQueries.Authority operator,UUID claimId,String purpose,Command command);
 Receipt retryFailed(String number,OrderQueries.Authority operator,UUID claimId,String purpose,Command command);
}
