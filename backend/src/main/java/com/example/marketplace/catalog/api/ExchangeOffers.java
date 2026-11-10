package com.example.marketplace.catalog.api;
import java.util.List;
import java.util.UUID;
/** Current exchange targets belong to the original store; approval caller retains the transaction. */
public interface ExchangeOffers {
 record Offer(UUID skuId,UUID productId,UUID storeId,String title,List<Skus.Option> options,String price,long productVersion,long skuVersion) { public Offer { options=List.copyOf(options); } }
 record TradeTargets(UUID storeId,String state,boolean visible,List<Offer> offers) { public TradeTargets { offers=List.copyOf(offers); } }
 /** Existing-order route only. Suspended/closed store status is returned as fact; claim owns inspected SELLER proof. */
 Offer existingPublished(UUID storeId,UUID skuId);
 List<Offer> existingCandidates(UUID storeId,UUID after,int size);
 TradeTargets lockExistingForSeller(UUID seller,UUID storeId,List<UUID> skuIds);
 TradeTargets lockExistingTrade(UUID storeId,List<UUID> skuIds);
 Offer published(UUID storeId,UUID skuId);
 List<Offer> candidates(UUID storeId,UUID after,int size);
 List<Offer> lockOpen(UUID seller,UUID storeId,List<UUID> skuIds);
 /** Trusted worker read; current public target and catalog locks, no caller authority. */
 List<Offer> lockPublished(UUID storeId,List<UUID> skuIds);
}
