package com.example.marketplace.claim.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.example.marketplace.order.api.OrderConfirmations.Unit;
import java.util.UUID;

public interface SettlementClaims {
    record Recovery(UUID causeId,UUID attemptId,long deducted,Instant occurredAt) { }
    record Reservation(UUID id,Instant acquiredAt,Instant releasedAt) { }
    record SupportRefund(UUID approvalId,UUID confirmationId,Unit unit,UUID attemptId,String purpose,long amount,Instant resultAt) { }
    /** First irreversible shipping decision for the current financial outcome, never a later reevaluation time. */
    record View(boolean pending,List<Recovery> recoveries,Instant shippingFinalizedAt,List<Reservation> reservations,
            Map<UUID,List<Unit>> attributedUnits,List<SupportRefund> supportRefunds,Instant retainedForConfirmationAt) {
        public View(boolean pending,List<Recovery> recoveries,Instant shippingFinalizedAt,List<Reservation> reservations) {
            this(pending,recoveries,shippingFinalizedAt,reservations,Map.of(),List.of(),null);
        }
        public View(boolean pending,List<Recovery> recoveries,Instant shippingFinalizedAt) { this(pending,recoveries,shippingFinalizedAt,List.of()); }
        public View {
            supportRefunds=List.copyOf(supportRefunds);
            recoveries=List.copyOf(recoveries);reservations=List.copyOf(reservations);
            attributedUnits=attributedUnits.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,entry->List.copyOf(entry.getValue())));
        }
    }
    /** Applied receipts and complete reservation intervals, including terminated assessments, under the caller's order lock. */
    View lock(UUID sellerOrderId);
}
