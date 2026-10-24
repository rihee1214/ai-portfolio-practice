package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

public interface Products {
    record Input(UUID categoryId,String slug,String title,String description,String state,Long version) { }
    record Product(UUID id,UUID categoryId,String slug,String title,String description,String state,long version,boolean hiddenByAdmin) { }
    record PublicProduct(String slug,String title,String description,UUID categoryId,String storeSlug) { }
    record Page<T>(List<T> items,int page,boolean hasMore) { }
    Product save(UUID owner,UUID id,Input input);
    Product own(UUID owner,UUID id);
    Page<Product> list(UUID owner,int page);
    PublicProduct published(String slug);
    Page<PublicProduct> published(int page);
}
