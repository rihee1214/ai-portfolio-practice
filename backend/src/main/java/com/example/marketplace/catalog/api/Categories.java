package com.example.marketplace.catalog.api;

import java.util.List;
import java.util.UUID;

public interface Categories {
    record Input(String name, Boolean active, Long version) { }
    record Category(UUID id, String name, boolean active, long version) { }
    record Page(List<Category> items, int page, boolean hasMore) { }
    Page list(UUID operator, int page);
    Category save(UUID operator, UUID id, Input input);
}
