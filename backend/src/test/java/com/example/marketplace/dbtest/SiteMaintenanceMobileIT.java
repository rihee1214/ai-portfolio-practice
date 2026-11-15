package com.example.marketplace.dbtest;

import org.junit.jupiter.api.Test;

class SiteMaintenanceMobileIT extends SiteMaintenanceBrowserSupport {
    @Test void actualMaintenanceUiUsesOwnedDatabaseAndPreservesSnapshots() throws Exception {
        runMaintenanceBrowser("mobile");
    }
}
