package com.example.marketplace.claim.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SettlementClaims {
    record Recovery(UUID causeId,UUID attemptId,long deducted,Instant occurredAt) { }
    record Reservation(UUID id,Instant acquiredAt,Instant releasedAt) { }
    /** First irreversible shipping decision for the current financial outcome, never a later reevaluation time. */
    record View(boolean pending,List<Recovery> recoveries,Instant shippingFinalizedAt,List<Reservation> reservations) {
        public View(boolean pending,List<Recovery> recoveries,Instant shippingFinalizedAt) { this(pending,recoveries,shippingFinalizedAt,List.of()); }
        public View { recoveries=List.copyOf(recoveries);reservations=List.copyOf(reservations); }
    }
    /** Applied receipts and complete reservation intervals, including terminated assessments, under the caller's order lock. */
    View lock(UUID sellerOrderId);
}
