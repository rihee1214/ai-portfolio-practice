package com.example.marketplace.claim.api;
import com.example.marketplace.order.api.OrderQueries;
import java.util.List;
import java.util.UUID;
public interface ExchangePhysical {
 enum Status { RECEIVED, LOST_REPORTED }
 enum Condition { SALEABLE, DEFECTIVE }
 record Received(UUID itemId,int ordinal,Status status) { }
 record Inspected(UUID itemId,int ordinal,Condition condition,Exchanges.Responsibility responsibility) { }
 record CollectionCommand(String key,Long expectedRevision,List<Received> units,String reason) { }
 record InspectionCommand(String key,Long expectedRevision,List<Inspected> units,String reason) { }
 Exchanges.Progress receive(UUID exchangeId,OrderQueries.Authority authority,CollectionCommand command);
 Exchanges.Progress inspect(UUID exchangeId,OrderQueries.Authority authority,InspectionCommand command);
}
