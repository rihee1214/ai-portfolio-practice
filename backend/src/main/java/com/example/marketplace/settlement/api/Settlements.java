package com.example.marketplace.settlement.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Original cause-linked immutable ledger and weekly mock-payment consumption contract. */
public interface Settlements {
    final class AssignmentConflict extends RuntimeException {
        private final List<LocalDate> conflictingWeeks;
        public AssignmentConflict(List<LocalDate> weeks) { super("SETTLEMENT_UNPAID_BATCH_CONFLICT");this.conflictingWeeks=weeks.stream().distinct().sorted().toList(); }
        public List<LocalDate> conflictingWeeks() { return conflictingWeeks; }
    }
    record Actor(UUID member,String session,Long version) { }
    record Policy(String version,int rateBasisPoints,Instant effectiveAt,String zone,LocalDate currentWeekStart) { }
    record Open(UUID key,LocalDate weekStart) { }
    record Change(UUID key,long expectedVersion) { }
    record Held(UUID sellerOrderId,String reason) { }
    record EligibilityHold(UUID id,Instant acquiredAt,Instant releasedAt) { }
    record EligibilityReservation(UUID id,String kind,Instant acquiredAt,Instant releasedAt) { }
    /** occurredAt is the economic fact; eligibleAt and eligibilityHolds are this batch's payment-readiness evidence. */
    record Item(UUID id,UUID storeId,UUID orderId,UUID sellerOrderId,UUID itemId,Integer ordinal,UUID causeId,
            UUID paymentId,UUID refundAttemptId,String kind,String amount,String fee,String net,Instant occurredAt,Instant eligibleAt,String policyVersion,List<EligibilityHold> eligibilityHolds,List<EligibilityReservation> eligibilityReservations) { }
    record Summary(UUID id,LocalDate weekStart,Instant periodStart,Instant periodEnd,String state,long version,
            boolean closed,String gross,String fee,String net) { }
    record Batch(UUID id,LocalDate weekStart,Instant periodStart,Instant periodEnd,String state,long version,
            boolean closed,List<Item> items,String gross,String fee,String net,List<Held> held) { }
    Policy policy(Actor actor);
    List<Summary> list(Actor actor);
    Batch get(UUID id,Actor actor);
    Batch open(Open command,Actor actor);
    Batch review(UUID id,Change command,Actor actor);
    Batch simulatePay(UUID id,Change command,Actor actor);
}
