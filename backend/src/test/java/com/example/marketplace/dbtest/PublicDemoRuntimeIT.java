package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.example.marketplace.payment.api.PaymentProvider;
import com.example.marketplace.payment.api.RefundProvider;
import com.example.marketplace.payment.infrastructure.MockPaymentProvider;
import com.example.marketplace.payment.infrastructure.MockRefundProvider;
import com.example.marketplace.platform.api.DemoFeatureUnavailable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** Public policy uses a fresh DB without test mail/phone/Toss configuration in its application. */
@ActiveProfiles("db")
@ExtendWith(ContractTiming.class)
@ContextConfiguration(initializers = DedicatedPostgresInitializer.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/", "server.address=127.0.0.1", "marketplace.runtime.mode=public-demo",
                "marketplace.runtime.access=invited-only", "marketplace.runtime.public-origin=https://demo.example.test",
                "marketplace.runtime.internal-api=true", "marketplace.session.secure=true", "marketplace.phone.mode=disabled"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicDemoRuntimeIT {
    private static final String PASSWORD = "P12-C synthetic member password 473!";
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @MockitoSpyBean MockPaymentProvider demoPayments;
    @MockitoSpyBean MockRefundProvider demoRefunds;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    private String cookie;

    @BeforeEach void identity() {
        ContractTiming.identity(port, context.getEnvironment().getRequiredProperty("spring.datasource.url"));
        for (String key : Set.of("FIREBASE_AUTH_EMULATOR_HOST", "FIRESTORE_EMULATOR_HOST", "FIREBASE_DATABASE_EMULATOR_HOST",
                "GCLOUD_PROJECT", "TOSS_CLIENT_KEY", "TOSS_SECRET_KEY", "TOSS_MID", "TOSS_API_VERSION", "TOSS_WEBHOOK_SECURITY_KEY")) {
            String value = System.getenv(key);
            assertTrue(value == null || value.isBlank(), "Public group must remove provider configuration from its process: " + key);
        }
    }

    private HttpResponse<String> request(int target, String method, String path, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (cookie != null) { builder.header("Cookie", cookie); }
        if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
        if (body != null) { builder.header("Content-Type", path.equals("/api/auth/login") ? "application/x-www-form-urlencoded" : "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        for (String set : response.headers().allValues("Set-Cookie")) {
            if (set.startsWith("SESSION=")) { cookie = set.substring(0, set.indexOf(';')); }
        }
        return response;
    }
    private String csrf(int target) throws Exception {
        var response = request(target, "GET", "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).path("token").asText();
    }
    private String prepareMember() throws Exception {
        String email = "p12-c-" + UUID.randomUUID() + "@example.test";
        // Reuse the existing local HTTP signup/verify flow before testing public access; no SQL account bootstrap.
        try (var local = new SpringApplicationBuilder(MarketplaceApplication.class).profiles("db", "local-mailbox", "local-http")
                .run("--spring.config.location=classpath:/", "--marketplace.runtime.mode=local-full", "--server.address=127.0.0.1",
                        "--server.port=0", "--marketplace.worker.enabled=false", "--marketplace.session.secure=false",
                        "--spring.datasource.url=" + context.getEnvironment().getRequiredProperty("spring.datasource.url"),
                        "--spring.datasource.username=" + context.getEnvironment().getRequiredProperty("spring.datasource.username"),
                        "--spring.datasource.password=" + context.getEnvironment().getRequiredProperty("spring.datasource.password"),
                        "--marketplace.media.root=" + java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("preparation-media"))) {
            int localPort = ((WebServerApplicationContext) local).getWebServer().getPort();
            cookie = null;
            assertEquals(201, request(localPort, "POST", "/api/auth/signup", json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), csrf(localPort)).statusCode());
            String token = local.getBean(LocalTestMailbox.class).messages().getFirst().token();
            assertEquals(200, request(localPort, "POST", "/api/auth/verify-email", json.writeValueAsString(Map.of("token", token)), csrf(localPort)).statusCode());
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE email=:email", Map.of("email", email), String.class));
            // Revoke preparation sessions before closing the local issuer, as required by the handoff contract.
            jdbc.update("DELETE FROM marketplace.spring_session", Map.of());
        }
        cookie = null;
        return email;
    }
    private Map<String, String> businessSnapshot() {
        var result = new java.util.TreeMap<String, String>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='marketplace' AND tablename NOT LIKE 'spring_session%' ORDER BY tablename", Map.of(), String.class)) {
            assertTrue(table.matches("[a-z_][a-z0-9_]*"));
            result.put(table, jdbc.queryForObject("SELECT md5(coalesce(string_agg(to_jsonb(t)::text, '' ORDER BY to_jsonb(t)::text), '')) FROM marketplace.\"" + table + "\" t", Map.of(), String.class));
        }
        return result;
    }

    @Test void publicDemoKeepsExistingLoginAndRejectsEveryIssuanceAndSandboxWithoutMutation() throws Exception {
        String email = prepareMember();
        assertFalse(context.containsBean("localTestMailbox"));
        assertFalse(context.containsBean("localPasswordResetMailbox"));
        assertTrue(context.getBeansOfType(com.example.marketplace.phone.api.PhoneVerifications.class).isEmpty());
        var capability = request(port, "GET", "/api/runtime-policy", null, null);
        assertEquals(200, capability.statusCode());
        assertEquals("no-store", capability.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("public-demo", json.readTree(capability.body()).path("mode").asText());
        assertTrue(json.readTree(capability.body()).path("restricted").asBoolean());
        assertEquals(401, request(port, "GET", "/api/auth/me", null, null).statusCode());
        assertEquals(403, request(port, "POST", "/api/auth/signup", "{}", null).statusCode());
        cookie = null;
        var csrfResponse = request(port, "GET", "/api/auth/csrf", null, null);
        String csrf = json.readTree(csrfResponse.body()).path("token").asText();
        // The API is internal HTTP behind the HTTPS proxy; cookie attributes still require Secure.
        assertFalse(csrfResponse.headers().allValues("Set-Cookie").isEmpty());
        assertTrue(csrfResponse.headers().allValues("Set-Cookie").stream().allMatch(value -> value.contains("Secure") && value.contains("HttpOnly") && value.contains("SameSite=Lax")));
        String form = "email=" + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + java.net.URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        assertEquals(200, request(port, "POST", "/api/auth/login", form, csrf).statusCode());
        csrf = csrf(port);
        assertEquals(200, request(port, "GET", "/api/auth/me", null, null).statusCode());
        assertEquals(403, request(port, "GET", "/api/admin/system/payments", null, null).statusCode());
        var before = businessSnapshot();
        clearInvocations(demoPayments, demoRefunds);
        for (String path : Set.of("/api/auth/signup", "/api/auth/verify-email", "/api/auth/password/request", "/api/auth/password/reset",
                "/api/guest/checkout", "/api/guest/verifications", "/api/guest/verifications/11111111-1111-4111-8111-111111111111/exchange",
                "/api/guest/verifications/11111111-1111-4111-8111-111111111111/verify", "/api/guest/checkout/password", "/api/guest/checkout/link",
                "/api/guest/access/login", "/api/guest/access/logout", "/api/guest/access/password", "/api/guest/access/recovery-code",
                "/api/guest/recovery/start", "/api/guest/recovery/11111111-1111-4111-8111-111111111111/exchange",
                "/api/guest/recovery/11111111-1111-4111-8111-111111111111/verify", "/api/guest/recovery/reset",
                "/api/payments/demo-number/sandbox", "/api/payments/demo-number/confirm", "/api/payments/webhooks/toss",
                "/api/orders?mode=SANDBOX")) {
            var blocked = request(port, "POST", path, "{}", csrf);
            assertEquals(503, blocked.statusCode(), path + " " + blocked.body());
            assertEquals("DEMO_FEATURE_UNAVAILABLE", json.readTree(blocked.body()).path("code").asText(), path);
            assertEquals("no-store", blocked.headers().firstValue("Cache-Control").orElse(""));
        }
        var body = request(port, "POST", "/api/orders", "{\"mode\":\"SANDBOX\"}", csrf);
        assertEquals(503, body.statusCode());
        assertEquals(before, businessSnapshot());
        verifyNoInteractions(demoPayments, demoRefunds);
        try {
            var hashes=new java.util.TreeMap<String,String>();
            for(Class<?> type:java.util.List.of(com.example.marketplace.platform.web.PublicDemoBoundary.class,
                    com.example.marketplace.platform.config.RuntimeSettingsConfiguration.class)) {
                String resource="/"+type.getName().replace('.','/')+".class";
                try(var bytes=type.getResourceAsStream(resource)) {
                    hashes.put(resource,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
                }
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"provider-boundary-proof.json"),json.writeValueAsString(Map.of(
                    "test","publicDemoKeepsExistingLoginAndRejectsEveryIssuanceAndSandboxWithoutMutation","database",System.getenv("D2_DB_NAME"),
                    "phoneProviderBeans",context.getBeansOfType(com.example.marketplace.phone.api.PhoneVerifications.class).size(),
                    "paymentProviderCalls",org.mockito.Mockito.mockingDetails(demoPayments).getInvocations().size(),
                    "refundProviderCalls",org.mockito.Mockito.mockingDetails(demoRefunds).getInvocations().size(),"businessUnchanged",true,"implementationHashes",hashes)));
        } catch(Exception failure) {throw new IllegalStateException("Public boundary evidence could not be retained",failure);}
        assertEquals(200, request(port, "POST", "/api/auth/logout", "{}", csrf).statusCode());
        assertEquals(401, request(port, "GET", "/api/auth/me", null, null).statusCode());
    }

    @Test void publicDemoDispatchersRejectSandboxWithoutProviderOrDatabaseEffects() {
        var before = businessSnapshot();
        clearInvocations(demoPayments, demoRefunds);
        var payment = context.getBean(PaymentProvider.class);
        var refund = context.getBean(RefundProvider.class);
        UUID id = UUID.randomUUID();
        var paymentInput = new PaymentProvider.Request(id, id, 100, "SANDBOX");
        var refundInput = new RefundProvider.Request(id, id, id, "synthetic", 100, "SANDBOX");
        assertThrows(DemoFeatureUnavailable.class, () -> payment.approve(paymentInput));
        assertThrows(DemoFeatureUnavailable.class, () -> payment.query(paymentInput));
        assertThrows(DemoFeatureUnavailable.class, () -> payment.cancel(paymentInput));
        assertThrows(DemoFeatureUnavailable.class, () -> payment.queryCancellation(paymentInput));
        assertThrows(DemoFeatureUnavailable.class, () -> refund.supports("SANDBOX"));
        assertThrows(DemoFeatureUnavailable.class, () -> refund.submit(refundInput));
        assertThrows(DemoFeatureUnavailable.class, () -> refund.query(refundInput));
        verifyNoInteractions(demoPayments, demoRefunds);
        assertEquals(before, businessSnapshot());
    }
}
