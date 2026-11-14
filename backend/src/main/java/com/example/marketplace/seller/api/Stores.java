package com.example.marketplace.seller.api;

import java.util.UUID;

public interface Stores {
    record Input(String slug, String name, String introduction, String address, String notice,
            String imageKey, String imageAlt, Boolean visible, Long version) { }
    record Store(UUID id, String slug, String name, String introduction, String address, String notice,
            String imageKey, String imageAlt, boolean visible, long version, String state) { }
    record Change(Long version, String reason) { }
    record Event(UUID actorId, String action, String beforeState, String afterState, String reason, java.time.Instant occurredAt) { }
    record Page(java.util.List<Store> items, int page, boolean hasMore) { }
    record Capabilities(boolean manageCatalog, boolean newSales, boolean existingTrades) { }
    enum Capability { MANAGE_CATALOG, NEW_SALES, EXISTING_TRADES }
    Page queue(UUID operator, int page);
    Store transition(UUID actor, UUID store, String action, Change change);
    java.util.List<Event> history(UUID actor, UUID store, boolean administrator);
    Capabilities capabilities(UUID owner);
    /** For future writes: caller transaction must contain this check and its business mutation. */
    void requireCapability(UUID owner, Capability capability);
    /** Trusted same-transaction store fact; locks its actual owner/membership before the store. */
    Store lockExistingTrade(UUID storeId);
    /** Trusted existing-order read; never grants new-sale eligibility and does not acquire later business locks. */
    Store existingTrade(UUID storeId);
    /** Stored recipient of historical trade notices; this supplies no read or sale capability. */
    UUID tradeOwner(UUID storeId);
    record PublicStore(String slug, String name, String introduction, String address, String notice,
            String imageKey, String imageAlt) { }
    Store own(UUID owner);
    Store save(UUID owner, UUID id, Input input);
    PublicStore published(String slug);
}
