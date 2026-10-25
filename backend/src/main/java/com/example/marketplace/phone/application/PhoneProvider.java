package com.example.marketplace.phone.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Local provider boundary only. Never grants a purchase or account session. */
public final class PhoneProvider {
    public record Request(String id, String session, Instant expiresAt) { }
    public record Proof(String phone, Instant authenticatedAt) { }
    public interface Gateway {
        String issue(String phone);
        String exchange(String session, String code);
        Proof confirm(String session, String token);
    }
    private final Clock clock;
    private final Gateway gateway;
    private final Map<String, Window> numbers = new HashMap<>();
    private final Map<String, Attempt> requests = new HashMap<>();
    private static final class Window {
        private final Instant start;
        private Instant lastSent;
        private String currentId;
        private int sends;
        private int failures;
        Window(Instant start) { this.start = start; }
    }
    private record Attempt(String phone, String session, Instant issuedAt, Instant expiresAt) { }

    public PhoneProvider(String mode, String address, Clock clock, Gateway gateway) {
        if (!"local-test".equals(mode) || !"127.0.0.1".equals(address)) {
            throw new IllegalArgumentException("Phone emulator requires explicit loopback local-test mode");
        }
        this.clock = Objects.requireNonNull(clock);
        this.gateway = Objects.requireNonNull(gateway);
    }

    public static String normalize(String phone) {
        if (phone == null) { throw new IllegalArgumentException("Invalid phone"); }
        if (phone.matches("010-[0-9]{4}-[0-9]{4}")) { phone = phone.replace("-", ""); }
        if (phone.matches("010[0-9]{8}")) { return "+82" + phone.substring(1); }
        if (phone.matches("\\+8210[0-9]{8}")) { return phone; }
        throw new IllegalArgumentException("Invalid phone");
    }

    public synchronized Request issue(String input) {
        String phone = normalize(input);
        Instant now = clock.instant();
        Window window = numbers.get(phone);
        if (window == null || !now.isBefore(window.start.plusSeconds(900))) {
            if (window != null) { requests.remove(window.currentId); }
            window = new Window(now);
            numbers.put(phone, window);
        }
        if (window.failures >= 5 || window.sends >= 3
                || window.lastSent != null && now.isBefore(window.lastSent.plusSeconds(60))) {
            throw rejected();
        }
        // Count provider failures too; unavailable transport must not permit unbounded issuance.
        window.sends++;
        window.lastSent = now;
        requests.remove(window.currentId);
        window.currentId = null;
        String session = gateway.issue(phone);
        if (session == null || session.isBlank()) { throw rejected(); }
        String id = UUID.randomUUID().toString();
        Instant issuedAt = clock.instant();
        requests.put(id, new Attempt(phone, session, issuedAt, issuedAt.plusSeconds(300)));
        window.currentId = id;
        return new Request(id, session, issuedAt.plusSeconds(300));
    }

    public synchronized String exchange(String id, String code) {
        Attempt attempt = requests.get(id);
        if (attempt == null) { throw rejected(); }
        Window window = numbers.get(attempt.phone());
        if (window.failures >= 5 || !id.equals(window.currentId) || !clock.instant().isBefore(attempt.expiresAt())) { throw rejected(); }
        try {
            if (code == null || !code.matches("[0-9]{6}")) { throw rejected(); }
            String customToken = gateway.exchange(attempt.session(), code);
            if (!clock.instant().isBefore(attempt.expiresAt())) { throw rejected(); }
            return customToken;
        } catch (RuntimeException failure) { window.failures++; throw rejected(); }
    }

    public synchronized Proof confirm(String id, String token) {
        Attempt attempt = requests.get(id);
        if (attempt == null) { throw rejected(); }
        Window window = numbers.get(attempt.phone());
        Instant now = clock.instant();
        if (window.failures >= 5 || !id.equals(window.currentId) || !now.isBefore(attempt.expiresAt())) {
            throw rejected();
        }
        try {
            if (token == null || token.isBlank()) { throw rejected(); }
            Proof proof = gateway.confirm(attempt.session(), token);
            now = clock.instant();
            if (proof == null || !attempt.phone().equals(proof.phone()) || proof.authenticatedAt() == null
                    || proof.authenticatedAt().isBefore(attempt.issuedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                    || proof.authenticatedAt().isAfter(now) || !now.isBefore(attempt.expiresAt())) {
                throw rejected();
            }
            requests.remove(id);
            window.currentId = null;
            return proof;
        } catch (RuntimeException failure) {
            window.failures++;
            // Do not log provider bodies, tokens, codes or raw phone numbers.
            throw rejected();
        }
    }

    private static IllegalStateException rejected() {
        return new IllegalStateException("Phone verification rejected");
    }
}

