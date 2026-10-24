package com.example.marketplace.identity.api;

import java.util.UUID;

/** Must participate in the caller's transaction; identity owns its member/role writes. */
public interface SellerMembership {
    boolean lockActiveBuyer(UUID member);
    void grant(UUID operator, UUID member);
}
