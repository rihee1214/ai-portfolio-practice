package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

/** Caller transaction: lock all accounts, stores, categories, products and SKUs before any order FK. */
public interface PurchaseOffers {
    void lock(UUID buyer,List<UUID> skus);
}
