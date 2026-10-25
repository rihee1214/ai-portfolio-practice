package com.example.marketplace.catalog.api;
import java.util.List;
import java.util.UUID;
public interface Browse {
    record Query(String q,UUID category,String store,Long minPrice,Long maxPrice,boolean inStock,String sort,int page) { }
    record Item(String slug,String title,String description,UUID categoryId,String categoryName,String storeSlug,String storeName,Long minPrice,boolean soldOut) { }
    record Offer(UUID id,List<Skus.Option> options,long price,long available) { }
    record Detail(Item product,List<Offer> offers) { }
    Products.Page<Item> search(Query query);
    Detail detail(String slug);
    Detail forSku(UUID sku);
}
