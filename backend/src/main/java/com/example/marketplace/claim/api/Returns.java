package com.example.marketplace.claim.api;

import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A physical return application is independent of a financial fee assessment. */
public interface Returns {
 enum Responsibility { BUYER, SELLER }
 record Unit(UUID itemId,int ordinal,Responsibility responsibility) { }
 record Request(String key,Long expectedRevision,List<Unit> units,String reason) { }
 record Receipt(UUID returnId,UUID holdId,UUID intentId,String state,long version,long orderRevision,
                Instant requestedAt,String reason,List<Unit> units) {
  public Receipt { units=List.copyOf(units); }
 }
 Receipt request(String number,OrderQueries.Authority authority,Request command);
 record Command(String key,Long expectedRevision,String reason) { }
 record History(String action,long version,String reason,Instant occurredAt) { }
 record Physical(UUID itemId,int ordinal,String collection,String inspection,Responsibility responsibility,UUID collectionId,UUID inspectionId,UUID restitutionId,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.example.marketplace.order.api.PhysicalOrders.Source physical) {
  public Physical(UUID itemId,int ordinal,String collection,String inspection,Responsibility responsibility,UUID collectionId,UUID inspectionId,UUID restitutionId) { this(itemId,ordinal,collection,inspection,responsibility,collectionId,inspectionId,restitutionId,null); }
 }
 record Progress(Receipt accepted,String state,long version,List<History> history,List<Physical> physical) {
  public Progress { history=List.copyOf(history);physical=physical==null?List.of():List.copyOf(physical); }
  public Progress(Receipt accepted,String state,long version,List<History> history) { this(accepted,state,version,history,List.of()); }
 }
 Progress get(String number,UUID id,OrderQueries.Authority authority);
 Progress approve(UUID id,OrderQueries.Authority authority,Command command);
 Progress reject(UUID id,OrderQueries.Authority authority,Command command);
 Progress withdraw(String number,UUID id,OrderQueries.Authority authority,Command command);
}
