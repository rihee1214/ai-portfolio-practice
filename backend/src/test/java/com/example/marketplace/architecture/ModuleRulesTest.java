package com.example.marketplace.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRulesTest {
    @ParameterizedTest
    @ValueSource(strings = {"fulfillment.api", "claim.api", "payment.infrastructure"})
    void confirmationBoundaryFixturesRejectReverseDependencies(String target) throws Exception {
        assertTrue(violates(compile(Map.of("order.infrastructure.Confirmation", "e6fixture." + target + ".Facts value;",
                target + ".Facts", ""))));
    }
    @ParameterizedTest
    @ValueSource(strings = {"fulfillment", "claim"})
    void initialDeliveryDoesNotPermitReverseDependencies(String module) throws Exception {
        assertTrue(violates(compile(Map.of("order.infrastructure.Consumer", "e6fixture." + module + ".api.Facts value;",
                module + ".api.Facts", ""))));
    }
    private static final String ROOT = "e6fixture";
    @TempDir
    Path directory;

    private JavaClasses compile(Map<String, String> sources) throws Exception {
        Path output = Files.createDirectory(directory.resolve("classes"));
        var arguments = new ArrayList<String>();
        arguments.add("-d");
        arguments.add(output.toString());
        for (var entry : sources.entrySet()) {
            String name = entry.getKey();
            int separator = name.lastIndexOf('.');
            Path source = directory.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + ROOT + "." + name.substring(0, separator)
                    + "; public class " + name.substring(separator + 1) + " { " + entry.getValue() + " }");
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return new ClassFileImporter().importPath(output);
    }

    private boolean violates(JavaClasses classes) {
        return ModuleRules.rules(ROOT).stream().anyMatch(rule -> rule.evaluate(classes).hasViolation());
    }

    @Test
    void platformApiCanUseOwnContracts() throws Exception {
        assertFalse(violates(compile(Map.of("platform.api.Jobs", "e6fixture.platform.api.Command value;", "platform.api.Command", "String id;"))));
    }

    @Test
    void businessImplementationCanUsePlatformContracts() throws Exception {
        assertFalse(violates(compile(Map.of("order.infrastructure.Jobs", "e6fixture.platform.api.Command value;", "platform.api.Command", "String id;"))));
    }

    @Test
    void platformApiCannotExposePlatformInternals() throws Exception {
        assertTrue(violates(compile(Map.of("platform.api.Jobs", "e6fixture.platform.infrastructure.Internal value;", "platform.infrastructure.Internal", ""))));
    }

    @Test
    void ownInternalsAndOtherModuleApiAreAllowed() throws Exception {
        var classes = compile(Map.of("order.application.Service", "e6fixture.catalog.api.Product product; e6fixture.order.domain.Order order;",
                "catalog.api.Product", "String name;", "order.domain.Order", "String id;"));
        assertFalse(violates(classes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"web", "application", "domain", "infrastructure", "internal"})
    void otherModuleInternalsAreRejected(String layer) throws Exception {
        assertTrue(violates(compile(Map.of("order.application.Service", "e6fixture.catalog." + layer + ".Internal value;",
                "catalog." + layer + ".Internal", ""))));
    }

    @Test
    void platformCannotDependOnBusinessEvenThroughApi() throws Exception {
        assertTrue(violates(compile(Map.of("platform.web.Filter", "e6fixture.catalog.api.Product value;",
                "catalog.api.Product", ""))));
    }

    @Test
    void publicApiCannotExposeOwnInternalType() throws Exception {
        assertTrue(violates(compile(Map.of("catalog.api.Product", "e6fixture.catalog.domain.Internal value;",
                "catalog.domain.Internal", ""))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "domain"})
    void jdbcTypesAreRejectedInContractsAndDomain(String layer) throws Exception {
        assertTrue(violates(compile(Map.of("catalog." + layer + ".Product", "java.sql.Connection connection;"))));
    }

    @Test
    void cyclesThroughPublicApisAreRejected() throws Exception {
        assertTrue(violates(compile(Map.of("order.api.Order", "e6fixture.catalog.api.Product value;",
                "catalog.api.Product", "e6fixture.order.api.Order value;"))));
    }

    @Test
    void domainCannotDependOnItsWebLayer() throws Exception {
        assertTrue(violates(compile(Map.of("catalog.domain.Product", "e6fixture.catalog.web.Request value;",
                "catalog.web.Request", ""))));
    }

    @Test
    void genericJdbcTypeIsNotHiddenByJavaList() throws Exception {
        assertTrue(violates(compile(Map.of("catalog.api.Product", "java.util.List<java.sql.Connection> values;"))));
    }

    @Test
    void securityPrincipalCannotLeakIntoApi() throws Exception {
        assertTrue(violates(compile(Map.of("catalog.api.Product", "java.security.Principal value;"))));
    }

    @Test
    void publicApiCannotDependOnPlatformEvenIfItsPackageIsNamedApi() throws Exception {
        assertTrue(violates(compile(Map.of("catalog.api.Product", "e6fixture.platform.api.Technical value;",
                "platform.api.Technical", ""))));
    }

    @Test
    void publicApiCannotDependOnPlatformWeb() throws Exception {
        assertTrue(violates(compile(Map.of("catalog.api.Product", "e6fixture.platform.web.Technical value;",
                "platform.web.Technical", ""))));
    }

    @Test
    void applicationCanStillUsePlatform() throws Exception {
        assertFalse(violates(compile(Map.of("catalog.application.Service", "e6fixture.platform.web.Technical value;",
                "platform.web.Technical", ""))));
    }
}
