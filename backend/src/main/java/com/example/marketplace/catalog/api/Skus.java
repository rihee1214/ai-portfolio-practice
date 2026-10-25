package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

public interface Skus {
    record Option(String name,String value) { }
    record Sku(UUID id,String code,List<Option> options,long price,boolean active,long version) { }
    record Input(List<Option> options,Long price,Boolean active,Long version) { }
    /** Inventory authorization and target validation. Writes require the caller's transaction. */
    void inventoryAccess(UUID actor,UUID product,UUID sku,boolean administrator,boolean write);
    Sku save(UUID owner,UUID product,UUID id,Input input);
    Products.Page<Sku> list(UUID owner,UUID product,int page);
    Products.Page<Sku> published(String slug,int page);
}
