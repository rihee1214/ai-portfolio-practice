package com.example.marketplace.platform.api;
import java.util.UUID;
/** Caller transaction retains the job lock until commit; business locks must be acquired first. */
public interface JobLeases {
 void requireOwned(UUID jobId,UUID ownerToken);
 /** Platform-issued scheduler decision; callers cannot supply a claimed/checked timestamp. */
 record DueClaim(UUID jobId,int attempt,UUID ownerToken,java.time.Instant claimedAt,java.time.Instant scheduledAt) { }
 default DueClaim requireDueClaim(UUID jobId,UUID ownerToken) { throw new UnsupportedOperationException("DURABLE_DUE_CLAIM_REQUIRED"); }
}
