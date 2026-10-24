package com.example.marketplace.identity.api;

import java.util.UUID;

public interface AccessPolicy {
    boolean allowed(UUID member, AccessArea area);
    default void require(UUID member, AccessArea area) {
        if (!allowed(member, area)) { throw new AccessRejected(); }
    }
}
