package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.infrastructure.InitialAdmin;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production JARs and real TLS/static proxy; observation lives only in this test JVM. */
@ExtendWith(ContractTiming.class)
class PackageRuntimeIT {
    private final Path root = Path.of("..").toAbsolutePath().normalize();
    private final Path folder = Path.of(System.getenv("A3_EVIDENCE"));
    private final tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final List<Process> children = new ArrayList<>();
    private final List<String> containers = new ArrayList<>();
    private final Map<String,String> passwords = new LinkedHashMap<>();
    private final String gatePassword = UUID.randomUUID().toString();
    private final String observerKey = UUID.randomUUID().toString();
    private final Map<String,Object> accounts = new LinkedHashMap<>();

    @Test
    void productionArtifactsServeTlsRolesAndActualBrowserBusiness() throws Exception {
        HttpServer observer = null;
        try {
            var classes = Path.of(MarketplaceApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var jar = classes.getParent().resolve("marketplace-api-0.0.1-SNAPSHOT.jar");
            String jarHash = sha(jar);
            negativeStartup(jar);
            prepareAccounts();
            var data = new NamedParameterJdbcTemplate(new DriverManagerDataSource(System.getenv("D2_DB_URL"),
                    System.getenv("D2_DB_USERNAME"), System.getenv("D2_DB_PASSWORD")));
            int apiPort = freePort();
            int httpsPort = freePort();
            String image = buildImage(jar);
            var api = startJar(jar, "api", "db", apiPort);
            awaitLog(api, folder.resolve("api.log"), "Tomcat started on port", 60);
            ContractTiming.identity(apiPort, System.getenv("D2_DB_URL"));
            assertFalse(Files.readString(folder.resolve("api.log")).contains("local-mailbox"));
            assertEquals(0, data.queryForObject("SELECT count(*) FROM marketplace.job WHERE status='RUNNING'", Map.of(), Integer.class));
            var worker = startJar(jar, "worker", "db,worker", freePort());
            awaitLog(worker, folder.resolve("worker.log"), "Started MarketplaceApplication", 60);
            assertFalse(Files.readString(folder.resolve("worker.log")).contains("Tomcat"));
            assertEquals(jarHash, sha(jar));
            buildDist();
            startProxy(folder, apiPort, httpsPort, folder.resolve("browser-app"));
            Path legacy = copyLegacyDist();
            int legacyPort = freePort();
            startProxy(folder.resolve("legacy-proxy"), apiPort, legacyPort, legacy);
            var tls = tlsClient();
            String base = "https://localhost:" + httpsPort;
            awaitHttp(tls, base);
            assertEquals(401, send(tls, base, "/", false).statusCode());
            assertEquals(200, send(tls, base, "/products/direct-refresh", true).statusCode());
            assertEquals(404, send(tls, base, "/assets/missing-package.js", true).statusCode());
            var health = send(tls, base, "/actuator/health", true);
            assertEquals(200, health.statusCode());
            assertEquals("{\"status\":\"UP\"}", health.body());
            assertTrue(health.headers().firstValue("cache-control").orElseThrow().contains("no-store"));
            var spoofedForwarding = Map.of("X-Forwarded-Prefix", "/untrusted", "X-Forwarded-Ssl", "off",
                    "X-Forwarded-Host", "evil.invalid", "X-Forwarded-Port", "80");
            var spoofedHealth = send(tls, base, "/actuator/health", true, spoofedForwarding);
            assertEquals(200, spoofedHealth.statusCode()); assertEquals(health.body(), spoofedHealth.body());
            var spoofedCsrf = send(tls, base, "/api/auth/csrf", true, spoofedForwarding);
            assertEquals(200, spoofedCsrf.statusCode());
            assertEquals("X-CSRF-TOKEN", mapper.readTree(spoofedCsrf.body()).path("headerName").asText());
            var spoofedSession = send(tls, base, "/api/auth/me", true, spoofedForwarding);
            assertEquals(401, spoofedSession.statusCode());
            assertTrue(spoofedSession.headers().firstValue("content-type").orElseThrow().contains("application/json"));
            assertTrue(spoofedSession.headers().firstValue("cache-control").orElseThrow().contains("no-store"));
            for (String path : List.of("/actuator", "/actuator/env", "/ACTUATOR/HEALTH", "/api/../actuator/env",
                    "/api//auth/me", "/api/%2fadmin", "/a%70i/auth/me", "/API/auth/me", "/actuator/health/db")) {
                assertTrue(send(tls, base, path, true).statusCode() >= 400, path);
            }
            var capability = send(tls, base, "/api/runtime-policy", true);
            assertEquals(200, capability.statusCode(), capability.body());
            assertEquals("public-demo", mapper.readTree(capability.body()).path("mode").asText());
            assertEquals(401, send(tls, base, "/api/auth/me", true).statusCode());
            assertEquals(401, send(tls, base, "/api/__test/package", true).statusCode());
            assertFalse(Files.exists(folder.resolve("browser-app/media")));
            observer = observer(data, apiPort, httpsPort);
            runBrowsers(base, "https://localhost:" + legacyPort, legacy, observer.getAddress().getPort());
            assertTrue(api.isAlive());
            assertTrue(worker.isAlive());
            assertEquals(jarHash, sha(jar));
            SharedProductionBundle.verifyIfDeclared(root, folder, folder.resolve("browser-app"));
            var proof = new LinkedHashMap<String,Object>();
            proof.put("jar", jar.toString()); proof.put("apiJarSha256", jarHash); proof.put("workerJarSha256", jarHash);
            proof.put("apiPid", api.pid()); proof.put("workerPid", worker.pid()); proof.put("apiProfiles", "db");
            proof.put("workerProfiles", "db,worker"); proof.put("workerWebServer", false);
            proof.put("database", System.getenv("D2_DB_NAME")); proof.put("media", folder.resolve("media").toString());
            proof.put("apiPort", apiPort); proof.put("httpsPort", httpsPort); proof.put("runtimeMode", "public-demo");
            proof.put("accountPreparation", "SignupOperations.register/verifyEmail, InitialAdmin.initialize, SellerMembership.grant");
            proof.put("javaVersion", System.getProperty("java.version")); proof.put("containers", containers);
            proof.put("applicationImageId", image);
            Files.writeString(folder.resolve("package-proof.json"), mapper.writeValueAsString(proof));
        } finally {
            if (observer != null) { observer.stop(0); }
            for (String id : containers) {
                command(folder.resolve("nginx-" + id.substring(0, 12) + ".log"), root, List.of("docker", "logs", id), 10, Map.of());
                command(folder.resolve("nginx-stop-" + id.substring(0, 12) + ".txt"), root,
                        List.of("docker", "stop", "--timeout", "5", id), 10, Map.of());
            }
            for (Process child : children) {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
            }
        }
    }

    private void prepareAccounts() throws Exception {
        var application = new SpringApplication(MarketplaceApplication.class);
        application.addInitializers(new DedicatedPostgresInitializer());
        try (var context = application.run("--spring.profiles.active=db,local-mailbox,local-http", "--server.address=127.0.0.1",
                "--server.port=0", "--marketplace.runtime.mode=local-full", "--marketplace.phone.mode=disabled")) {
            var signup = context.getBean(SignupOperations.class);
            var mailbox = context.getBean(LocalTestMailbox.class);
            var jdbc = context.getBean(NamedParameterJdbcTemplate.class);
            var sellers = new ArrayList<UUID>();
            UUID admin = null;
            for (String project : List.of("desktop", "mobile")) {
                var emails = new LinkedHashMap<String,String>();
                for (String role : List.of("buyer", "seller", "other", "admin")) {
                    String email = "p12c-" + role + "-" + project + "@example.com";
                    if (role.equals("admin") && admin != null) { emails.put(role, "p12c-admin-desktop@example.com"); continue; }
                    String password = " P12C-" + UUID.randomUUID() + "-individual-가나다! ";
                    UUID id = signup.register(email, password);
                    passwords.put(email, password);
                    signup.verifyEmail(mailbox.messages().stream().filter(message -> message.recipient().equals(email)).findFirst().orElseThrow().token());
                    if (role.equals("seller")) { sellers.add(id); }
                    if (role.equals("admin")) {
                        new InitialAdmin(jdbc, context.getBean(PlatformTransactionManager.class)).initialize(id, System.getenv("D2_DB_NAME"), "INITIAL_ADMIN");
                        admin = id;
                    }
                    emails.put(role, email);
                }
                accounts.put(project, emails);
            }
            UUID operator = admin;
            for (UUID seller : sellers) {
                new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                        .executeWithoutResult(status -> context.getBean(com.example.marketplace.identity.api.SellerMembership.class).grant(operator, seller));
            }
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit WHERE action='INITIAL_ADMIN_GRANTED'", Map.of(), Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order", Map.of(), Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token WHERE consumed_at IS NULL", Map.of(), Integer.class));
            assertEquals(7, passwords.size());
            assertEquals(passwords.size(), new java.util.HashSet<>(passwords.values()).size());
            jdbc.update("DELETE FROM marketplace.spring_session", Map.of());
            Files.writeString(folder.resolve("account-preparation.json"), mapper.writeValueAsString(Map.of("database", System.getenv("D2_DB_NAME"),
                    "accounts", accounts, "initialAdminCount", 1, "ordersBefore", 0, "sessionsAfter", 0, "distinctIndividualPasswords", passwords.size(),
                    "preparationClosedBeforePublicStart", true)));
        }
    }

    private Process startJar(Path jar, String role, String profiles, int port) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-jar", jar.toString(),
                "--spring.profiles.active=" + profiles, "--server.address=127.0.0.1", "--server.port=" + port,
                "--server.forward-headers-strategy=framework", "--marketplace.phone.mode=disabled");
        var env = builder.environment();
        var removed = isolatePublicChild(env);
        Files.writeString(folder.resolve(role + "-removed-environment-names.json"), mapper.writeValueAsString(removed));
        env.put("DB_URL", System.getenv("D2_DB_URL")); env.put("DB_USERNAME", System.getenv("D2_DB_USERNAME"));
        env.put("DB_PASSWORD", System.getenv("D2_DB_PASSWORD"));
        if (profiles.contains("worker")) { env.remove("MEDIA_ROOT"); }
        else { env.put("MEDIA_ROOT", folder.resolve("media").toString()); }
        env.put("MARKETPLACE_RUNTIME_MODE", "public-demo"); env.put("MARKETPLACE_RUNTIME_ACCESS", "invited-only");
        env.put("MARKETPLACE_RUNTIME_PUBLIC_ORIGIN", "https://localhost"); env.put("MARKETPLACE_RUNTIME_INTERNAL_API", "true");
        var process = builder.redirectErrorStream(true).redirectOutput(folder.resolve(role + ".log").toFile()).start();
        children.add(process);
        return process;
    }

    private void negativeStartup(Path jar) throws Exception {
        for (String role : List.of("api", "worker")) {
            for (String variant : List.of("production", "missing-access", "forbidden-mailbox")) {
                String profiles = role.equals("worker") ? "db,worker" : "db";
                if (variant.equals("forbidden-mailbox")) { profiles += ",local-mailbox"; }
                Path log = folder.resolve("negative-" + role + "-" + variant + ".log");
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-jar", jar.toString(),
                        "--spring.profiles.active=" + profiles, "--server.address=127.0.0.1", "--server.port=0",
                        "--marketplace.runtime.mode=" + (variant.equals("production") ? "production" : "public-demo"),
                        "--marketplace.runtime.access=" + (variant.equals("missing-access") ? "" : "invited-only"),
                        "--marketplace.runtime.public-origin=https://localhost", "--marketplace.runtime.internal-api=true",
                        "--marketplace.phone.mode=disabled", "--DB_URL=jdbc:postgresql://127.0.0.1:1/never_connect",
                        "--DB_USERNAME=synthetic", "--DB_PASSWORD=p12c-negative-secret-never-log");
                var removed = isolatePublicChild(builder.environment());
                Files.writeString(folder.resolve("negative-" + role + "-" + variant + "-removed-environment-names.json"), mapper.writeValueAsString(removed));
                var child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start(); children.add(child);
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Finite negative startup preparation");
                assertTrue(child.exitValue() != 0);
                String output = Files.readString(log);
                assertTrue(output.contains("RUNTIME_CONFIGURATION_REJECTED"));
                assertFalse(output.contains("HikariPool")); assertFalse(output.contains("Migrating schema"));
                assertFalse(output.contains("p12c-negative-secret-never-log"));
            }
        }
    }

    private List<String> isolatePublicChild(Map<String,String> environment) {
        var removed = new ArrayList<String>();
        for (String name : List.copyOf(environment.keySet())) {
            if (name.startsWith("TOSS_") || name.startsWith("FIREBASE_") || name.startsWith("FIRESTORE_")
                    || name.startsWith("GCLOUD_") || name.startsWith("SPRING_") || name.startsWith("MARKETPLACE_")
                    || name.equals("D2_ADMIN_PASSWORD") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS")) {
                environment.remove(name); removed.add(name);
            }
        }
        return removed;
    }

    private void buildDist() throws Exception {
        var build = folder.resolve("browser-app");
        if (!SharedProductionBundle.copyIfDeclared(root, folder, build)) {
            command(folder.resolve("package-build.txt"), root.resolve("frontend"),
                List.of(root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe").toString(), "node_modules/vite/bin/vite.js",
                        "build", "--manifest", "--outDir", build.toString()), 60, Map.of());
        }
        var files = new ArrayList<Map<String,Object>>();
        try (var paths = Files.walk(build)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.add(Map.of("file", build.relativize(path).toString().replace('\\','/'), "bytes", Files.size(path), "sha256", sha(path)));
            }
        }
        Files.writeString(folder.resolve("package-dist.json"), mapper.writeValueAsString(Map.of("mode", "production", "files", files)));
    }

    private String buildImage(Path jar) throws Exception {
        Path build = Files.createDirectory(folder.resolve("image-context"));
        Files.copy(jar, build.resolve("marketplace.jar"));
        Files.copy(root.resolve("infra/p12-c/Dockerfile"), build.resolve("Dockerfile"));
        String tag = "p12c-local:" + UUID.randomUUID().toString().replace("-", "");
        command(folder.resolve("image-build.txt"), root, List.of("docker", "build", "--pull=false", "--network=none", "--tag", tag, build.toString()), 60, Map.of());
        command(folder.resolve("application-image.json"), root, List.of("docker", "image", "inspect", tag), 10, Map.of());
        var image = mapper.readTree(Files.readString(folder.resolve("application-image.json"))).get(0);
        assertEquals("10001:10001", image.path("Config").path("User").asText());
        String name = "p12c-artifact-" + UUID.randomUUID().toString().replace("-", "");
        command(folder.resolve("image-jar-sha.txt"), root, List.of("docker", "run", "--pull=never", "--name", name, "--network=none", "--read-only",
                "--entrypoint", "sha256sum", image.path("Id").asText(), "/app/marketplace.jar"), 10, Map.of());
        assertTrue(Files.readString(folder.resolve("image-jar-sha.txt")).startsWith(sha(jar)));
        command(folder.resolve("image-media-uid.txt"), root, List.of("docker", "run", "--pull=never", "--name", "p12c-media-" + UUID.randomUUID(),
                "--network=none", "--read-only", "--mount", "type=bind,source=" + folder.resolve("media") + ",target=/media",
                "--entrypoint", "sh", image.path("Id").asText(), "-c", "id -u; printf package-uid > /media/p12c-uid-proof"), 10, Map.of());
        assertTrue(Files.readString(folder.resolve("image-media-uid.txt")).startsWith("10001"));
        assertEquals("package-uid", Files.readString(folder.resolve("media/p12c-uid-proof")));
        return image.path("Id").asText();
    }

    private Path copyLegacyDist() throws Exception {
        Path originalRun = root.resolve(".local-tools/p12-b-full1-640368218b3a498aa1e9d9b1351c47ee");
        Path input = originalRun.resolve("inputs.json");
        assertEquals("93d5f36020475dc792e3fa2276f14a510d2ed51d12e59cd890e3cf8af32142a0", sha(input), "Retained P12-B source provenance");
        Path original = originalRun.resolve("db/groups/browser-api/browser-app");
        Path copy = Files.createDirectory(folder.resolve("browser-legacy-app"));
        var inventory = new ArrayList<Map<String,Object>>();
        try (var files = Files.walk(original)) {
            for (Path file : files.toList()) {
                Path relative = original.relativize(file);
                if (Files.isDirectory(file)) { Files.createDirectories(copy.resolve(relative)); }
                else {
                    String before = sha(file);
                    Files.copy(file, copy.resolve(relative));
                    assertEquals(before, sha(file)); assertEquals(before, sha(copy.resolve(relative)));
                    inventory.add(Map.of("file", relative.toString().replace('\\','/'), "bytes", Files.size(file), "sha256", before));
                }
            }
        }
        Files.writeString(folder.resolve("package-legacy-dist.json"), mapper.writeValueAsString(Map.of("source", original.toString(),
                "sourceInputs", input.toString(), "sourceInputsSha256", sha(input), "retainedBuildMode", "development",
                "sourceBuildRecordSha256", sha(original.getParent().resolve("browser-app-build.json")), "files", inventory)));
        return copy;
    }

    private void startProxy(Path scope, int apiPort, int httpsPort, Path dist) throws Exception {
        var output = folder.resolve(scope.equals(folder) ? "proxy-start.txt" : "legacy-proxy-start.txt");
        command(output, root, List.of("C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe",
                "-NoProfile", "-File", root.resolve("infra/p12-c/Start-LocalProxy.ps1").toString(), "-Evidence", scope.toString(),
                "-ApiPort", Integer.toString(apiPort), "-HttpsPort", Integer.toString(httpsPort), "-GatePassword", gatePassword,
                "-Dist", dist.toString()), 60, Map.of());
        var owner = mapper.readTree(Files.readString(scope.resolve("proxy-owner.json")));
        containers.add(owner.path("container").asText());
    }

    private HttpServer observer(NamedParameterJdbcTemplate jdbc, int apiPort, int httpsPort) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = 200;
            Object value;
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || !observerKey.equals(exchange.getRequestHeaders().getFirst("X-Observer-Key"))) {
                    status = 403; value = Map.of("error", "READ_ONLY_OBSERVER");
                } else if (exchange.getRequestURI().getPath().equals("/identity")) {
                    value = Map.of("database", System.getenv("D2_DB_NAME"), "apiPort", apiPort, "httpsPort", httpsPort, "media", folder.resolve("media").toString());
                } else if (exchange.getRequestURI().getPath().equals("/legacy-issuance")) {
                    String query = exchange.getRequestURI().getRawQuery();
                    String email = query != null && query.startsWith("email=") ? java.net.URLDecoder.decode(query.substring(6), java.nio.charset.StandardCharsets.UTF_8) : "";
                    if (!email.matches("p12c-legacy-[a-z0-9-]+@example\\.com")) { status = 404; value = Map.of("error", "OWNED_SYNTHETIC_EMAIL_REQUIRED"); }
                    else {
                        var parameters = Map.of("email", email);
                        value = Map.of("users", jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email", parameters, Integer.class),
                                "signupTokens", jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token t JOIN marketplace.identity_user u ON u.id=t.user_id WHERE u.email=:email", parameters, Integer.class),
                                "resetTokens", jdbc.queryForObject("SELECT count(*) FROM marketplace.password_reset_token t JOIN marketplace.identity_user u ON u.id=t.user_id WHERE u.email=:email", parameters, Integer.class));
                    }
                } else {
                    String query = exchange.getRequestURI().getRawQuery();
                    String number = query != null && query.startsWith("orderNumber=") ? query.substring(12) : "";
                    if (!number.matches("O[0-9a-fA-F]{32}")) { status = 404; value = Map.of("error", "OWNED_ORDER_REQUIRED"); }
                    else { value = purchaseObservation(jdbc, number); }
                }
            } catch (RuntimeException failure) { status = 500; value = Map.of("error", "OBSERVER_FAILURE"); }
            byte[] bytes = mapper.writeValueAsBytes(value);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        return server;
    }

    private Object purchaseObservation(NamedParameterJdbcTemplate jdbc, String number) {
        var parameters = Map.of("number", number);
        var order = jdbc.queryForList("SELECT o.id::text,o.state,o.amount,o.mode FROM marketplace.purchase_order o JOIN marketplace.identity_user u ON u.id=o.member_id WHERE o.order_number=:number AND u.email LIKE 'p12c-buyer-%@example.com'", parameters);
        if (order.size() != 1) { throw new IllegalArgumentException("Owned order missing"); }
        var result = new LinkedHashMap<String,Object>();
        result.put("order", order.getFirst());
        result.put("inventory", jdbc.queryForList("SELECT r.status FROM marketplace.inventory_reservation r JOIN marketplace.order_reservation b ON b.reservation_id=r.id JOIN marketplace.purchase_order o ON o.id=b.order_id WHERE o.order_number=:number", parameters));
        result.put("attempts", jdbc.queryForList("SELECT a.state,a.id::text,m.approval_effects,(SELECT count(*) FROM marketplace.payment_event e WHERE e.attempt_id=a.id AND e.action='SUCCEEDED') AS success_events FROM marketplace.payment_attempt a LEFT JOIN marketplace.payment_mock m ON m.attempt_id=a.id JOIN marketplace.purchase_order o ON o.id=a.order_id WHERE o.order_number=:number", parameters));
        result.put("shipments", jdbc.queryForList("SELECT s.id::text,s.state FROM marketplace.shipment s JOIN marketplace.purchase_order o ON o.id=s.order_id WHERE o.order_number=:number", parameters));
        result.put("notifications", jdbc.queryForList("SELECT kind AS event_type,recipient_id::text FROM marketplace.notification_event WHERE target_id=(SELECT id FROM marketplace.purchase_order WHERE order_number=:number)", parameters));
        return result;
    }

    private void runBrowsers(String base, String legacyBase, Path legacyDist, int observerPort) throws Exception {
        var env = Map.ofEntries(Map.entry("P12_C_BASE_URL", base), Map.entry("P12_C_GATE_USER", "reviewer"), Map.entry("P12_C_GATE_PASSWORD", gatePassword),
                Map.entry("P12_C_PASSWORDS", mapper.writeValueAsString(passwords)), Map.entry("P12_C_ACCOUNTS", mapper.writeValueAsString(accounts)),
                Map.entry("P12_C_OBSERVER_URL", "http://127.0.0.1:" + observerPort), Map.entry("P12_C_OBSERVER_KEY", observerKey),
                Map.entry("P12_C_DIST", folder.resolve("browser-app").toString()), Map.entry("P12_C_LEGACY_BASE_URL", legacyBase),
                Map.entry("P12_C_LEGACY_DIST", legacyDist.toString()));
        var builder = new ProcessBuilder(root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe").toString(),
                "node_modules/@playwright/test/cli.js", "test", "--config=auth-e2e/package-runtime.config.ts").directory(root.resolve("frontend").toFile());
        builder.environment().putAll(env);
        builder.redirectErrorStream(true).redirectOutput(folder.resolve("browser.txt").toFile());
        var browser = builder.start(); children.add(browser);
        com.example.marketplace.jdbcfixture.BrowserChildWatchdog.await(browser, folder.resolve("browser-progress.ndjson"));
        assertEquals(0, browser.exitValue(), "Inspect preserved browser.txt");
    }

    private void command(Path log, Path directory, List<String> command, int seconds, Map<String,String> environment) throws Exception {
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        var child = builder.start(); children.add(child);
        if (!child.waitFor(seconds, TimeUnit.SECONDS)) {
            com.example.marketplace.jdbcfixture.BrowserChildWatchdog.terminate(child);
            throw new AssertionError("Finite command exceeded preparation/cleanup boundary: " + log.getFileName());
        }
        assertEquals(0, child.exitValue(), "Inspect " + log);
    }

    private void awaitLog(Process process, Path log, String text, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(log) && Files.readString(log).contains(text)) { return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Role did not become ready; inspect " + log.getFileName());
    }

    private HttpClient tlsClient() throws Exception {
        var trust = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); trust.load(null);
        try (var input = Files.newInputStream(folder.resolve("proxy-secrets/tls.crt"))) {
            trust.setCertificateEntry("localhost", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        var ssl = javax.net.ssl.SSLContext.getInstance("TLS"); ssl.init(null, factory.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3)).build();
    }
    private void awaitHttp(HttpClient client, String base) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try { if (send(client, base, "/", true).statusCode() == 200) { return; } }
            catch (java.io.IOException failure) { /* Retry only the readiness GET within one shared budget. */ }
            Thread.sleep(100);
        }
        throw new AssertionError("NGINX readiness failed");
    }
    private HttpResponse<String> send(HttpClient client, String base, String path, boolean gate) throws Exception {
        return send(client, base, path, gate, Map.of());
    }
    private HttpResponse<String> send(HttpClient client, String base, String path, boolean gate, Map<String,String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (gate) { request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("reviewer:" + gatePassword).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        headers.forEach(request::header);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static String sha(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
}
