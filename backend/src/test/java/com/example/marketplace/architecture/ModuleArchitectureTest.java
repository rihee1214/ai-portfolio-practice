package com.example.marketplace.architecture;

import com.example.marketplace.MarketplaceApplication;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleArchitectureTest {
    @Test
    void productionClassesRespectModuleBoundaries() throws Exception {
        // Import the actual main output directory, including custom Maven build paths, not test fixtures.
        Path mainOutput = Path.of(MarketplaceApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var classes = new ClassFileImporter().importPath(mainOutput);
        assertFalse(classes.isEmpty(), "An empty import must not pass architecture verification");
        assertTrue(classes.stream().anyMatch(type -> type.getName().equals(MarketplaceApplication.class.getName())));
        assertTrue(classes.stream().noneMatch(type -> type.getName().equals(getClass().getName())));
        ModuleRules.rules("com.example.marketplace").forEach(rule -> rule.check(classes));
    }
}
