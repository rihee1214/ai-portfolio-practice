package com.example.marketplace.order.web;

import com.example.marketplace.order.infrastructure.OrderCreationService;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public class OrderController {
    private final OrderCreationService creation;
    public OrderController(OrderCreationService creation) { this.creation=creation; }
    @PostMapping("/api/orders") public ResponseEntity<?> create(Principal principal,HttpSession session,jakarta.servlet.http.HttpServletRequest request,
            @CookieValue(name="GUEST_ACCESS",required=false) String guestToken,@RequestBody OrderCreationService.Input input) {
        var order=creation.createForSession(session.getId(),principal==null?null:UUID.fromString(principal.getName()),(Long)request.getAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE),guestToken,input);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("id",order.id(),"orderNumber",order.orderNumber(),"state",order.state(),"total",Long.toString(order.quote().total()),"expiresAt",order.expiresAt(),"stores",order.sellers().size()));
    }
}
