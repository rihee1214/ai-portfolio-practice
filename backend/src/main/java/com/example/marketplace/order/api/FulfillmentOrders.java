package com.example.marketplace.order.api;
import java.util.List;
import java.util.UUID;
/** Trusted application port only: no HTTP system identity parameter. All methods require a caller transaction. */
public interface FulfillmentOrders {
 record Candidate(UUID id,String number) { }
 record Paid(UUID orderId,List<OrderQueries.SellerOrder> sellers) { public Paid { sellers=List.copyOf(sellers); } }
 /** Takes the same exclusive order lock as payment; non-PAID returns null. */
 Paid lockPaid(String number);
 List<Candidate> paidCandidates(UUID after,int size);
}
