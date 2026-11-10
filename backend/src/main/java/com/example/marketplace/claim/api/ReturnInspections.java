package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.util.List;
import java.util.UUID;

/** Physical seller judgment proposes responsibility; it never supplies buyer financial consent. */
public interface ReturnInspections {
 enum Condition { SALEABLE, DEFECTIVE }
 record Unit(UUID itemId,int ordinal,Condition condition,Returns.Responsibility responsibility) { }
 record Command(String key,Long expectedRevision,List<Unit> units,String reason) { }
 Returns.Progress inspect(UUID id,OrderQueries.Authority authority,Command command);
}
