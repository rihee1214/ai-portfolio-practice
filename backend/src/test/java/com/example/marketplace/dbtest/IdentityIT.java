package com.example.marketplace.dbtest;


import com.example.marketplace.identity.api.AccountOperations;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.application.PasswordRecoveryService;
import com.example.marketplace.identity.infrastructure.JdbcPasswordRecoveryStore;
import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.application.TokenSecrets;
import com.example.marketplace.identity.domain.VerificationMailbox;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityIT extends PostgresContractFixture {
    @Test
    void accountStateLifecycleRevokesSessionsAndPreservesIdentityReference() throws Exception {
        UUID admin = activeMember("d-admin@example.com");
        UUID target = activeMember("d-target@example.com");
        var operator = authBrowser();
        var buyer = authBrowser();
        var second = authBrowser();
        assertEquals(200, authRequest(operator, "/api/auth/login", loginForm("d-admin@example.com", SIGNUP_PASSWORD), csrfToken(operator)).statusCode());
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        assertEquals(200, authRequest(second, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(second)).statusCode());
        String path = "/api/admin/accounts/" + target;
        var reason = Map.of("reason", "테스트 운영 조치");
        assertEquals(403, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", admin));
        try {
            assertEquals(403, accountRequest(operator, "POST", path + "/suspend", reason, null).statusCode());
            assertEquals(200, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(401, authRequest(buyer, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(second, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(200, accountRequest(operator, "POST", path + "/restore", reason, csrfToken(operator)).statusCode());
            assertEquals(401, authRequest(second, "/api/auth/me", null, null).statusCode());
            assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
            accountOperations.saveAddress(target, null, addressInput(true));
            accountOperations.updateProfile(target, "탈퇴자", "01012345678");
            recovery.request("d-target@example.com");
            String resetToken = resetMail("d-target@example.com");
            var withdrawal = Map.of("password", SIGNUP_PASSWORD, "confirmation", "WITHDRAW");
            jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.d_trade_reference (member_id uuid REFERENCES marketplace.identity_user(id), amount integer NOT NULL)");
            jdbc.update("INSERT INTO marketplace.d_trade_reference VALUES(:id,12300)", Map.of("id", target));
            assertEquals(403, accountRequest(buyer, "POST", "/api/account/withdraw", withdrawal, null).statusCode());
            assertEquals(400, accountRequest(buyer, "POST", "/api/account/withdraw", Map.of("password", "wrong", "confirmation", "WITHDRAW"), csrfToken(buyer)).statusCode());
            assertEquals(200, accountRequest(buyer, "POST", "/api/account/withdraw", withdrawal, csrfToken(buyer)).statusCode());
            assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(3L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals("", jdbc.queryForObject("SELECT display_name FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            for (String table : List.of("user_credential", "member_address", "email_verification_token", "password_reset_token", "role_assignment")) {
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", target), Integer.class));
            }
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
            assertEquals(12300, jdbc.queryForObject("SELECT amount FROM marketplace.d_trade_reference WHERE member_id=:id", Map.of("id", target), Integer.class));
            assertNull(jdbc.queryForObject("SELECT phone FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertFalse("d-target@example.com".equals(jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class)));
            assertEquals(401, authRequest(buyer, "/api/auth/me", null, null).statusCode());
            assertThrows(SignupException.class, () -> recovery.reset(resetToken, SIGNUP_PASSWORD));
            assertEquals(409, accountRequest(operator, "POST", path + "/restore", reason, csrfToken(operator)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", "/api/admin/accounts/" + admin + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", "/api/account/withdraw", withdrawal, csrfToken(operator)).statusCode());
            UUID replacement = activeMember("d-target@example.com");
            assertFalse(target.equals(replacement));
            assertEquals(target, jdbc.queryForObject("SELECT member_id FROM marketplace.d_trade_reference", Map.of(), UUID.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", admin));
        }
    }

    @Test
    void stateChangesRollbackAndSerializeAndCannotBypassServicePermission() throws Exception {
        var service = context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class);
        UUID actor = activeMember("d-service-actor@example.com");
        UUID target = activeMember("d-service-target@example.com");
        UUID pending = signup.register("d-pending@example.com", SIGNUP_PASSWORD);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> service.suspend(actor, target, "reason"));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", actor));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", actor));
        try {
            assertThrows(AccountException.class, () -> service.suspend(actor, target, " "));
            assertThrows(AccountException.class, () -> service.suspend(actor, pending, "reason"));
            assertThrows(AccountException.class, () -> service.restore(actor, target, "reason"));
            assertThrows(AccountException.class, () -> service.suspend(actor, UUID.randomUUID(), "reason"));
            assertThrows(AccountException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "wrong"));
            var browser = authBrowser();
            assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("d-service-target@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
            recovery.request("d-service-target@example.com");
            accountOperations.saveAddress(target, null, addressInput(true));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.account_state_audit ADD CONSTRAINT d_reject_audit CHECK (reason='impossible') NOT VALID");
            try {
                assertThrows(DataIntegrityViolationException.class, () -> service.suspend(actor, target, "reason"));
                assertThrows(DataIntegrityViolationException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "WITHDRAW"));
            } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.account_state_audit DROP CONSTRAINT d_reject_audit"); }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals(1, accountOperations.addresses(target).size());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", target), Integer.class));
            assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
            accountOperations.updateProfile(target, "보존 이름", "01012345678");
            // Fail after state/session/token/audit/anonymization/address writes, during credential deletion.
            jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.d_credential_reference (user_id uuid REFERENCES marketplace.user_credential(user_id))");
            jdbc.update("INSERT INTO marketplace.d_credential_reference VALUES(:id)", Map.of("id", target));
            assertThrows(DataIntegrityViolationException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "WITHDRAW"));
            assertEquals("d-service-target@example.com", accountOperations.profile(target).email());
            assertEquals("보존 이름", accountOperations.profile(target).displayName());
            assertEquals("01012345678", accountOperations.profile(target).phone());
            assertEquals(1, accountOperations.addresses(target).size());
            for (String table : List.of("user_credential", "role_assignment", "password_reset_token", "email_verification_token")) {
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", target), Integer.class));
            }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
            assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
            jdbc.update("DELETE FROM marketplace.d_credential_reference WHERE user_id=:id", Map.of("id", target));
            var results = race(() -> {
                try { service.suspend(actor, target, "race"); return "ok"; }
                catch (AccountException exception) { return "blocked"; }
            });
            assertEquals(1, results.stream().filter("ok"::equals).count());
            assertEquals(1, results.stream().filter("blocked"::equals).count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", actor));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", actor));
        }
    }

    @Test
    void initialAdminIsExplicitAtomicAndOnlyOneConcurrentWinner() throws Exception {
        var bootstrap = new com.example.marketplace.identity.infrastructure.InitialAdmin(jdbc, transactionManager);
        String database = jdbc.queryForObject("SELECT current_database()", Map.of(), String.class);
        UUID first = activeMember("bootstrap-first-c@example.com");
        UUID second = activeMember("bootstrap-second-c@example.com");
        UUID pending = signup.register("bootstrap-pending-c@example.com", SIGNUP_PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize(first, database, "wrong"));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(first, "wrong_database", "INITIAL_ADMIN"));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(pending, database, "INITIAL_ADMIN"));
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("bootstrap-first-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", second));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(first, database, "INITIAL_ADMIN"));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", second));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.authorization_audit ADD CONSTRAINT c_reject_audit CHECK (action <> 'INITIAL_ADMIN_GRANTED') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> bootstrap.initialize(first, database, "INITIAL_ADMIN"));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.authorization_audit DROP CONSTRAINT c_reject_audit"); }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission", Map.of(), Integer.class));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bootstrap.initialize(first, database, "INITIAL_ADMIN");
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission", Map.of(), Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit", Map.of(), Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", first), Long.class));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        var next = new java.util.concurrent.atomic.AtomicInteger();
        var results = race(() -> {
            UUID candidate = next.getAndIncrement() == 0 ? first : second;
            return runBootstrapCommand(candidate, database, "INITIAL_ADMIN", false) == 0 ? "ok" : "blocked";
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("blocked"::equals).count());
        UUID winner = jdbc.queryForObject("SELECT user_id FROM marketplace.admin_bootstrap WHERE id=1", Map.of(), UUID.class);
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", winner), Integer.class));
        assertEquals(List.of("ADMIN", "BUYER"), jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id ORDER BY role", Map.of("id", winner), String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit WHERE source='LOCAL_BOOTSTRAP' AND target_id=:id", Map.of("id", winner), Integer.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(winner, database, "INITIAL_ADMIN"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id", Map.of("id", winner.toString()), Integer.class));
        // Leave completed marker intact: removing ADMIN is not an authorization to bootstrap again.
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", winner));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", winner));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(second, database, "INITIAL_ADMIN"));
        assertEquals(1, runBootstrapCommand(second, database, "INITIAL_ADMIN", false));
        assertEquals(1, runBootstrapCommand(second, "wrong_database", "INITIAL_ADMIN", false));
        assertEquals(1, runBootstrapCommand(second, database, "wrong", false));
        assertEquals(1, runBootstrapCommand(second, database, "INITIAL_ADMIN", true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit", Map.of(), Integer.class));
    }

    @Test
    void adminPermissionsAreSeparateAndRevocationAppliesToExistingSession() throws Exception {
        UUID id = activeMember("permission-c@example.com");
        var servicePolicy = new com.example.marketplace.identity.infrastructure.JdbcAccessPolicy(jdbc);
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", id));
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("permission-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertEquals(200, authRequest(browser, "/api/admin/access", null, null).statusCode());
        for (String area : List.of("accounts", "content", "support", "settlements", "system")) {
            assertEquals(403, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> servicePolicy.require(id,
                    com.example.marketplace.identity.api.AccessArea.valueOf(area.toUpperCase(java.util.Locale.ROOT))));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,:permission)", Map.of("id", id, "permission", area.toUpperCase(java.util.Locale.ROOT)));
            assertEquals(200, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
            servicePolicy.require(id, com.example.marketplace.identity.api.AccessArea.valueOf(area.toUpperCase(java.util.Locale.ROOT)));
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", id));
            assertEquals(403, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
        }
        assertEquals(403, authRequest(browser, "/api/admin/unknown", null, null).statusCode());
        assertEquals(403, authRequest(browser, "/api/seller/access", null, null).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", id));
        assertEquals(200, authRequest(browser, "/api/seller/access", null, null).statusCode());
        assertEquals(200, authRequest(browser, "/api/account/profile", null, null).statusCode());
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", id));
        assertEquals(403, authRequest(browser, "/api/seller/access", null, null).statusCode());
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", id));
    }

    @Test
    void roleSpacesDenyAnonymousAndBuyerEvenWithForgedSignupFields() throws Exception {
        var browser = authBrowser();
        assertEquals(401, authRequest(browser, "/api/seller/access", null, null).statusCode());
        assertEquals(401, authRequest(browser, "/api/admin/access", null, null).statusCode());
        var signupResponse = authJsonPost(browser, "/api/auth/signup", Map.of("email", "forged-c@example.com",
                "password", SIGNUP_PASSWORD, "role", "ADMIN", "permissions", "SYSTEM", "isAdmin", "true"), csrfToken(browser));
        assertEquals(201, signupResponse.statusCode());
        signup.verifyEmail(mail("forged-c@example.com").token());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("forged-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        for (String path : List.of("/api/seller/access", "/api/admin/access", "/api/admin/accounts/access",
                "/api/admin/content/access", "/api/admin/support/access", "/api/admin/settlements/access", "/api/admin/system/access")) {
            var response = authRequest(browser, path, null, null);
            assertEquals(403, response.statusCode(), path);
            assertEquals("FORBIDDEN", authMapper.readTree(response.body()).get("code").asText());
        }
        assertEquals(List.of("BUYER"), authMapper.readTree(authRequest(browser, "/api/auth/me", null, null).body())
                .get("roles").valueStream().map(node -> node.asText()).toList());
    }

    @Test
    void accountHttpOwnerCsrfValidationAndPrivacyBoundaries() throws Exception {
        String email = "account-http-b@example.com";
        UUID member = activeMember(email);
        UUID other = activeMember("account-http-other-b@example.com");
        var foreign = accountOperations.saveAddress(other, null, addressInput(false));
        var browser = authBrowser();
        for (String path : List.of("/api/account/profile", "/api/account/addresses")) {
            assertEquals(401, accountRequest(browser, "GET", path, null, null).statusCode());
        }
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        String csrf = csrfToken(browser);
        var profileBody = Map.of("displayName", "사용자", "phone", "010-9876-5432", "email", "forged@example.com");
        assertEquals(403, accountRequest(browser, "PUT", "/api/account/profile", profileBody, null).statusCode());
        assertEquals("", accountOperations.profile(member).displayName());
        var profile = accountRequest(browser, "PUT", "/api/account/profile", profileBody, csrf);
        assertEquals(200, profile.statusCode());
        assertEquals(email, authMapper.readTree(profile.body()).get("email").asText());
        assertTrue(profile.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(400, accountRequest(browser, "PUT", "/api/account/profile", Map.of("displayName", " "), csrf).statusCode());
        assertEquals(403, accountRequest(browser, "POST", "/api/account/addresses", addressInput(false), null).statusCode());
        assertTrue(accountOperations.addresses(member).isEmpty());
        var created = accountRequest(browser, "POST", "/api/account/addresses", addressInput(false), csrf);
        assertEquals(201, created.statusCode());
        String id = authMapper.readTree(created.body()).get("id").asText();
        assertTrue(authMapper.readTree(created.body()).get("isDefault").asBoolean());
        assertEquals(403, accountRequest(browser, "PUT", "/api/account/addresses/" + id, addressInput(true), null).statusCode());
        assertEquals(403, accountRequest(browser, "DELETE", "/api/account/addresses/" + id, null, null).statusCode());
        assertEquals(1, accountOperations.addresses(member).size());
        assertEquals(404, accountRequest(browser, "PUT", "/api/account/addresses/" + foreign.id(), addressInput(true), csrf).statusCode());
        assertEquals(404, accountRequest(browser, "DELETE", "/api/account/addresses/" + foreign.id(), null, csrf).statusCode());
        assertEquals(400, accountRequest(browser, "POST", "/api/account/addresses", Map.of("label", "bad"), csrf).statusCode());
        assertEquals(200, accountRequest(browser, "PUT", "/api/account/addresses/" + id, addressInput(false), csrf).statusCode());
        var listing = accountRequest(browser, "GET", "/api/account/addresses", null, null);
        assertEquals(1, authMapper.readTree(listing.body()).get("addresses").size());
        assertTrue(listing.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(200, accountRequest(browser, "DELETE", "/api/account/addresses/" + id, null, csrf).statusCode());
        assertTrue(accountOperations.addresses(member).isEmpty());
        assertEquals(1, accountOperations.addresses(other).size());
    }

    @Test
    void recoveryFailureAfterSessionDeleteRollsBackAllState() throws Exception {
        String email = "recover-atomic-b@example.com";
        UUID member = activeMember(email);
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        recovery.request(email);
        String token = resetMail(email);
        var delegate = new JdbcPasswordRecoveryStore(jdbc);
        var failingStore = new com.example.marketplace.identity.domain.PasswordRecoveryStore() {
            @Override public UUID lockEligibleMember(String value) { return delegate.lockEligibleMember(value); }
            @Override public void replaceToken(UUID id, String hash, Instant created, Instant expires) {
                delegate.replaceToken(id, hash, created, expires);
            }
            @Override public boolean consume(String hash, String passwordHash, java.util.function.Supplier<Instant> currentTime) {
                assertTrue(delegate.consume(hash, passwordHash, currentTime));
                throw new IllegalStateException("injected-after-session-delete");
            }
        };
        var failing = new PasswordRecoveryService(failingStore, passwordHasher, resetMailbox, Clock.systemUTC(), transactionManager);
        assertThrows(IllegalStateException.class, () -> failing.reset(token, "atomic-new-password"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), Long.class));
        String hash = jdbc.queryForObject("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class);
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, hash));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        recovery.reset(token, "atomic-new-password");
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
    }

    @Test
    void recoveryConcurrentIssueAndConsumeLeavesOnlyReplacementValid() throws Exception {
        String email = "recover-reissue-b@example.com";
        activeMember(email);
        recovery.request(email);
        String old = resetMail(email);
        var issuer = new AtomicBoolean();
        race(() -> {
            if (issuer.compareAndSet(false, true)) { recovery.request(email); return "issued"; }
            try { recovery.reset(old, "concurrent-new-password"); return "consumed"; }
            catch (SignupException expected) { return expected.getMessage(); }
        });
        String replacement = resetMail(email);
        assertThrows(SignupException.class, () -> recovery.reset(old, SIGNUP_PASSWORD));
        recovery.reset(replacement, SIGNUP_PASSWORD);
    }

    @Test
    void recoveryRechecksExpiryAfterWaitingForMemberLock() throws Exception {
        String email = "recover-lock-expiry-b@example.com";
        UUID member = activeMember(email);
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        recoveryAt(issuedAt, resetMailbox).request(email);
        String token = resetMail(email);
        var current = new java.util.concurrent.atomic.AtomicReference<>(issuedAt.plusSeconds(1799));
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var service = new PasswordRecoveryService(new JdbcPasswordRecoveryStore(jdbc), passwordHasher,
                resetMailbox, moving, transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> reset;
            try (var blocker = source.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, member);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                }
                reset = pool.submit(() -> {
                    try { service.reset(token, "expired-while-waiting"); return "ok"; }
                    catch (SignupException expected) { return expected.getMessage(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database()
                            AND pid<>pg_backend_pid() AND wait_event_type='Lock'
                            AND query LIKE '%marketplace.identity_user%' AND query LIKE '%FOR UPDATE%'
                            """, Map.of(), Boolean.class);
                    if (waiting) { break; }
                    Thread.sleep(10);
                }
                current.set(issuedAt.plusSeconds(1800));
                blocker.commit();
                assertTrue(waiting, "Reset must demonstrably wait for the member lock before clock advances");
            }
            assertEquals("INVALID_RESET_TOKEN", reset.get(10, TimeUnit.SECONDS));
        }
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", member), Long.class));
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, jdbc.queryForObject(
                "SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class)));
    }

    @Test
    void recoveryLatestOnlyOneUseRevokesSessionsAndChangesPassword(CapturedOutput output) throws Exception {
        String email = "recover-b@example.com";
        UUID member = activeMember(email);
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        recovery.request(email);
        String old = resetMail(email);
        recovery.request(email);
        String current = resetMail(email);
        assertThrows(SignupException.class, () -> recovery.reset(old, "replacement-password"));
        assertThrows(SignupException.class, () -> recovery.reset(mail(email).token(), "replacement-password"));
        assertThrows(SignupException.class, () -> signup.verifyEmail(current));
        assertThrows(SignupException.class, () -> recovery.reset(current, "short"));
        assertEquals(TokenSecrets.hash(current), jdbc.queryForObject(
                "SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", member), String.class));
        recovery.reset(current, "replacement-password");
        assertThrows(SignupException.class, () -> recovery.reset(current, "another-password"));
        assertEquals(1L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", member), Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", member.toString()), Integer.class));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, "replacement-password"), csrfToken(browser)).statusCode());
        assertFalse(output.getAll().contains(current));
        assertFalse(output.getAll().contains("replacement-password"));
    }

    @Test
    void recoveryConcurrentConsumptionAndLatestRequestSerialize() throws Exception {
        UUID member = activeMember("recover-race-b@example.com");
        race(() -> { recovery.request("recover-race-b@example.com"); return "ok"; });
        String hash = jdbc.queryForObject("SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), String.class);
        String token = resetMailbox.messages().stream().filter(item -> TokenSecrets.hash(item.token()).equals(hash))
                .findFirst().orElseThrow().token();
        var results = race(() -> {
            try { recovery.reset(token, "new-racing-password"); return "ok"; }
            catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("INVALID_RESET_TOKEN"::equals).count());
    }

    @Test
    void recoveryExpiryRollbackAndIneligibleMembersLeaveStateUntouched() {
        UUID member = activeMember("recover-expire-b@example.com");
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        recoveryAt(now, resetMailbox).request("recover-expire-b@example.com");
        String token = resetMail("recover-expire-b@example.com");
        assertThrows(SignupException.class, () -> recoveryAt(now.plusSeconds(1800), resetMailbox).reset(token, SIGNUP_PASSWORD));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        VerificationMailbox failing = (recipient, secret, expiry) -> { throw new SignupException("MAIL_FAILURE"); };
        assertThrows(SignupException.class, () -> recoveryAt(now, failing).request("recover-expire-b@example.com"));
        assertEquals(TokenSecrets.hash(token), jdbc.queryForObject("SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), String.class));
        int before = resetMailbox.messages().size();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recoveryAt(now, resetMailbox).request("recover-expire-b@example.com");
            status.setRollbackOnly();
        });
        assertEquals(before, resetMailbox.messages().size());
        recoveryAt(now.plusSeconds(1799), resetMailbox).reset(token, "valid-before-expiry");
        signup.register("recover-pending-b@example.com", SIGNUP_PASSWORD);
        UUID suspended = activeMember("recover-suspended-b@example.com");
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", suspended));
        before = resetMailbox.messages().size();
        for (String email : List.of("recover-pending-b@example.com", "recover-suspended-b@example.com", "absent-b@example.com")) {
            recovery.request(email);
        }
        assertEquals(before, resetMailbox.messages().size());
    }

    @Test
    void recoveryHttpRequiresCsrfAndDoesNotRevealEligibility() throws Exception {
        var browser = authBrowser();
        assertEquals(403, authJsonPost(browser, "/api/auth/password/request", Map.of("email", "unknown-b@example.com"), null).statusCode());
        String csrf = csrfToken(browser);
        activeMember("recover-http-b@example.com");
        signup.register("recover-http-pending-b@example.com", SIGNUP_PASSWORD);
        for (String email : List.of("unknown-b@example.com", "recover-http-b@example.com", "recover-http-pending-b@example.com")) {
            var response = authJsonPost(browser, "/api/auth/password/request", Map.of("email", email), csrf);
            assertEquals(202, response.statusCode());
            assertEquals("{\"status\":\"PASSWORD_RESET_REQUESTED\"}", response.body());
        }
        String token = resetMail("recover-http-b@example.com");
        assertEquals(403, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", SIGNUP_PASSWORD), null).statusCode());
        assertEquals(400, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", "short"), csrf).statusCode());
        assertEquals(200, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", SIGNUP_PASSWORD), csrf).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
    }

    @Test
    void profileAndAddressOwnershipDefaultAndRollback() {
        UUID member = activeMember("address-owner-b@example.com");
        UUID other = activeMember("address-other-b@example.com");
        var profile = accountOperations.updateProfile(member, " 구매자😀 ", "010-1234-5678");
        assertEquals("구매자😀", profile.displayName());
        assertEquals("address-owner-b@example.com", profile.email());
        assertThrows(AccountException.class, () -> accountOperations.updateProfile(member, " ", null));
        assertEquals(profile, accountOperations.profile(member));
        var first = accountOperations.saveAddress(member, null, addressInput(false));
        assertTrue(first.isDefault());
        var second = accountOperations.saveAddress(member, null, addressInput(true));
        assertEquals(second.id(), accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).findFirst().orElseThrow().id());
        assertTrue(accountOperations.saveAddress(member, second.id(), addressInput(false)).isDefault());
        assertEquals("ADDRESS_NOT_FOUND", assertThrows(AccountException.class,
                () -> accountOperations.saveAddress(other, first.id(), addressInput(true))).getMessage());
        assertThrows(AccountException.class, () -> accountOperations.deleteAddress(other, first.id()));
        assertTrue(accountOperations.addresses(other).isEmpty());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountOperations.saveAddress(member, first.id(), addressInput(true));
            accountOperations.deleteAddress(member, second.id());
            status.setRollbackOnly();
        });
        assertEquals(2, accountOperations.addresses(member).size());
        assertEquals(second.id(), accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).findFirst().orElseThrow().id());
        accountOperations.deleteAddress(member, second.id());
        assertEquals(first.id(), accountOperations.addresses(member).getFirst().id());
        assertTrue(accountOperations.addresses(member).getFirst().isDefault());
        accountOperations.deleteAddress(member, first.id());
        assertTrue(accountOperations.addresses(member).isEmpty());
    }

    @Test
    void addressConcurrentFirstDefaultAndCapacityPreserveInvariants() throws Exception {
        UUID member = activeMember("address-race-b@example.com");
        race(() -> { accountOperations.saveAddress(member, null, addressInput(true)); return "ok"; });
        assertEquals(2, accountOperations.addresses(member).size());
        assertEquals(1, accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).count());
        for (int i = 2; i < 19; i++) { accountOperations.saveAddress(member, null, addressInput(false)); }
        var result = race(() -> {
            try { accountOperations.saveAddress(member, null, addressInput(true)); return "ok"; }
            catch (AccountException expected) { return expected.getMessage(); }
        });
        assertEquals(1, result.stream().filter("ok"::equals).count());
        assertEquals(1, result.stream().filter("ADDRESS_LIMIT"::equals).count());
        assertEquals(20, accountOperations.addresses(member).size());
        assertEquals(1, accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).count());
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

    @Test void fixtureAccountSignupIsolatesMailboxesAndKeepsRealVerificationAndSingleUse() throws Exception {
        signup.register("c-fixture-shared-existing@example.com",SIGNUP_PASSWORD);
        assertEquals(1,mailbox.messages().stream().filter(message->message.recipient().equals("c-fixture-shared-existing@example.com")).count());
        var sharedBefore=mailbox.messages();String first="c-fixture-inbox-one@example.com",second="c-fixture-inbox-two@example.com";UUID a=activeMember(first),b=activeMember(second);assertEquals(sharedBefore,mailbox.messages(),"synthetic account preparation must not consume or change the application's bounded HTTP mailbox");assertFalse(FIXTURE_ACCOUNT_MAILBOXES.get(a).messages().getFirst().token().equals(FIXTURE_ACCOUNT_MAILBOXES.get(b).messages().getFirst().token()));
        for(var entry:Map.of(a,first,b,second).entrySet()){var inbox=FIXTURE_ACCOUNT_MAILBOXES.get(entry.getKey());assertTrue(inbox!=null);assertEquals(1,inbox.messages().size());assertEquals(entry.getValue(),inbox.messages().getFirst().recipient());assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",Map.of("id",entry.getKey()),String.class));var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(entry.getValue(),SIGNUP_PASSWORD),csrfToken(browser)).statusCode());assertThrows(SignupException.class,()->signup.verifyEmail(inbox.messages().getFirst().token()));assertEquals(1,inbox.messages().size(),"consumed fixture verification evidence is retained");}
        var pending=prepareFixtureSignup("c-fixture-inbox-pending@example.com");assertEquals("PENDING_EMAIL",jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",Map.of("id",pending.id()),String.class));var message=pending.inbox().messages().getFirst();var browser=authBrowser();assertEquals(401,authRequest(browser,"/api/auth/login",loginForm(message.recipient(),SIGNUP_PASSWORD),csrfToken(browser)).statusCode());pending.service().verifyEmail(message.token());assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",Map.of("id",pending.id()),String.class));assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(message.recipient(),SIGNUP_PASSWORD),csrfToken(browser)).statusCode());assertThrows(SignupException.class,()->pending.service().verifyEmail(message.token()));assertEquals(sharedBefore,mailbox.messages());assertEquals(1,pending.inbox().messages().size());
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
        for (String email : List.of("wrong-a2@example.com", "missing-a2@example.com", "pending-a2@example.com", "invalid-email-input")) {
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
    void verificationRechecksExpiryAfterTokenLockWait() throws Exception {
        verifyExpiryAfterLockWait("email_verification_token");
    }

    @Test
    void verificationRechecksExpiryAfterMemberLockWait() throws Exception {
        verifyExpiryAfterLockWait("identity_user");
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
        UUID failing = signup.register("activation-write-failure@example.com", SIGNUP_PASSWORD);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.identity_user ADD CONSTRAINT r1_reject_activation CHECK (id <> '"
                + failing + "'::uuid OR state <> 'ACTIVE') NOT VALID");
        try {
            var failure = assertThrows(SignupException.class,
                    () -> signup.verifyEmail(mail("activation-write-failure@example.com").token()));
            assertEquals("VERIFICATION_UNAVAILABLE", failure.getMessage());
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.identity_user DROP CONSTRAINT r1_reject_activation"); }
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), String.class));
        assertNull(jdbc.queryForObject("SELECT email_verified_at FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", failing), java.sql.Timestamp.class));
        // A retry with the same token succeeds after the transient write constraint is removed.
        signup.verifyEmail(mail("activation-write-failure@example.com").token());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), String.class));
    }
}
