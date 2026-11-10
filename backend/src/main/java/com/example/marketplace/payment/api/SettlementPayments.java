package com.example.marketplace.payment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SettlementPayments {
    record Refund(UUID id,UUID causeId,UUID sellerOrderId,UUID claimId,int sequence,String purpose,long amount,String state,
            Instant resultAt,Instant reservedAt,Instant releasedAt) {
        public Refund(UUID id,UUID causeId,UUID sellerOrderId,UUID claimId,int sequence,String purpose,long amount,String state,Instant resultAt) {
            this(id,causeId,sellerOrderId,claimId,sequence,purpose,amount,state,resultAt,Instant.MIN,resultAt);
        }
    }
    record Payment(UUID id,long amount,String mode,List<Refund> refunds) { }
    /** Caller holds the order lock; projection does not call any provider. */
    List<Payment> lock(UUID orderId);
}
