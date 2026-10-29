package com.example.marketplace.payment.web;
import com.example.marketplace.payment.api.PaymentQueries;
import com.example.marketplace.order.api.OrderQueries;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public class PaymentQueryController {
    private final PaymentQueries queries;
    public PaymentQueryController(PaymentQueries queries) { this.queries=queries; }
    @GetMapping("/api/payments/{number}/history") public ResponseEntity<?> history(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token) {
        var a=new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(queries.history(number,a));
    }
}
