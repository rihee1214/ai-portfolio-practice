package com.example.marketplace.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trusted application contract; callers must authorize checkout and coordinate payment state. */
public interface Reservations {
    record Request(UUID id, UUID checkoutId, UUID orderItemId, UUID skuId, long quantity, Instant expiresAt) { }
    record Reservation(UUID id, UUID checkoutId, UUID orderItemId, UUID skuId, long quantity,
                       Instant expiresAt, String status) { }
    List<Reservation> reserve(List<Request> requests);
    List<Reservation> commit(UUID checkoutId, List<UUID> ids);
    List<Reservation> release(UUID checkoutId, List<UUID> ids);
    List<Reservation> expire(UUID checkoutId, List<UUID> ids);
    List<Reservation> inspect(UUID checkoutId,List<UUID> ids);
}
