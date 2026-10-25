package com.example.marketplace.inventory.api;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
public interface Inventory {
    record Balance(UUID id,String code,long onHand,long reserved,long available,long version) { }
    record Change(Long onHand,Long version,String reason,UUID requestId) { }
    record Movement(UUID id,UUID actorId,String cause,String reason,long beforeOnHand,long afterOnHand,long reserved,long version,Instant occurredAt) { }
    record Page<T>(List<T> items,int page,boolean hasMore) { }
    Page<Balance> list(UUID actor,UUID product,boolean administrator,int page);
    Balance adjust(UUID actor,UUID product,UUID sku,boolean administrator,Change change);
    Page<Movement> history(UUID actor,UUID product,UUID sku,boolean administrator,int page);
}
