package com.example.marketplace.dbtest;

import org.junit.jupiter.api.Test;

class SiteMaintenanceDesktopIT extends SiteMaintenanceBrowserSupport {
    @Test void actualMaintenanceUiUsesOwnedDatabaseAndPreservesSnapshots() throws Exception {
        runMaintenanceBrowser("desktop");
    }
}
