package com.example.marketplace.order.api;

import java.util.List;
import java.util.UUID;

/** Trusted irreversible payment-result facts. Order owns terminal evaluation and never reads claim tables. */
public interface AftercareSettlements {
 record Item(UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units,UUID paymentId,UUID refundAttemptId,String reference) { }
 record Shipping(UUID orderId,UUID sellerOrderId,UUID causeId,UUID paymentId,UUID refundAttemptId,long amount,String reference) { }
 record AftercareUnit(UUID itemId,int ordinal,UUID skuId,long net,String state,UUID activeHoldId,UUID refundCauseId,boolean dispatched,UUID confirmationId,UUID cancellationCauseId,UUID returnCompletionId,UUID exchangeRefundCompletionId) {
  public AftercareUnit(UUID itemId,int ordinal,UUID skuId,long net,String state,UUID activeHoldId,UUID refundCauseId,boolean dispatched,UUID confirmationId,UUID cancellationCauseId,UUID returnCompletionId) { this(itemId,ordinal,skuId,net,state,activeHoldId,refundCauseId,dispatched,confirmationId,cancellationCauseId,returnCompletionId,null); }
  @Override public String toString() {
   String original="AftercareUnit[itemId="+itemId+", ordinal="+ordinal+", skuId="+skuId+", net="+net+", state="+state+", activeHoldId="+activeHoldId+", refundCauseId="+refundCauseId+", dispatched="+dispatched+", confirmationId="+confirmationId+", cancellationCauseId="+cancellationCauseId+", returnCompletionId="+returnCompletionId;
   return original+(exchangeRefundCompletionId==null?"":", exchangeRefundCompletionId="+exchangeRefundCompletionId)+"]";
  }
 }
 record AftercareView(UUID orderId,UUID sellerOrderId,UUID checkoutId,String orderNumber,String orderState,String mode,long originalShipping,long originalPaidAmount,String quoteFingerprint,List<AftercareUnit> units,List<UUID> recordedPaymentIds) {
  public AftercareView { units=List.copyOf(units);recordedPaymentIds=List.copyOf(recordedPaymentIds); }
 }
 /** Same transaction, canonical order-owned evidence; no caller financial-eligibility flags. */
 AftercareView lockAftercare(UUID sellerOrderId);
 record ConfirmationUnit(UUID itemId,int ordinal,UUID sellerOrderId) { }
 record ConfirmationFact(UUID confirmationId,UUID orderId,String cause,java.time.Instant confirmedAt,long revision,List<ConfirmationUnit> units) { public ConfirmationFact { units=List.copyOf(units); } }
 ConfirmationFact lockConfirmation(UUID confirmationId);
 record ReturnCompletion(UUID returnId,UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units,UUID paymentId,UUID itemAttemptId,String reference) { }
 record ShippingDecision(UUID decisionId,UUID orderId,UUID sellerOrderId,long revision,String state,String reason,long originalShipping,String fingerprint,List<OrderConfirmations.Unit> units) { }
 void recordReturnCompletion(ReturnCompletion fact);
 record ExchangeRefundCompletion(UUID exchangeId,UUID conversionId,UUID intentId,UUID readyId,UUID shippingDecisionId,UUID orderId,UUID sellerOrderId,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units,UUID paymentId,UUID itemAttemptId,long resultVersion,String reference) { public ExchangeRefundCompletion { units=List.copyOf(units); } }
 /** Exact EXCHANGE hold and original financial units; claim/payment evidence is linked by typed DB graph. */
 void recordExchangeRefundCompletion(ExchangeRefundCompletion fact);
 void recordShippingDecision(ShippingDecision fact);
 void recordShippingSettlement(Shipping fact);
 /** Same transaction, exact stored original-S receipt; absence is not a caller eligibility flag. */
 java.util.Optional<Shipping> lockShippingSettlement(UUID sellerOrderId);
 void record(Item fact);
 void record(Shipping fact);
 void evaluate(UUID orderId);
 boolean customerCancellationSettled(UUID orderId,UUID paymentId);
}
