package com.example.marketplace.catalog.api;
import java.util.List;
import java.util.UUID;
public interface Browse {
    record Query(String q,UUID category,String store,Long minPrice,Long maxPrice,boolean inStock,String sort,int page) { }
    record Thumbnail(UUID assetId,String alt) { }
    record Item(String slug,String title,String description,UUID categoryId,String categoryName,String storeSlug,String storeName,Long minPrice,boolean soldOut,ReturnPolicy returnPolicy,Thumbnail thumbnail) { }
    record Offer(UUID id,List<Skus.Option> options,long price,long available) { }
    record Detail(Item product,List<Offer> offers,List<ProductDescription.Block> descriptionBlocks) { }
    Products.Page<Item> search(Query query);
    Detail detail(String slug);
    Detail forSku(UUID sku);
    record QuoteOffer(UUID skuId,UUID productId,UUID storeId,String storeSlug,String storeName,String title,List<Skus.Option> options,long price,long available,long productVersion,long skuVersion,long storeVersion,ReturnPolicy returnPolicy) { public QuoteOffer { options=List.copyOf(options); } }
    QuoteOffer quoteOffer(UUID sku);

    Detail forProduct(UUID product);
    UUID identify(String slug);
}
