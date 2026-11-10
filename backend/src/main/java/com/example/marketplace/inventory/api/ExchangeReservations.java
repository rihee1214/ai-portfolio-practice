package com.example.marketplace.inventory.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Dedicated exchange allocations never replace the original purchase reservation identity. */
public interface ExchangeReservations {
 record Purchased(UUID reservationId,UUID checkoutId,UUID itemId,int ordinal,UUID skuId) { }
 Purchased purchased(UUID checkoutId,UUID itemId,int ordinal,UUID skuId);
 record Unit(UUID physicalId,UUID itemId,int ordinal,UUID sourceReservationId,UUID sourceSkuId,UUID targetSkuId) { }
 record Request(UUID exchangeId,UUID orderId,UUID sellerOrderId,UUID checkoutId,int sequence,Instant approvedAt,Instant expiresAt,List<Unit> units) { public Request { units=List.copyOf(units); } }
 record Reservation(UUID id,UUID exchangeId,UUID physicalId,UUID targetSkuId,long quantity,int sequence,String status,Instant approvedAt,Instant expiresAt) { }
 List<Reservation> reserve(Request request);
 record PhysicalBinding(UUID reservationId,UUID physicalId,UUID committedSourceId) { }
 void bindSources(UUID exchangeId,List<PhysicalBinding> bindings);
 List<Reservation> get(UUID exchangeId);
 /** Locks current allocations and all target balances in canonical order before a lifecycle decision. */
 List<Reservation> lockCurrent(UUID exchangeId);
 record Extension(UUID id,UUID exchangeId,int sequence,Instant previousExpiresAt,Instant extendedExpiresAt,Instant extendedAt) { }
 /** Original approval/seven-day rows remain immutable; this is a separately receipted deadline. */
 List<Reservation> extend(Extension extension);
 enum ReleaseReason { EXPIRED, REFUND_CONVERSION }
 record Release(UUID id,UUID exchangeId,int sequence,ReleaseReason reason,Instant releasedAt) { }
 /** Releases only this actual allocation generation. COMMITTED allocations can never be released. */
 List<Reservation> release(Release command);
 record Released(UUID id,UUID exchangeId,int sequence,ReleaseReason reason,Instant releasedAt,List<Reservation> reservations) { public Released { reservations=List.copyOf(reservations); } }
 Released released(UUID exchangeId,int sequence);
 long available(UUID skuId);
 /** Commits only the dedicated allocation; caller must retain all order and source/target stock locks. */
 List<Reservation> commit(UUID exchangeId,UUID dispatchId);
}
