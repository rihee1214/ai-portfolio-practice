package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface Products {
    record Input(UUID categoryId,String slug,String title,String description,String state,Long version,String returnFee,
            List<ProductDescription.Block> descriptionBlocks,Map<Integer, ProductDescriptionMedia.Upload> descriptionUploads) {
        public Input(UUID categoryId,String slug,String title,String description,String state,Long version,String returnFee) {
            this(categoryId,slug,title,description,state,version,returnFee,null,Map.of());
        }
        public Input(UUID categoryId,String slug,String title,String description,String state,Long version) {
            this(categoryId,slug,title,description,state,version,null);
        }
    }
    record Product(UUID id,UUID categoryId,String slug,String title,String description,String state,long version,boolean hiddenByAdmin,
            ReturnPolicy returnPolicy,List<ProductDescription.Block> descriptionBlocks) { }
    record PublicProduct(String slug,String title,String description,UUID categoryId,String storeSlug,ReturnPolicy returnPolicy) { }
    record Page<T>(List<T> items,int page,boolean hasMore) { }
    Product save(UUID owner,UUID id,Input input);
    Product saveForSession(UUID owner,String browser,Long authVersion,UUID id,Input input);
    Product own(UUID owner,UUID id);
    /** Caller must hold an active transaction through the media mutation. */
    void lockForMedia(UUID owner,UUID id);
    Page<Product> list(UUID owner,int page);
    PublicProduct published(String slug);
    Page<PublicProduct> published(int page);
}
