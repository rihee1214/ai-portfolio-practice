package com.example.marketplace.identity.api;

public enum AccessArea {
    SELLER, ADMIN, ACCOUNTS, CONTENT, SUPPORT, SETTLEMENTS, SYSTEM;

    public String role() { return this == SELLER ? "SELLER" : "ADMIN"; }
    public boolean permissionRequired() { return this != SELLER && this != ADMIN; }
    public static AccessArea forAdminPath(String path) {
        if ("/api/admin/access".equals(path)) { return ADMIN; }
        for (AccessArea area : values()) {
            if (area.permissionRequired() && path.startsWith("/api/admin/" + area.name().toLowerCase(java.util.Locale.ROOT) + "/")) {
                return area;
            }
        }
        return null;
    }
}
