package com.example.marketplace.guest.infrastructure;

import com.example.marketplace.guest.api.GuestInput;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.phone.api.PhoneVerifications;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database transitions bracket external calls; no token/code is persisted. */
public final class JdbcGuestPurchases {
    public record Status(String state, UUID attemptId, Instant expiresAt, String maskedPhone, String phone) { }
    public record Verified(String name, String phone, String purpose) { }
    private record Attempt(UUID id, UUID checkout, String name, String phone, String provider, Instant issued, Instant expires, String state) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PhoneVerifications provider;
    private final byte[] credentialKey = new byte[32];
    public JdbcGuestPurchases(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, PhoneVerifications provider) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.clock = clock; this.provider = provider;
        new SecureRandom().nextBytes(credentialKey);
    }
    /** Local process-scoped PRF: concurrent requests derive the same opaque credential.
     * Restart invalidates local checkouts together with the in-memory provider. */
    public String credential(String browser) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(credentialKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(browser.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public String create(String browser) {
        available();
        String token = credential(browser);
        tx.executeWithoutResult(status -> {
            String binding = hash(browser);
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", binding), Object.class);
            jdbc.update("UPDATE marketplace.guest_checkout SET state='REVOKED' WHERE browser_hash=:b AND token_hash<>:t AND state IN ('OPEN','VERIFIED')", Map.of("b", binding, "t", hash(token)));
            Instant now = clock.instant();
            jdbc.update("INSERT INTO marketplace.guest_checkout(id,token_hash,browser_hash,purpose,expires_at,state,created_at) VALUES(:id,:t,:b,'PURCHASE',:e,'OPEN',:n) ON CONFLICT(token_hash) DO UPDATE SET state='OPEN',current_attempt=NULL,consumed_at=NULL,expires_at=EXCLUDED.expires_at",
                    Map.of("id", UUID.randomUUID(), "t", hash(token), "b", binding, "e", Timestamp.from(now.plusSeconds(1800)), "n", Timestamp.from(now)));
        });
        return token;
    }
    public Status status(String browser, String token) {
        available();
        return tx.execute(status -> {
            var checkout = checkout(browser, token);
            UUID current = (UUID) checkout.get("current_attempt");
            if (current == null) { return new Status("OPEN", null, instant(checkout, "expires_at"), "", ""); }
            Attempt attempt = attempt(current);
            String state = clock.instant().isBefore(attempt.expires()) ? attempt.state() : "EXPIRED";
            return new Status(state, current, attempt.expires(), "010-****-" + attempt.phone().substring(attempt.phone().length() - 4), "SENT".equals(state) ? attempt.phone() : "");
        });
    }
    public Status issue(String browser, String token, GuestInput input, String source) {
        available();
        Attempt reserved = tx.execute(status -> {
            var checkout = checkout(browser, token);
            UUID id = (UUID) checkout.get("id");
            quota("phone:" + input.phone(), 3, true);
            quota("source:" + source, 20, false);
            Instant now = clock.instant(); UUID aid = UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.guest_attempt(id,checkout_id,buyer_name,phone,purpose,state,issued_at,expires_at) VALUES(:a,:c,:n,:p,'PURCHASE','ISSUING',:t,:e)",
                    Map.of("a", aid, "c", id, "n", input.name(), "p", input.phone(), "t", Timestamp.from(now), "e", Timestamp.from(now.plusSeconds(180))));
            jdbc.update("UPDATE marketplace.guest_checkout SET state='OPEN',current_attempt=:a WHERE id=:c", Map.of("a", aid, "c", id));
            return new Attempt(aid, id, input.name(), input.phone(), null, now, now.plusSeconds(180), "ISSUING");
        });
        PhoneVerifications.Request request;
        try { request = provider.issue(reserved.phone()); }
        catch (RuntimeException failure) { fail(reserved, "REJECTED"); throw new GuestRejected(503); }
        return tx.execute(status -> {
            bound(browser, token, reserved.id(), input.phone(), input.purpose(), "ISSUING");
            if (request == null || request.id() == null || !clock.instant().isBefore(request.expiresAt())) { throw new GuestRejected(409); }
            jdbc.update("UPDATE marketplace.guest_attempt SET state='SENT',provider_id=:p WHERE id=:a", Map.of("p", request.id(), "a", reserved.id()));
            return new Status("SENT", reserved.id(), reserved.expires(), "010-****-" + input.phone().substring(input.phone().length() - 4), input.phone());
        });
    }
    public String exchange(String browser, String token, UUID id, String phone, String purpose, String code) {
        available();
        Attempt reserved = reserve(browser, token, id, phone, purpose, "SENT", "EXCHANGING");
        String custom;
        try { custom = provider.exchange(reserved.provider(), code); }
        catch (RuntimeException failure) { fail(reserved, "SENT"); throw new GuestRejected(409); }
        tx.executeWithoutResult(status -> {
            bound(browser, token, id, phone, purpose, "EXCHANGING");
            jdbc.update("UPDATE marketplace.guest_attempt SET state='EXCHANGED' WHERE id=:id", Map.of("id", id));
        });
        return custom;
    }
    public Status verify(String browser, String token, UUID id, String phone, String purpose, String proofToken) {
        available();
        Attempt reserved = reserve(browser, token, id, phone, purpose, "EXCHANGED", "CONFIRMING");
        PhoneVerifications.Proof proof;
        try { proof = provider.confirm(reserved.provider(), proofToken); }
        catch (RuntimeException failure) { fail(reserved, "EXCHANGED"); throw new GuestRejected(409); }
        if (proof == null || !reserved.phone().equals(proof.phone()) || proof.authenticatedAt() == null
                || proof.authenticatedAt().isBefore(reserved.issued().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                || proof.authenticatedAt().isAfter(clock.instant())) {
            fail(reserved, "REJECTED"); throw new GuestRejected(409);
        }
        return tx.execute(status -> {
            bound(browser, token, id, phone, purpose, "CONFIRMING");
            jdbc.update("UPDATE marketplace.guest_attempt SET state='VERIFIED',authenticated_at=:at WHERE id=:id", Map.of("id", id, "at", Timestamp.from(proof.authenticatedAt())));
            jdbc.update("UPDATE marketplace.guest_checkout SET state='VERIFIED' WHERE id=:id", Map.of("id", reserved.checkout()));
            return new Status("VERIFIED", id, reserved.expires(), "010-****-" + reserved.phone().substring(reserved.phone().length() - 4), "");
        });
    }
    /** Internal fixture contract only. Real order atomicity is deferred to P08-C. No HTTP endpoint. */
    public Verified consume(String browser, String token, UUID id, String phone, String purpose) {
        available();
        return tx.execute(status -> {
            Attempt attempt = bound(browser, token, id, phone, purpose, "VERIFIED");
            jdbc.update("UPDATE marketplace.guest_checkout SET state='CONSUMED',consumed_at=:now WHERE id=:id", Map.of("id", attempt.checkout(), "now", Timestamp.from(clock.instant())));
            jdbc.update("UPDATE marketplace.guest_attempt SET consumed_at=:now WHERE id=:id", Map.of("id", attempt.id(), "now", Timestamp.from(clock.instant())));
            return new Verified(attempt.name(), attempt.phone(), purpose);
        });
    }
    private Attempt reserve(String browser, String token, UUID id, String phone, String purpose, String from, String to) {
        return tx.execute(status -> {
            Attempt attempt = bound(browser, token, id, phone, purpose, from);
            var limit = limit("phone:" + attempt.phone());
            if (((Number) limit.get("failures")).intValue() >= 5) { throw new GuestRejected(429); }
            jdbc.update("UPDATE marketplace.guest_attempt SET state=:s WHERE id=:id", Map.of("s", to, "id", id));
            return attempt;
        });
    }
    private void fail(Attempt attempt, String next) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM marketplace.guest_checkout WHERE id=:id FOR UPDATE", Map.of("id", attempt.checkout()), UUID.class);
            var limit = limit("phone:" + attempt.phone());
            jdbc.update("UPDATE marketplace.guest_phone_limit SET failures=failures+1 WHERE key_hash=:k", Map.of("k", limit.get("key_hash")));
            jdbc.update("UPDATE marketplace.guest_attempt SET failures=LEAST(failures+1,5),state=CASE WHEN failures>=4 THEN 'REJECTED' ELSE :s END WHERE id=:id", Map.of("s", next, "id", attempt.id()));
        });
    }
    private Map<String,Object> checkout(String browser, String token) {
        if (browser == null || token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), credential(browser).getBytes(StandardCharsets.UTF_8))) { throw new GuestRejected(409); }
        var rows = jdbc.queryForList("SELECT * FROM marketplace.guest_checkout WHERE token_hash=:t AND browser_hash=:b FOR UPDATE", Map.of("t", hash(token), "b", hash(browser)));
        if (rows.size() != 1) { throw new GuestRejected(409); }
        var row = rows.getFirst();
        if (!clock.instant().isBefore(instant(row, "expires_at")) || !java.util.Set.of("OPEN", "VERIFIED").contains(row.get("state"))) { throw new GuestRejected(409); }
        return row;
    }
    private Attempt bound(String browser, String token, UUID id, String phone, String purpose, String expected) {
        var checkout = checkout(browser, token);
        if (!"PURCHASE".equals(purpose) || id == null || !id.equals(checkout.get("current_attempt"))) { throw new GuestRejected(409); }
        Attempt attempt = attempt(id);
        if (!attempt.checkout().equals(checkout.get("id")) || !attempt.phone().equals(PhoneVerifications.normalize(phone))
                || !expected.equals(attempt.state()) || !clock.instant().isBefore(attempt.expires())) { throw new GuestRejected(409); }
        return attempt;
    }
    private Attempt attempt(UUID id) {
        return jdbc.queryForObject("SELECT * FROM marketplace.guest_attempt WHERE id=:id", Map.of("id", id), (r,n) ->
                new Attempt(id, r.getObject("checkout_id", UUID.class), r.getString("buyer_name"), r.getString("phone"), r.getString("provider_id"),
                        r.getTimestamp("issued_at").toInstant(), r.getTimestamp("expires_at").toInstant(), r.getString("state")));
    }
    private Map<String,Object> limit(String key) {
        String hash = hash(key);
        jdbc.update("INSERT INTO marketplace.guest_phone_limit(key_hash,window_start) VALUES(:k,:n) ON CONFLICT DO NOTHING", Map.of("k", hash, "n", Timestamp.from(clock.instant())));
        return jdbc.queryForMap("SELECT * FROM marketplace.guest_phone_limit WHERE key_hash=:k FOR UPDATE", Map.of("k", hash));
    }
    private void quota(String key, int max, boolean cooldown) {
        var row = limit(key); Instant now = clock.instant(); String hash = (String) row.get("key_hash");
        boolean reset = !now.isBefore(instant(row, "window_start").plusSeconds(900));
        if (!reset && (((Number) row.get("sends")).intValue() >= max || ((Number) row.get("failures")).intValue() >= 5
                || cooldown && row.get("last_sent") != null && now.isBefore(instant(row, "last_sent").plusSeconds(60)))) { throw new GuestRejected(429); }
        if (reset) { jdbc.update("UPDATE marketplace.guest_phone_limit SET window_start=:n,sends=0,failures=0 WHERE key_hash=:k", Map.of("n", Timestamp.from(now), "k", hash)); }
        jdbc.update("UPDATE marketplace.guest_phone_limit SET sends=sends+1,last_sent=:n WHERE key_hash=:k", Map.of("n", Timestamp.from(now), "k", hash));
    }
    private static Instant instant(Map<String,Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private void available() { if (provider == null) { throw new GuestRejected(503); } }
}
