package com.example.marketplace.promotion.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Trusted in-process checkout/refund coordinator contract. Never bind these commands to public HTTP. */
public interface CouponReservations {
 record Request(UUID reservationId,UUID checkoutId,UUID sellerOrderRef,UUID memberId,UUID storeId,UUID issuedCouponId,Quotes.Quote quoteSnapshot,Instant expiresAt){}
 record Reservation(UUID id,UUID checkoutId,UUID sellerOrderRef,UUID memberId,UUID storeId,UUID issuedCouponId,String state,Instant expiresAt,long version){}
 record RefundCompletion(boolean full,boolean confirmed){}
 record RestoreRequest(UUID causeId,UUID sellerOrderRef,UUID reservationId,RefundCompletion refundCompletion){}
 /** Immutable result at restoration time; reusable is not current permission to reserve again. */
 record Restoration(UUID causeId,UUID sellerOrderRef,UUID reservationId,boolean restored,boolean reusable,String reason){}
 List<Reservation> reserve(List<Request> requests);
 List<Reservation> commit(UUID checkoutId,List<UUID> ids);
 List<Reservation> release(UUID checkoutId,List<UUID> ids);
 List<Reservation> expire(UUID checkoutId,List<UUID> ids);
 Restoration restore(RestoreRequest request);
}
