package com.example.marketplace.guest.web;
import com.example.marketplace.guest.api.GuestPassword;
import com.example.marketplace.guest.api.GuestRejected;
import com.example.marketplace.guest.infrastructure.JdbcGuestAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class GuestAccessController {
    public record PasswordInput(UUID attemptId, String password, String confirmation) {
        @Override public String toString() { return "PasswordInput[REDACTED]"; }
    }
    public record Login(String orderNumber, String phone, String password) {
        @Override public String toString() { return "GuestLogin[REDACTED]"; }
    }
    private final JdbcGuestAccess access;
    private final boolean secure;
    public GuestAccessController(JdbcGuestAccess access,@Value("${marketplace.session.secure:true}") boolean secure) { this.access=access; this.secure=secure; }
    @PostMapping("/api/guest/checkout/password") public ResponseEntity<?> prepare(HttpSession session,@RequestBody PasswordInput input) {
        if(input==null) { throw new GuestRejected(400); }
        access.prepare(session.getId(),input.attemptId(),new GuestPassword(input.password(),input.confirmation()));
        return response(Map.of("status","PASSWORD_READY"));
    }
    @PostMapping("/api/guest/access/login") public ResponseEntity<?> login(@RequestBody Login input,HttpServletRequest request,
            @CookieValue(name="GUEST_ACCESS",required=false) String previous) {
        if(input==null) { throw new GuestRejected(401); }
        String token=access.login(input.orderNumber(),input.phone(),input.password(),request.getRemoteAddr(),previous);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Set-Cookie",cookie(token,43200)).body(Map.of("status","AUTHENTICATED"));
    }
    @PostMapping("/api/guest/access/logout") public ResponseEntity<?> logout(@CookieValue(name="GUEST_ACCESS",required=false) String token) {
        access.logout(token);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Set-Cookie",cookie("",0)).body(Map.of("status","LOGGED_OUT"));
    }
    @GetMapping("/api/guest/orders") public ResponseEntity<?> orders(@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(Map.of("orders",access.orders(token))); }
    @GetMapping("/api/guest/orders/{number}") public ResponseEntity<?> order(@CookieValue(name="GUEST_ACCESS",required=false) String token,@PathVariable String number) { return response(access.order(token,number)); }
    private String cookie(String token,long seconds) { return ResponseCookie.from("GUEST_ACCESS",token).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(seconds).build().toString(); }
    private ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
}
