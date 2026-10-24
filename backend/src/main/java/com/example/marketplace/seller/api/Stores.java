package com.example.marketplace.seller.api;

import java.util.UUID;

public interface Stores {
    record Input(String slug, String name, String introduction, String address, String notice,
            String imageKey, String imageAlt, Boolean visible, Long version) { }
    record Store(UUID id, String slug, String name, String introduction, String address, String notice,
            String imageKey, String imageAlt, boolean visible, long version) { }
    record PublicStore(String slug, String name, String introduction, String address, String notice,
            String imageKey, String imageAlt) { }
    Store own(UUID owner);
    Store save(UUID owner, UUID id, Input input);
    PublicStore published(String slug);
}
