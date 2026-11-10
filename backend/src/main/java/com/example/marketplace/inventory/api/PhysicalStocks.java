package com.example.marketplace.inventory.api;
import java.util.List;
import java.util.UUID;
/** Inventory-owned concrete COMMITTED source units. Claim IDs never stand in for reservation identity. */
public interface PhysicalStocks {
 enum Kind { PURCHASE, EXCHANGE }
 enum Purpose { RETURN, EXCHANGE }
 record Source(UUID id,Kind kind,UUID reservationId,int reservationUnit,UUID checkoutId,UUID itemId,int ordinal,UUID skuId) { }
 record Unit(UUID physicalId,UUID sourceId) { }
 record Inbound(UUID inspectionId,UUID claimId,UUID checkoutId,Purpose purpose,List<Unit> units) { public Inbound { units=List.copyOf(units); } }
 record Receipt(UUID inspectionId,UUID claimId,Purpose purpose,List<Unit> units) { public Receipt { units=List.copyOf(units); } }
 Source purchased(UUID checkoutId,UUID itemId,int ordinal,UUID skuId);
 Source exchanged(UUID reservationId);
 Source source(UUID id);
 /** All source and target balances precede any reservation/source business lock. */
 void lockBalances(List<UUID> skus);
 Receipt restore(Inbound inbound);
}
