package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.util.List;
import java.util.UUID;

/** An original-unit collection fact is independent of inspection, refund and stock restoration. */
public interface ReturnCollections {
 enum Status { RECEIVED, LOST_REPORTED }
 record Unit(UUID itemId,int ordinal,Status status) { }
 record Command(String key,Long expectedRevision,List<Unit> units,String reason) { }
 Returns.Progress receive(UUID id,OrderQueries.Authority authority,Command command);
}
