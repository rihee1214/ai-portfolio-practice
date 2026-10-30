package com.example.marketplace.order.api;
import java.util.List;
import java.util.UUID;
/** Trusted application port. Caller must retain its transaction and acquire identity before this order lock. */
public interface OrderUnitHolds {
 record Hold(UUID id,UUID orderId,UUID causeId,String kind,List<OrderConfirmations.Unit> units,String state) { public Hold { units=List.copyOf(units); } }
 Hold acquire(UUID orderId,UUID holdId,UUID causeId,String kind,List<OrderConfirmations.Unit> units);
 Hold release(UUID orderId,UUID holdId,UUID causeId,String outcome);
 Hold completeRefund(UUID orderId,UUID holdId,UUID causeId,String refundReference);
}
