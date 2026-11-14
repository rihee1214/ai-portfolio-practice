package com.example.marketplace.promotion.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.promotion.infrastructure.JdbcShippingPolicies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public class ShippingPolicyController {
    private final JdbcShippingPolicies policies;
    public ShippingPolicyController(JdbcShippingPolicies policies) { this.policies=policies; }
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    private JdbcShippingPolicies.Actor actor(HttpServletRequest request) {
        var principal=request.getUserPrincipal();var session=request.getSession(false);
        return new JdbcShippingPolicies.Actor(principal==null?null:UUID.fromString(principal.getName()),
                session==null?null:session.getId(),(Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping("/api/site/shipping-policy")
    public Object current() { return policies.current(); }
    @GetMapping("/api/admin/system/shipping-policy")
    public Object history(@RequestParam(defaultValue="9223372036854775807") long before,HttpServletRequest request) {
        return policies.history(actor(request),before);
    }
    @PostMapping("/api/admin/system/shipping-policy")
    public Object change(@RequestBody tools.jackson.databind.JsonNode input,HttpServletRequest request) {
        if(!input.isObject()||input.size()!=5||!input.path("key").isString()||!input.path("reason").isString()) {
            throw new com.example.marketplace.promotion.api.PromotionRejected(400);
        }
        for(String field:java.util.List.of("expectedVersion","baseFee","freeThreshold")) {
            var value=input.path(field);
            if(!value.isIntegralNumber()||!value.canConvertToLong()) { throw new com.example.marketplace.promotion.api.PromotionRejected(400); }
        }
        UUID key;
        try { key=UUID.fromString(input.path("key").asText()); }
        catch(IllegalArgumentException invalid) { throw new com.example.marketplace.promotion.api.PromotionRejected(400); }
        return policies.change(actor(request),new JdbcShippingPolicies.Change(key,input.path("expectedVersion").asLong(),
                input.path("baseFee").asLong(),input.path("freeThreshold").asLong(),input.path("reason").asText()));
    }
}
