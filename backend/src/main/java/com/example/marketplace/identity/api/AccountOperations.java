package com.example.marketplace.identity.api;

import java.util.List;
import java.util.UUID;

public interface AccountOperations {
    record Profile(String email, String displayName, String phone) { }
    record Address(UUID id, String label, String recipient, String phone, String postalCode,
            String addressLine1, String addressLine2, boolean isDefault) { }
    record AddressInput(String label, String recipient, String phone, String postalCode,
            String addressLine1, String addressLine2, boolean isDefault) { }
    Profile profile(UUID member);
    Profile updateProfile(UUID member, String displayName, String phone);
    List<Address> addresses(UUID member);
    Address saveAddress(UUID member, UUID id, AddressInput input);
    void deleteAddress(UUID member, UUID id);
}
