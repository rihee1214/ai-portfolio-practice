package com.example.marketplace.inventory.api;

import java.util.List;
import java.util.UUID;

/** Trusted successful cancellation coordinator; never accepts a client supplied quantity or completion flag. */
public interface Restitutions {
 record Unit(UUID reservationId,UUID itemId,int ordinal,UUID skuId) { }
 record Request(UUID causeId,UUID checkoutId,List<Unit> units) { }
 record Receipt(UUID causeId,UUID checkoutId,List<Unit> units) { public Receipt { units=List.copyOf(units); } }
 Receipt restoreCancellation(Request request);
 record ReturnRequest(UUID inspectionId,UUID returnId,UUID checkoutId,List<Unit> units) { }
 /** The inspection coordinator and deferred attribution graph must commit with the inbound receipt. */
 Receipt restoreReturn(ReturnRequest request);
}
