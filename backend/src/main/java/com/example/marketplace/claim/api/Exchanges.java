package com.example.marketplace.claim.api;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.catalog.api.ExchangeOffers;
import com.example.marketplace.inventory.api.ExchangeReservations;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface Exchanges {
 enum Responsibility { BUYER, SELLER }
 record Unit(UUID itemId,int ordinal,UUID targetSkuId,Responsibility responsibility) { }
 record Request(String key,Long expectedRevision,List<Unit> units,String reason) { }
 record Receipt(UUID exchangeId,UUID holdId,String state,long version,long orderRevision,Instant requestedAt,String reason,List<Unit> units) { public Receipt { units=List.copyOf(units); } }
 record Command(String key,Long expectedRevision,String reason) { }
 record History(String action,long version,String reason,Instant occurredAt) { }
 record Physical(UUID itemId,int ordinal,UUID physicalId,int round,UUID actualSkuId,String collection,UUID collectionId,String inspection,UUID inspectionId,Responsibility responsibility,UUID restitutionId) { }
 record Progress(Receipt accepted,String state,long version,List<History> history,List<ExchangeReservations.Reservation> reservations,List<ExchangeOffers.Offer> approvedOffers,String policyVersion,List<Physical> physical) { public Progress { history=List.copyOf(history);reservations=List.copyOf(reservations);approvedOffers=List.copyOf(approvedOffers);physical=physical==null?List.of():List.copyOf(physical); } public Progress(Receipt accepted,String state,long version,List<History> history,List<ExchangeReservations.Reservation> reservations,List<ExchangeOffers.Offer> approvedOffers,String policyVersion) { this(accepted,state,version,history,reservations,approvedOffers,policyVersion,List.of()); } }
 record Choice(UUID itemId,int ordinal,UUID sourceSkuId,String title,String originalUnitPrice,List<ExchangeOffers.Offer> offers) { public Choice { offers=List.copyOf(offers); } }
 record Page(long orderRevision,List<Progress> exchanges,List<Choice> choices) { public Page { exchanges=List.copyOf(exchanges);choices=List.copyOf(choices); } }
 Receipt request(String number,OrderQueries.Authority authority,Request command);
 Progress approve(UUID id,OrderQueries.Authority authority,Command command);
 Progress get(String number,UUID id,OrderQueries.Authority authority);
 Page owner(String number,OrderQueries.Authority authority);
 Page seller(UUID sellerOrderId,OrderQueries.Authority authority);
}
