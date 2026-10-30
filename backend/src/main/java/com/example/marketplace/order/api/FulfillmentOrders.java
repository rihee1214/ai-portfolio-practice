package com.example.marketplace.order.api;
import java.util.List;
import java.util.UUID;
/** Trusted application port only: no HTTP system identity parameter. All methods require a caller transaction. */
public interface FulfillmentOrders {
 record Candidate(UUID id,String number) { }
 record Paid(UUID orderId,List<OrderQueries.SellerOrder> sellers) { public Paid { sellers=List.copyOf(sellers); } }
 /** Takes the same exclusive order lock as payment; non-PAID returns null. */
 Paid lockPaid(String number);
 /** Locks an existing parent, including CLOSED for prior receipt lookup. New writes still require PAID. */
 Paid lockExisting(String number);
 void lockUnits(UUID sellerOrderId);
 record Dispatch(int schemaVersion,UUID orderId,UUID sellerOrderId,UUID shipmentId,UUID eventId,java.time.Instant occurredAt,List<Unit> units) { public Dispatch { if(units!=null) { units=List.copyOf(units); } } }
 void recordDispatch(Dispatch fact);
 List<Candidate> paidCandidates(UUID after,int size);
 record Unit(UUID itemId,int ordinal) { }
 record InitialDelivery(int schemaVersion,UUID orderId,UUID sellerOrderId,UUID shipmentId,UUID eventId,java.time.Instant occurredAt,List<Unit> units) { public InitialDelivery { if(units!=null) { units=List.copyOf(units); } } }
 /** Trusted applied first-delivery fact. Caller owns the order exclusive lock and transaction. */
 void recordInitialDelivery(InitialDelivery fact);
}
