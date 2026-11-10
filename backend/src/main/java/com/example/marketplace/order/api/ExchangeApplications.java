package com.example.marketplace.order.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Order-owned original-unit and physical-round evidence; all mutation calls retain the caller transaction. */
public interface ExchangeApplications {
 record Unit(UUID itemId,int ordinal,UUID physicalId,int round,UUID skuId,UUID reservationId,UUID checkoutId,UUID deliveryEventId,UUID deliveryBucketId,Instant deliveredAt,String originalUnitPrice,String originalNet,com.example.marketplace.catalog.api.ReturnPolicy returnPolicy) { }
 record Source(UUID id,UUID orderId,UUID sellerOrderId,UUID storeId,UUID holdId,UUID causeId,Instant requestedAt,long orderRevision,long windowSeconds,List<Unit> units) { public Source { units=List.copyOf(units); } }
 Source begin(String number,OrderQueries.Authority authority,UUID id,UUID holdId,UUID causeId,List<OrderConfirmations.Unit> units);
 Source lockOwner(String number,OrderQueries.Authority authority,UUID id);
 Source lockSeller(OrderQueries.Authority authority,UUID id);
 /** Trusted order-owned evidence in the caller transaction; grants no caller authority. */
 Source lockRecorded(UUID id);
 /** Original financial units and the physical fixed at acceptance, with an actual active EXCHANGE hold.
  * This consumes the accepted application, never reopens its original seven-day request window. */
 ReturnSources.Selection lockRefundSource(String number,OrderQueries.Authority authority,UUID exchangeId);
}
