package com.example.marketplace.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Order-owned original quantities and irreversible aftercare receipts. Caller retains order locks. */
public interface SettlementOrders {
    record Unit(UUID itemId,int ordinal,long gross,long productDiscount,long couponDiscount,long net,String state,
            UUID confirmationId,Instant confirmedAt,UUID refundCauseId,UUID refundAttemptId,Instant terminalAt,String refundKind) {
        public Unit(UUID itemId,int ordinal,long gross,long productDiscount,long couponDiscount,long net,String state,
                UUID confirmationId,Instant confirmedAt,UUID refundCauseId,UUID refundAttemptId,Instant terminalAt) {
            this(itemId,ordinal,gross,productDiscount,couponDiscount,net,state,confirmationId,confirmedAt,refundCauseId,refundAttemptId,terminalAt,null);
        }
    }
    record ShippingRefund(UUID causeId,UUID attemptId,long amount) { }
    record Hold(UUID id,Instant acquiredAt,Instant releasedAt,List<OrderConfirmations.Unit> units) {
        public Hold { units=List.copyOf(units); }
        public Hold(UUID id,Instant acquiredAt,Instant releasedAt) { this(id,acquiredAt,releasedAt,List.of()); }
    }
    record Seller(UUID orderId,UUID sellerOrderId,UUID storeId,String policyVersion,long shipping,List<Unit> units,
            List<ShippingRefund> shippingRefunds,String shippingState,Instant createdAt,List<Hold> holds) {
        public Seller(UUID orderId,UUID sellerOrderId,UUID storeId,String policyVersion,long shipping,List<Unit> units,
                List<ShippingRefund> shippingRefunds,String shippingState) {
            this(orderId,sellerOrderId,storeId,policyVersion,shipping,units,shippingRefunds,shippingState,Instant.MIN,List.of());
        }
        public Seller { units=List.copyOf(units);shippingRefunds=List.copyOf(shippingRefunds);holds=List.copyOf(holds); }
    }
    List<UUID> candidates(String policyVersion);
    List<Seller> lock(UUID orderId,String policyVersion);
}
