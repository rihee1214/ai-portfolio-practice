package com.example.marketplace.inventory.api;

import java.util.List;
import java.util.UUID;

/** Trusted successful cancellation coordinator; never accepts a client supplied quantity or completion flag. */
public interface Restitutions {
 record Unit(UUID reservationId,UUID itemId,int ordinal,UUID skuId) { }
 record Request(UUID causeId,UUID checkoutId,List<Unit> units) { }
 record Receipt(UUID causeId,UUID checkoutId,List<Unit> units) { public Receipt { units=List.copyOf(units); } }
 Receipt restoreCancellation(Request request);
}
