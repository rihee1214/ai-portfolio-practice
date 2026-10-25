package com.example.marketplace.guest.web;
import com.example.marketplace.guest.api.GuestInput;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.guest.infrastructure.JdbcGuestPurchases;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class GuestController {
    public record Verification(String phone, String purpose, String code, String proof) { }
    private final JdbcGuestPurchases purchases;
    private final Environment env;
    public GuestController(JdbcGuestPurchases purchases, Environment env) { this.purchases = purchases; this.env = env; }
    @PostMapping("/api/guest/checkout") public ResponseEntity<?> create(HttpSession session) {
        String token = purchases.create(session.getId());
        return response(purchases.status(session.getId(), token));
    }
    @GetMapping("/api/guest/checkout") public ResponseEntity<?> get(HttpSession session) {
        return response(purchases.status(session.getId(), token(session)));
    }
    @PostMapping("/api/guest/verifications") public ResponseEntity<?> issue(HttpSession session, HttpServletRequest request, @RequestBody GuestInput input) {
        if (input == null) { throw new GuestRejected(400); }
        return response(purchases.issue(session.getId(), token(session), input, request.getRemoteAddr()));
    }
    @PostMapping("/api/guest/verifications/{id}/exchange") public ResponseEntity<?> exchange(HttpSession session, @PathVariable UUID id, @RequestBody Verification input) {
        if (input == null || input.code() == null || input.code().length() > 6) { throw new GuestRejected(400); }
        String custom = purchases.exchange(session.getId(), token(session), id, input.phone(), input.purpose(), input.code());
        return response(Map.of("customToken", custom, "host", env.getRequiredProperty("FIREBASE_AUTH_EMULATOR_HOST"), "project", env.getRequiredProperty("GCLOUD_PROJECT")));
    }
    @PostMapping("/api/guest/verifications/{id}/verify") public ResponseEntity<?> verify(HttpSession session, @PathVariable UUID id, @RequestBody Verification input) {
        if (input == null || input.proof() == null || input.proof().length() > 16384) { throw new GuestRejected(400); }
        return response(purchases.verify(session.getId(), token(session), id, input.phone(), input.purpose(), input.proof()));
    }
    private String token(HttpSession session) { return purchases.credential(session.getId()); }
    private ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
