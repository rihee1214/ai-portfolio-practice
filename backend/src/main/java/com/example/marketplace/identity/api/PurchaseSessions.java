package com.example.marketplace.identity.api;

import java.util.UUID;

/** Current HTTP purchase authority, checked after the caller's canonical account locks. */
public interface PurchaseSessions {
    String AUTH_VERSION_ATTRIBUTE = PurchaseSessions.class.getName() + ".authVersion";
    boolean lockCurrent(String sessionId, UUID memberId, Long authVersion);
}
