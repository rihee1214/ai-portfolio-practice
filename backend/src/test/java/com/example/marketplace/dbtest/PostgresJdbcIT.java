package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.application.SignupService;
import com.example.marketplace.identity.application.TokenSecrets;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.domain.VerificationMailbox;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.example.marketplace.jdbcfixture.AccountRepository;
import com.example.marketplace.jdbcfixture.BatchService;
import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import com.example.marketplace.jdbcfixture.TransferRejectedException;
import com.example.marketplace.jdbcfixture.TransferService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"db", "c3-fixture", "local-mailbox", "local-http"})
@ExtendWith(OutputCaptureExtension.class)
@ContextConfiguration(initializers = DedicatedPostgresInitializer.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/", "spring.sql.init.mode=never", "spring.flyway.clean-disabled=true",
            "server.address=127.0.0.1", "spring.jmx.enabled=true", "spring.jmx.unique-names=true",
            "management.endpoints.jmx.domain=com.example.marketplace.dbfollowup",
            "spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(PostgresJdbcIT.TestMailboxEndpoint.class)
class PostgresJdbcIT {

    private HttpResponse<String> authJsonPost(HttpClient browser, String path, Map<String, String> body, String csrf)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signupAndVerificationHttpRejectCsrfAndBadInputWithoutChanges() throws Exception {
        var browser = authBrowser();
        var body = Map.of("email", "guard-a3@example.com", "password", "guard-a3-password");
        assertEquals(403, authJsonPost(browser, "/api/auth/signup", body, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), Integer.class));
        String csrf = csrfToken(browser);
        assertEquals(400, authJsonPost(browser, "/api/auth/signup",
                Map.of("email", "invalid", "password", "short"), csrf).statusCode());
        assertEquals(201, authJsonPost(browser, "/api/auth/signup", body, csrf).statusCode());
        assertEquals(409, authJsonPost(browser, "/api/auth/signup", body, csrf).statusCode());
        String token = mail("guard-a3@example.com").token();
        var verification = Map.of("token", token);
        assertEquals(403, authJsonPost(browser, "/api/auth/verify-email", verification, null).statusCode());
        assertEquals("PENDING_EMAIL", jdbc.queryForObject(
                "SELECT state FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.email_verification_token WHERE token_hash=:hash AND consumed_at IS NULL",
                Map.of("hash", TokenSecrets.hash(token)), Integer.class));
        assertEquals(200, authJsonPost(browser, "/api/auth/verify-email", verification, csrf).statusCode());
        assertEquals(400, authJsonPost(browser, "/api/auth/verify-email", verification, csrf).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), Integer.class));
    }
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("c3-fixture")
    static class TestMailboxEndpoint {
        private final LocalTestMailbox mailbox;
        TestMailboxEndpoint(LocalTestMailbox mailbox) { this.mailbox = mailbox; }
        @org.springframework.web.bind.annotation.GetMapping("/__test/mail")
        Map<String, String> token(@org.springframework.web.bind.annotation.RequestParam String email,
                jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            return Map.of("token", mailbox.messages().stream().filter(item -> item.recipient().equals(email))
                    .findFirst().orElseThrow().token());
        }
    }

    private void runAuthBrowsersIfRequested() throws Exception {
        if (!"true".equals(System.getenv("A3_RUN_BROWSER"))) { return; }
        var root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        var node = root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe");
        var builder = new ProcessBuilder(node.toString(), "node_modules/@playwright/test/cli.js", "test",
                "--config=auth-e2e/playwright.config.ts").directory(root.resolve("frontend").toFile());
        builder.environment().put("A3_API_PORT", Integer.toString(port));
        builder.environment().put("PATH", node.getParent() + java.io.File.pathSeparator + System.getenv("PATH"));
        builder.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"), "browser.txt").toFile());
        var process = builder.start();
        if (!process.waitFor(150, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Auth browser verification timed out");
        }
        assertEquals(0, process.exitValue(), "See isolated evidence browser.txt");
    }

    @Test
    void authMutationLimitReturns429WithoutChangingAccount() throws Exception {
        var browser = authBrowser();
        String token = csrfToken(browser);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, authRequest(browser, "/api/auth/login",
                    loginForm("limited-a3@example.com", "wrong-password"), token).statusCode());
        }
        var blocked = authRequest(browser, "/api/auth/login", loginForm(" LIMITED-A3@example.com ", "wrong"), token);
        assertEquals(429, blocked.statusCode());
        assertEquals("60", blocked.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("RATE_LIMITED", authMapper.readTree(blocked.body()).get("code").asText());
    }
    @Test
    void signupHttpRequiresCsrfAndReturnsNoSecrets() throws Exception {
        var browser = authBrowser();
        String token = csrfToken(browser);
        var response = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/signup"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"http-a3@example.com\",\"password\":\"a3-test-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        assertFalse(response.body().contains("password"));
        assertFalse(response.body().contains("token"));
        var verify = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/verify-email"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(Map.of("token", mail("http-a3@example.com").token()))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, verify.statusCode());
        runAuthBrowsersIfRequested();
    }

    private final tools.jackson.databind.json.JsonMapper authMapper = tools.jackson.databind.json.JsonMapper.builder().build();

    private HttpClient authBrowser() {
        return HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(
                null, java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpResponse<String> authRequest(HttpClient browser, String path, String form, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (form == null) { request.GET(); } else {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
        }
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrfToken(HttpClient browser) throws Exception {
        var response = authRequest(browser, "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode());
        return authMapper.readTree(response.body()).get("token").asText();
    }

    private String loginForm(String email, String password) {
        return "email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
    }

    private UUID activeMember(String email) {
        UUID id = signup.register(email, SIGNUP_PASSWORD);
        signup.verifyEmail(mail(email).token());
        return id;
    }

    private String cookieValue(HttpClient browser) {
        return ((java.net.CookieManager) browser.cookieHandler().orElseThrow()).getCookieStore().getCookies()
                .stream().filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
    }

    @Test
    void loginRotatesSessionPersistsInJdbcAndLogoutRevokesCookie(CapturedOutput output) throws Exception {
        UUID id = activeMember("login-a2@example.com");
        var browser = authBrowser();
        String beforeToken = csrfToken(browser);
        String beforeCookie = cookieValue(browser);
        var login = authRequest(browser, "/api/auth/login", loginForm(" LOGIN-A2@example.com ", SIGNUP_PASSWORD), beforeToken);
        assertEquals(200, login.statusCode());
        assertFalse(login.headers().firstValue("Location").isPresent());
        String afterCookie = cookieValue(browser);
        assertFalse(beforeCookie.equals(afterCookie));
        String cookieHeader = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Lax"));
        assertFalse(cookieHeader.contains("Secure"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));

        // Another HTTP client restores authentication from the same JDBC-backed cookie.
        var restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .header("Cookie", "SESSION=" + afterCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, restored.statusCode());
        assertEquals(id.toString(), authMapper.readTree(restored.body()).get("id").asText());
        assertTrue(restored.body().contains("BUYER"));
        assertFalse(restored.body().contains("email"));
        assertFalse(restored.body().contains("password"));
        assertTrue(restored.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var attributes = jdbc.queryForList("""
                SELECT a.attribute_bytes FROM marketplace.spring_session_attributes a
                JOIN marketplace.spring_session s ON s.primary_id=a.session_primary_id WHERE s.principal_name=:id
                """, Map.of("id", id.toString()), byte[].class);
        assertFalse(attributes.isEmpty());
        for (byte[] attribute : attributes) {
            String serialized = new String(attribute, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(serialized.contains("{argon2id}"));
            assertFalse(serialized.contains("A1-private-passphrase"));
        }

        assertEquals(403, authRequest(browser, "/api/auth/logout", "", beforeToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        String afterToken = csrfToken(browser);
        assertFalse(beforeToken.equals(afterToken));
        assertEquals(200, authRequest(browser, "/api/auth/logout", "", afterToken).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        var replay = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .header("Cookie", "SESSION=" + afterCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, replay.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));
        assertEquals(403, authRequest(browser, "/api/auth/login", loginForm("login-a2@example.com", SIGNUP_PASSWORD),
                afterToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("login-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        assertFalse(output.getAll().contains(SIGNUP_PASSWORD));
        assertFalse(output.getAll().contains(beforeToken));
    }

    @Test
    void invalidCredentialsAndUnverifiedAccountsHaveSameJsonFailure() throws Exception {
        activeMember("wrong-a2@example.com");
        signup.register("pending-a2@example.com", SIGNUP_PASSWORD);
        var browser = authBrowser();
        String token = csrfToken(browser);
        for (String email : List.of("wrong-a2@example.com", "missing-a2@example.com", "pending-a2@example.com", "bad")) {
            String password = email.startsWith("wrong") ? "incorrect-password" : SIGNUP_PASSWORD;
            var response = authRequest(browser, "/api/auth/login", loginForm(email, password), token);
            assertEquals(401, response.statusCode());
            assertEquals("AUTHENTICATION_REQUIRED", authMapper.readTree(response.body()).get("code").asText());
            assertEquals(response.headers().firstValue("X-Request-ID").orElseThrow(),
                    authMapper.readTree(response.body()).get("requestId").asText());
            assertFalse(response.headers().firstValue("Location").isPresent());
            assertFalse(response.body().contains(email));
        }
    }

    @Test
    void csrfCannotBeOmittedOrCopiedBetweenSessions() throws Exception {
        var first = authBrowser();
        var second = authBrowser();
        String token = csrfToken(first);
        csrfToken(second);
        for (String path : List.of("/api/auth/login", "/api/auth/logout", "/api/unknown-mutation")) {
            assertEquals(403, authRequest(first, path, "", null).statusCode());
            assertEquals(403, authRequest(second, path, "", token).statusCode());
        }
        assertFalse(authRequest(first, "/api/auth/logout", null, null).statusCode() == 200);
    }

    @Test
    void currentRolesAndAccountVersionAreCheckedEveryRequest() throws Exception {
        UUID id = activeMember("version-a2@example.com");
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("version-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES (:id, 'SELLER')", Map.of("id", id));
        assertTrue(authRequest(browser, "/api/auth/me", null, null).body().contains("SELLER"));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", id));
        assertFalse(authRequest(browser, "/api/auth/me", null, null).body().contains("SELLER"));
        jdbc.update("UPDATE marketplace.identity_user SET auth_version=auth_version+1 WHERE id=:id", Map.of("id", id));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));
    }

    @Test
    void suspendedAndWithdrawnAccountsLoseExistingSessions() throws Exception {
        for (String state : List.of("SUSPENDED", "WITHDRAWN")) {
            String email = state.toLowerCase(java.util.Locale.ROOT) + "-a2@example.com";
            UUID id = activeMember(email);
            var browser = authBrowser();
            assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD),
                    csrfToken(browser)).statusCode());
            jdbc.update("UPDATE marketplace.identity_user SET state=:state WHERE id=:id", Map.of("id", id, "state", state));
            assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD),
                    csrfToken(browser)).statusCode());
        }
    }

    @Test
    void expiredJdbcSessionCannotAuthenticateOrReuseCsrf() throws Exception {
        UUID id = activeMember("expiry-a2@example.com");
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        String oldToken = csrfToken(browser);
        jdbc.update("""
                UPDATE marketplace.spring_session SET last_access_time=0, expiry_time=0 WHERE principal_name=:id
                """, Map.of("id", id.toString()));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(403, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                oldToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
    }

    @Test
    void anonymousAuthenticationContract() throws Exception {
        HttpClient browser = HttpClient.newHttpClient();
        var me = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode());
        assertTrue(me.body().contains("AUTHENTICATION_REQUIRED"));
        var csrf = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrf.statusCode());
        assertTrue(csrf.body().contains("X-CSRF-TOKEN"));
        assertTrue(csrf.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }
    private static final String SIGNUP_PASSWORD = " A1-private-passphrase-가나다 ";
    @Autowired
    private SignupOperations signup;
    @Autowired
    private IdentityStore identityStore;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private LocalTestMailbox mailbox;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @LocalServerPort
    private int port;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private MBeanServer mBeanServer;
    @Autowired
    private DataSource source;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private TransferService transfers;
    @Autowired
    private BatchService batches;
    @Autowired
    private Flyway flyway;

    private LocalTestMailbox.Message mail(String email) {
        return mailbox.messages().stream().filter(item -> item.recipient().equals(email)).findFirst().orElseThrow();
    }

    private SignupService serviceAt(Instant now, VerificationMailbox targetMailbox) {
        return new SignupService(identityStore, passwordHasher, targetMailbox,
                Clock.fixed(now, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void signupPersistsOnlyHashesAndBuyerThenVerificationActivates(CapturedOutput output) {
        UUID id = signup.register("  New.Buyer@EXAMPLE.COM ", SIGNUP_PASSWORD);
        String hash = jdbc.queryForObject("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id",
                Map.of("id", id), String.class);
        assertTrue(hash.startsWith("{argon2id}$argon2id$"));
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, hash));
        assertFalse(passwordHasher.matches(SIGNUP_PASSWORD.strip(), hash));
        assertEquals("BUYER", jdbc.queryForObject("SELECT role FROM marketplace.role_assignment WHERE user_id=:id",
                Map.of("id", id), String.class));
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), String.class));
        var message = mail("new.buyer@example.com");
        assertEquals(TokenSecrets.hash(message.token()), jdbc.queryForObject(
                "SELECT token_hash FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", id), String.class));
        signup.verifyEmail(message.token());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), Long.class));
        assertEquals("INVALID_EMAIL_TOKEN", assertThrows(SignupException.class,
                () -> signup.verifyEmail(message.token())).getMessage());
        assertFalse(output.getAll().contains(SIGNUP_PASSWORD));
        assertFalse(output.getAll().contains(message.token()));
        assertFalse(output.getAll().contains(hash));
        assertFalse(message.toString().contains(message.token()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.flyway_schema_history WHERE version='3' AND success",
                Map.of(), Integer.class));
    }

    @Test
    void normalizedDuplicateIsRejectedWithoutExtraCredentialOrMail() {
        signup.register("duplicate@example.com", SIGNUP_PASSWORD);
        int mailCount = mailbox.messages().size();
        assertEquals("EMAIL_ALREADY_REGISTERED", assertThrows(SignupException.class,
                () -> signup.register(" DUPLICATE@example.COM ", SIGNUP_PASSWORD)).getMessage());
        assertEquals(mailCount, mailbox.messages().size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "duplicate@example.com"), Integer.class));
    }

    private List<String> race(Callable<String> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                start.await();
                return action.call();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDuplicateHasExactlyOneCommitAndOneMail() throws Exception {
        var results = race(() -> {
            try {
                signup.register("race@example.com", SIGNUP_PASSWORD);
                return "ok";
            } catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("EMAIL_ALREADY_REGISTERED"::equals).count());
        assertEquals(1, mailbox.messages().stream().filter(item -> item.recipient().equals("race@example.com")).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email='race@example.com'",
                Map.of(), Integer.class));
    }

    @Test
    void concurrentTokenConsumptionHasExactlyOneSuccess() throws Exception {
        signup.register("token-race@example.com", SIGNUP_PASSWORD);
        String token = mail("token-race@example.com").token();
        var results = race(() -> {
            try {
                signup.verifyEmail(token);
                return "ok";
            } catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("INVALID_EMAIL_TOKEN"::equals).count());
    }

    @Test
    void expiryBoundaryIsExclusiveAndExpiredTokenRemainsUnconsumed() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var service = serviceAt(now, mailbox);
        UUID expired = service.register("expiry@example.com", SIGNUP_PASSWORD);
        service.register("before-expiry@example.com", SIGNUP_PASSWORD);
        String token = mail("expiry@example.com").token();
        assertThrows(SignupException.class,
                () -> serviceAt(now.plusSeconds(1800), mailbox).verifyEmail(token));
        serviceAt(now.plusSeconds(1799), mailbox).verifyEmail(mail("before-expiry@example.com").token());
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", expired), String.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id",
                Map.of("id", expired), java.sql.Timestamp.class));
        assertThrows(SignupException.class, () -> signup.verifyEmail("unknown"));
        assertThrows(SignupException.class, () -> signup.verifyEmail(TokenSecrets.generate()));
    }

    @Test
    void mailboxPreparationFailureRollsBackAllSignupRows() {
        VerificationMailbox unavailable = (recipient, token, expires) -> { throw new SignupException("MAILBOX_UNAVAILABLE"); };
        var service = serviceAt(Instant.now(), unavailable);
        int before = jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token", Map.of(), Integer.class);
        assertThrows(SignupException.class, () -> service.register("failed-mail@example.com", SIGNUP_PASSWORD));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email='failed-mail@example.com'",
                Map.of(), Integer.class));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token", Map.of(), Integer.class));
        assertTrue(mailbox.messages().stream().noneMatch(item -> item.recipient().equals("failed-mail@example.com")));
    }

    @Test
    void callerRollbackAlsoRollsBackSignupAndDiscardsUnpublishedMail() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            signup.register("outer-rollback@example.com", SIGNUP_PASSWORD);
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.identity_user WHERE email='outer-rollback@example.com'",
                Map.of(), Integer.class));
        assertTrue(mailbox.messages().stream().noneMatch(item -> item.recipient().equals("outer-rollback@example.com")));
    }

    @Test
    void invalidInputDoesNotWriteOrDeliver() {
        int before = jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class);
        assertThrows(SignupException.class, () -> signup.register("bad address", SIGNUP_PASSWORD));
        assertThrows(SignupException.class, () -> signup.register("short@example.com", "short"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class));
    }

    @Test
    void activationFailureRollsBackTokenConsumption() {
        UUID id = signup.register("suspended@example.com", SIGNUP_PASSWORD);
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", id));
        assertThrows(SignupException.class, () -> signup.verifyEmail(mail("suspended@example.com").token()));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id",
                Map.of("id", id), java.sql.Timestamp.class));
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void databaseSettingsValidatorAndActualDataSourceCoexist() {
        assertTrue(context.containsBean("requiredDatabaseSettings"));
        assertEquals(System.getenv("D2_DB_URL"), context.getEnvironment().getProperty("spring.datasource.url"));
        assertEquals(System.getenv("D2_DB_USERNAME"), context.getEnvironment().getProperty("spring.datasource.username"));
        assertEquals("none", context.getEnvironment().getProperty("management.endpoints.access.default"));
        assertEquals("read-only", context.getEnvironment().getProperty("management.endpoint.health.access"));
        assertTrue(context.containsBean("dbHealthContributor"));
    }

    @Test
    void invalidSettingsFailBeforeSingletonsWithRealDatabaseAutoConfigurationEnabled() {
        for (String key : new String[] {"url", "username", "password"}) {
            var singletonCreated = new AtomicBoolean();
            var databaseCreationAttempted = new AtomicBoolean();
            new ApplicationContextRunner().withInitializer(candidate -> {
                candidate.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
                        if (beanName.equals("dataSource") || beanName.equals("flyway")) {
                            databaseCreationAttempted.set(true);
                        }
                        return null;
                    }
                });
                candidate.getEnvironment().setConversionService(new ApplicationConversionService());
                candidate.getEnvironment().getPropertySources().remove("systemEnvironment");
                candidate.getEnvironment().getPropertySources().remove("systemProperties");
                candidate.getEnvironment().setActiveProfiles("db");
            }).withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(MarketplaceApplication.class)
                    .withPropertyValues("spring.config.location=classpath:/",
                            "DB_URL=" + System.getenv("D2_DB_URL"), "DB_USERNAME=marketplace_test",
                            "DB_PASSWORD=unused-synthetic-password", "spring.datasource." + key + "= ")
                    .withBean("singletonProbe", Object.class, () -> {
                        singletonCreated.set(true);
                        return new Object();
                    }).run(candidate -> {
                        assertThat(candidate).hasFailed();
                        assertThat(candidate.getStartupFailure())
                                .hasStackTraceContaining("Required database settings are missing, blank or unresolved:")
                                .hasStackTraceContaining("spring.datasource." + key);
                        assertFalse(singletonCreated.get());
                        assertFalse(databaseCreationAttempted.get());
                    });
        }
    }

    @Test
    void realDatabaseHealthPreservesStatusOnlyAndRequestId() throws Exception {
        var response = request("GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertTrue(response.headers().firstValue("X-Request-ID").orElse("")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        var head = request("HEAD", "/actuator/health");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(405, request("POST", "/actuator/health").statusCode());
    }

    @Test
    void databaseProfileDoesNotExposeManagementDetailsOrJmx() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/configprops",
                "/actuator/metrics", "/actuator/beans", "/actuator/health/db",
                "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertEquals(404, request("GET", path).statusCode(), path);
        }
        assertTrue(mBeanServer.queryNames(new ObjectName("com.example.marketplace.dbfollowup:*"), null).isEmpty());
        assertFalse(context.containsBean("heapDumpWebEndpoint"));
    }

    private void seed(long id, String label, String note) {
        jdbc.update("INSERT INTO c3_fixture.account VALUES (:id, :label, :balance, :note)",
                new MapSqlParameterSource().addValue("id", id).addValue("label", label)
                        .addValue("balance", new BigDecimal("100.50")).addValue("note", note));
    }

    /** Bypasses the Spring connection pool and transaction context to observe committed state. */
    private String observe(String sql, Object parameter) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("D2_DB_URL"),
                System.getenv("D2_DB_USERNAME"), System.getenv("D2_DB_PASSWORD"));
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private void balance(long id, String expected) throws Exception {
        assertEquals(expected, observe("SELECT balance FROM c3_fixture.account WHERE id = ?", id));
    }

    private void entryCount(String reference, String expected) throws Exception {
        assertEquals(expected, observe("SELECT count(*) FROM c3_fixture.transfer_entry WHERE reference = ?", reference));
    }

    @Test
    void bootUsesVerifiedPostgresAndUnprivilegedRole() throws Exception {
        try (var connection = source.getConnection()) {
            DedicatedDatabaseTarget.verifyConnection(connection, System.getenv("D2_DB_NAME"));
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(18, connection.getMetaData().getDatabaseMajorVersion());
        }
    }

    @Test
    void sameProductionV1AndFixtureV2MigrateOnce() {
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.flyway_schema_history WHERE version IN ('1','2') AND success",
                Map.of(), Integer.class));
        assertEquals("initialize schema", jdbc.queryForObject(
                "SELECT description FROM marketplace.flyway_schema_history WHERE version = '1'", Map.of(), String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void parametersAndMappingHandleQuotesUnicodeDecimalAndNull() {
        seed(101, "구매자 O'Reilly", null);
        seed(102, "판매자", "설명");
        var account = accounts.findByLabel("구매자 O'Reilly").orElseThrow();
        assertEquals(101, account.id());
        assertEquals("구매자 O'Reilly", account.label());
        assertEquals(new BigDecimal("100.50"), account.balance());
        assertNull(account.note());
        assertEquals("설명", accounts.findByLabel("판매자").orElseThrow().note());
        assertTrue(accounts.findByLabel("missing").isEmpty());
        assertTrue(accounts.findByLabel("' OR 1=1 --").isEmpty());
    }

    @Test
    void successCommitsBothBalancesAndEntry() throws Exception {
        seed(201, "commit-from", null);
        seed(202, "commit-to", null);
        transfers.transfer(201, 202, new BigDecimal("25.25"), "commit");
        balance(201, "75.25");
        balance(202, "125.75");
        entryCount("commit", "1");
    }

    @Test
    void duplicateReferenceRollsBackUpdatesAlreadyMade() throws Exception {
        seed(301, "duplicate-from", null);
        seed(302, "duplicate-to", null);
        transfers.transfer(301, 302, new BigDecimal("10.00"), "duplicate");
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(301, 302, new BigDecimal("20.00"), "duplicate"));
        balance(301, "90.50");
        balance(302, "110.50");
        entryCount("duplicate", "1");
    }

    @Test
    void checkedFailureAfterDebitRollsBack() throws Exception {
        seed(401, "checked-from", null);
        assertThrows(TransferRejectedException.class,
                () -> transfers.transfer(401, 9999, new BigDecimal("30.00"), "checked"));
        balance(401, "100.50");
        entryCount("checked", "0");
    }

    @Test
    void databaseConstraintsPreventNegativeBalanceAndOrphanEntry() throws Exception {
        seed(501, "constraint-from", null);
        seed(502, "constraint-to", null);
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(501, 502, new BigDecimal("200.00"), "insufficient"));
        balance(501, "100.50");
        balance(502, "100.50");
        entryCount("insufficient", "0");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO c3_fixture.transfer_entry VALUES ('orphan', 9999, 501, 1)", Map.of()));
        entryCount("orphan", "0");
    }

    @Test
    void caughtRequiredFailureRollsBackEarlierSuccessfulTransfer() throws Exception {
        seed(61, "batch-from", null);
        seed(62, "batch-to", null);
        assertThrows(UnexpectedRollbackException.class, () -> batches.transferThenCatchFailure());
        balance(61, "100.50");
        balance(62, "100.50");
        entryCount("batch-first", "0");
    }
}
