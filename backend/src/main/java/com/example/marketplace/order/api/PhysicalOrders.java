package com.example.marketplace.order.api;
import com.example.marketplace.inventory.api.PhysicalStocks;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
/** Order owns immutable physical lineage and the current pointer, independently of the financial original unit. */
public interface PhysicalOrders {
 enum BucketKind { BASIC, EXCHANGE }
 record Delivery(UUID bucketId,BucketKind kind,UUID shipmentId,UUID eventId,Instant deliveredAt) { }
 record Source(UUID id,UUID orderId,UUID sellerOrderId,UUID itemId,int ordinal,int round,UUID predecessorId,UUID originalPhysicalId,PhysicalStocks.Source stock,Delivery delivery) { }
 /** Binds a new accepted request to its locked current physical; original application JSON is a separate immutable anchor. */
 List<Source> bind(ExchangeApplications.Source accepted);
 /** Always returns the source fixed at acceptance, even after a successor becomes current. */
 List<Source> application(UUID exchangeId);
 /** Initializes genuine historical original-delivery evidence, never claim eligibility or a synthetic workflow. */
 Source initial(UUID orderId,UUID sellerOrderId,OrderConfirmations.Unit unit);
 Source current(UUID orderId,UUID sellerOrderId,OrderConfirmations.Unit unit);
 Source recorded(UUID physicalId);
 record Successor(UUID id,UUID predecessorId,UUID stockSourceId,UUID allocationId) { }
 record Dispatch(UUID exchangeId,UUID orderId,UUID sellerOrderId,UUID shipmentId,UUID eventId,Instant dispatchedAt,List<Successor> units) { public Dispatch { units=List.copyOf(units); } }
 void dispatched(Dispatch dispatch);
 record Delivered(UUID shipmentId,UUID eventId,Instant deliveredAt) { }
 void delivered(Delivered fact);
 /** Actual own delivery/lineage proof is required; this is never a generic hold release. */
 void completeExchange(UUID exchangeId,UUID completionId);
}
