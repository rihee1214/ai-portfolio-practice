package com.example.marketplace.promotion.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface Promotions {
    record Input(String title,String scope,List<UUID> productIds,Instant startsAt,Instant endsAt,String kind,Long value,Long cap,String state,Long version) { }
    record Target(UUID id,String slug,String title) { }
    record Promotion(UUID id,UUID storeId,String title,String scope,List<UUID> productIds,Instant startsAt,Instant endsAt,String kind,long value,Long cap,String state,long version,String storeSlug,String storeName,List<Target> targets) {
        public Promotion(UUID id,UUID storeId,String title,String scope,List<UUID> productIds,Instant startsAt,Instant endsAt,String kind,long value,Long cap,String state,long version) {
            this(id,storeId,title,scope,productIds,startsAt,endsAt,kind,value,cap,state,version,"","",List.of());
        }
    }
    record Page(List<Promotion> items,int page,boolean hasMore) { }
    Promotion save(UUID actor,UUID id,Input input);
    Promotion own(UUID actor,UUID id);
    Page list(UUID actor,int page);
    Promotion published(UUID id);
    Page published(int page);
    List<Promotion> candidates(UUID storeId,List<UUID> productIds,Instant at);
}
