package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderConfirmations;
import java.util.List;
import java.util.UUID;

/** Attributed refund business identity; registering RETURN never proves return approval or inspection/completion. */
public interface FinancialIntents {
 record Reference(UUID id,UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,String kind,List<OrderConfirmations.Unit> units) { public Reference { units=List.copyOf(units); } }
 Reference registerCancellation(UUID id,UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units);
 Reference registerReturn(String number,com.example.marketplace.order.api.OrderQueries.Authority authority,UUID id,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units);
 /** Explicit exchange-to-refund identity; preserves the actual EXCHANGE hold and physical workflow. */
 Reference registerExchangeRefund(String number,com.example.marketplace.order.api.OrderQueries.Authority authority,UUID exchangeId);
 Reference lock(UUID id);
 void bindRefund(UUID intentId,UUID attemptId);
 void bindReturnRefund(UUID intentId,UUID attemptId,UUID assessmentId);
 void bindExchangeRefund(UUID intentId,UUID attemptId,UUID assessmentId);
 /** Original seller SHIPPING obligation; never an ITEM/fee-assessment binding. */
 void bindReturnShipping(UUID intentId,UUID attemptId);
 void bindExchangeRefundShipping(UUID intentId,UUID attemptId);
 record Result(UUID attemptId,long version,UUID assessmentId,String state,String amount,String reference,java.time.Instant resultAt) { }
 /** Durable accepted-command results, for a trusted coordinator to resume independently of job retention. */
 List<Result> results(UUID intentId);
}
