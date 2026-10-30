package com.example.marketplace.platform.api;
import java.util.UUID;
/** Caller transaction retains the job lock until commit; business locks must be acquired first. */
public interface JobLeases {
 void requireOwned(UUID jobId,UUID ownerToken);
}
