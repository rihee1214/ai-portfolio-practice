package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

public interface Skus {
    record Option(String name,String value) { }
    record Sku(UUID id,String code,List<Option> options,long price,boolean active,long version) { }
    record Input(List<Option> options,Long price,Boolean active,Long version) { }
    Sku save(UUID owner,UUID product,UUID id,Input input);
    Products.Page<Sku> list(UUID owner,UUID product,int page);
    Products.Page<Sku> published(String slug,int page);
}
