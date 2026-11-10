package com.example.marketplace.catalog.api;

import java.util.UUID;

public interface ProductVisibility {
    record State(long version,boolean hidden) {}
    State lock(UUID id);
    void change(UUID id,boolean hidden,long version);
}
