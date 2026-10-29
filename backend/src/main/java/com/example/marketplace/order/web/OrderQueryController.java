package com.example.marketplace.order.web;
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
import org.springframework.web.bind.annotation.RequestParam;
@RestController @Profile("db")
public class OrderQueryController {
    private final OrderQueries queries;
    public OrderQueryController(OrderQueries queries) { this.queries=queries; }
    private OrderQueries.Authority authority(Principal p,HttpSession s,HttpServletRequest r,String token) { return new OrderQueries.Authority(s.getId(),p==null?null:UUID.fromString(p.getName()),(Long)r.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),token); }
    private ResponseEntity<?> response(Object body) { return ResponseEntity.ok().header("Cache-Control","no-store").body(body); }
    @GetMapping("/api/account/orders") public ResponseEntity<?> member(Principal p,HttpSession s,HttpServletRequest r,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor) { return response(queries.memberOrders(authority(p,s,r,null),size,cursor)); }
    @GetMapping("/api/guest/orders") public ResponseEntity<?> guest(@CookieValue(name="GUEST_ACCESS",required=false) String token,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor) { return response(queries.guestOrders(token,size,cursor)); }
    @GetMapping("/api/orders/{number}") public ResponseEntity<?> detail(@PathVariable String number,Principal p,HttpSession s,HttpServletRequest r,@CookieValue(name="GUEST_ACCESS",required=false) String token) { return response(queries.detail(number,authority(p,s,r,token))); }
    @GetMapping("/api/seller/orders/{id}") public ResponseEntity<?> seller(@PathVariable UUID id,Principal p,HttpSession s,HttpServletRequest r) { return response(queries.sellerOrder(id,authority(p,s,r,null))); }
    @GetMapping("/api/seller/orders") public ResponseEntity<?> sellers(Principal p,HttpSession s,HttpServletRequest r,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String cursor) { return response(queries.sellerOrders(authority(p,s,r,null),size,cursor)); }
}
