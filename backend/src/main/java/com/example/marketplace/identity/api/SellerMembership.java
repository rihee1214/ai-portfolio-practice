package com.example.marketplace.identity.api;

import java.util.UUID;

/** Must participate in the caller's transaction; identity owns its member/role writes. */
public interface SellerMembership {
    boolean lockActiveBuyer(UUID member);
    /** Retains current account and SELLER membership locks until the caller transaction commits. */
    void lockSeller(UUID member);
    void grant(UUID operator, UUID member);
}
