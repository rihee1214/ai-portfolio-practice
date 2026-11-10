package com.example.marketplace.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Caller retains its transaction; order owns the accepted original-delivery eligibility evidence. */
public interface ReturnApplications {
 record Current(UUID orderId,String orderState,long revision) { }
 record EligibilityUnit(UUID sellerOrderId,UUID itemId,int ordinal,String lifecycle,Instant deliveredAt,Instant dueAt,boolean eligible,String reason) { }
 record Eligibility(String orderNumber,String orderState,long revision,List<EligibilityUnit> units) { public Eligibility { units=List.copyOf(units); } }
 /** Trusted same-transaction order-owned revision. This grants no caller authority or mutation permission. */
 Current current(UUID orderId);
 /** Return-specific original-delivery claim window; distinct from purchase-confirmation eligibility. */
 Eligibility eligibility(String number,OrderQueries.Authority authority);
 record Accepted(UUID id,UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,Instant requestedAt,long windowSeconds,
                 ReturnSources.Selection source) { }
 Accepted begin(String number,OrderQueries.Authority authority,UUID id,UUID holdId,UUID causeId,
                List<OrderConfirmations.Unit> units);
 /** Current authority and genuine accepted graph; no new-application deadline is reapplied. */
 Accepted lockOwner(String number,OrderQueries.Authority authority,UUID id);
 Accepted lockSeller(OrderQueries.Authority authority,UUID id);
 enum RecordedHoldState { ACTIVE, RELEASED, REFUNDED }
 record Recorded(Accepted application,RecordedHoldState holdState,String holdOutcome,String orderState) { }
 /** Trusted same-transaction read of order evidence; this is neither caller authority nor operation permission. */
 Recorded lockRecorded(UUID id);
}
