package com.example.marketplace.phone.infrastructure;

import java.util.Set;

public final class LocalPhonePolicy {
    private LocalPhonePolicy() { }
    public static void check(String mode, String address, Set<String> profiles) {
        if ("disabled".equals(mode)) { return; }
        if (!"local-test".equals(mode) || !"127.0.0.1".equals(address) || profiles.contains("prod") || profiles.contains("production")) {
            throw new IllegalArgumentException("Phone verification is local-test only");
        }
    }
}
