package com.example.marketplace.claim.web;

import com.example.marketplace.claim.infrastructure.SupportRefunds;
import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
@RequestMapping("/api/admin/support/cases/{caseId}/confirmed-refunds")
public class SupportRefundController {
    private final SupportRefunds refunds;
    public SupportRefundController(SupportRefunds refunds) { this.refunds=refunds; }
    private OrderQueries.Authority actor(HttpServletRequest request) {
        var session=request.getSession(false);var principal=request.getUserPrincipal();
        return new OrderQueries.Authority(session==null?null:session.getId(),principal==null?null:UUID.fromString(principal.getName()),
                (Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE),null);
    }
    @ModelAttribute void privacy(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping public List<SupportRefunds.Entry> list(@PathVariable UUID caseId,HttpServletRequest request) {
        return refunds.list(caseId,actor(request));
    }
    @PostMapping("/{action}") public List<SupportRefunds.Entry> apply(@PathVariable UUID caseId,@PathVariable SupportRefunds.Action action,
            @RequestBody Map<String,Object> body,HttpServletRequest request) {
        var keys=new java.util.HashSet<>(Set.of("key","caseVersion","item","ordinal"));
        switch(action) {
            case COLLECT -> keys.add("reference");
            case INSPECT -> keys.addAll(Set.of("previous","reference","responsibility","reason"));
            case APPROVE, RETRY_FAILED -> keys.addAll(Set.of("previous","reason"));
            case EXECUTE -> keys.add("previous");
        }
        if(!body.keySet().equals(keys)) { throw new OrderRejected(400); }
        SupportRefunds.Command cmd;
        try {
            if(!(body.get("ordinal") instanceof Number)||!(body.get("caseVersion") instanceof Number)) { throw new IllegalArgumentException(); }
            cmd=new SupportRefunds.Command(UUID.fromString((String)body.get("key")),Long.parseLong(body.get("caseVersion").toString()),
                    UUID.fromString((String)body.get("item")),Integer.parseInt(body.get("ordinal").toString()),
                    keys.contains("previous")?UUID.fromString((String)body.get("previous")):null,
                    (String)body.get("reference"),(String)body.get("responsibility"),(String)body.get("reason"));
        }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid) { throw new OrderRejected(400); }
        return refunds.command(caseId,action,cmd,actor(request));
    }
}
