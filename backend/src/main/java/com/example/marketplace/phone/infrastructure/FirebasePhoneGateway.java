package com.example.marketplace.phone.infrastructure;

import com.example.marketplace.phone.application.PhoneProvider;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** Explicit local-only adapter; intentionally not registered as a Spring bean or public endpoint. */
public final class FirebasePhoneGateway implements PhoneProvider.Gateway, AutoCloseable {
    private final FirebaseApp app;
    private final FirebaseAuth auth;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();
    private final Map<String, Exchanged> exchanged = new java.util.concurrent.ConcurrentHashMap<>();
    private record Exchanged(String uid, String phone, Instant authenticatedAt, Instant expiresAt) { }

    public FirebasePhoneGateway(String mode, String project, String host) {
        if (!"local-test".equals(mode) || project == null || !project.matches("demo-[a-z0-9-]+")
                || host == null || !host.matches("127\\.0\\.0\\.1:[0-9]{1,5}")
                || !host.equals(System.getenv("FIREBASE_AUTH_EMULATOR_HOST"))) {
            throw new IllegalArgumentException("Only an explicitly configured local demo emulator is allowed");
        }
        URI address = URI.create("http://" + host);
        if (address.getPort() < 1 || address.getPort() > 65535) { throw new IllegalArgumentException("Invalid emulator port"); }
        endpoint = address;
        // Synthetic emulator credential, never Application Default Credentials or a real project secret.
        var credentials = GoogleCredentials.create(new AccessToken("owner", Date.from(Instant.now().plusSeconds(3600))));
        app = FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(project).setCredentials(credentials).build(),
                "phone-local-" + UUID.randomUUID());
        auth = FirebaseAuth.getInstance(app);
    }

    @Override
    public String issue(String phone) {
        return post("sendVerificationCode", Map.of("phoneNumber", PhoneProvider.normalize(phone))).get("sessionInfo").getAsString();
    }

    @Override
    public String exchange(String session, String code) {
        try {
            exchanged.entrySet().removeIf(entry -> !Instant.now().isBefore(entry.getValue().expiresAt()));
            if (exchanged.containsKey(session)) { throw new IllegalStateException("Already exchanged"); }
            // Firebase consumes the OTP exactly once, on the server, inside the attempt limit.
            String serverToken = post("signInWithPhoneNumber", Map.of("sessionInfo", session, "code", code))
                    .get("idToken").getAsString();
            FirebaseToken server = auth.verifyIdToken(serverToken);
            var claims = (Map<?, ?>) server.getClaims().get("firebase");
            if (!"phone".equals(claims.get("sign_in_provider"))) { throw new IllegalStateException("Rejected"); }
            String phone = PhoneProvider.normalize((String) server.getClaims().get("phone_number"));
            Instant time = Instant.ofEpochSecond(((Number) server.getClaims().get("auth_time")).longValue());
            String customToken = auth.createCustomToken(server.getUid());
            exchanged.put(session, new Exchanged(server.getUid(), phone, time, Instant.now().plusSeconds(300)));
            return customToken;
        } catch (Exception rejected) { throw new IllegalStateException("Phone code rejected"); }
    }

    @Override
    public PhoneProvider.Proof confirm(String session, String token) {
        try {
            Exchanged expected = exchanged.get(session);
            if (expected == null || !Instant.now().isBefore(expected.expiresAt())) { throw new IllegalStateException("Rejected"); }
            FirebaseToken browser = auth.verifyIdToken(token);
            String phone = (String) browser.getClaims().get("phone_number");
            Number expiry = (Number) browser.getClaims().get("exp");
            Object firebase = browser.getClaims().get("firebase");
            if (!(firebase instanceof Map<?, ?> claims) || !"custom".equals(claims.get("sign_in_provider"))
                    || !browser.getUid().equals(expected.uid()) || phone == null
                    || !phone.equals(expected.phone()) || expiry == null
                    || !Instant.now().isBefore(Instant.ofEpochSecond(expiry.longValue()))) {
                throw new IllegalStateException("Rejected");
            }
            exchanged.remove(session);
            return new PhoneProvider.Proof(expected.phone(), expected.authenticatedAt());
        } catch (Exception rejected) {
            throw new IllegalStateException("Phone proof rejected");
        }
    }

    private JsonObject post(String action, Map<String, String> body) {
        try {
            URI uri = endpoint.resolve("/identitytoolkit.googleapis.com/v1/accounts:" + action + "?key=local-test-only");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { throw new IllegalStateException("Rejected"); }
            return gson.fromJson(response.body(), JsonObject.class);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Phone provider unavailable");
        } catch (Exception failure) {
            throw new IllegalStateException("Phone provider unavailable");
        }
    }

    @Override
    public void close() {
        exchanged.clear();
        app.delete();
        http.close();
    }
}
