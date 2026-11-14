package com.example.marketplace.reporting.web;

import com.example.marketplace.identity.api.PurchaseSessions;
import com.example.marketplace.reporting.api.Dashboards;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Profile("db")
public class DashboardController {
    private final Dashboards dashboards;
    @org.springframework.web.bind.annotation.ExceptionHandler(com.example.marketplace.order.api.OrderRejected.class)
    public org.springframework.http.ResponseEntity<?> rejected(com.example.marketplace.order.api.OrderRejected rejected,HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(rejected.status()).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(com.example.marketplace.platform.web.ApiErrorResponse.forStatus(rejected.status(),
                        (String)request.getAttribute(com.example.marketplace.platform.web.RequestIdFilter.ATTRIBUTE)));
    }
    public DashboardController(Dashboards dashboards) { this.dashboards=dashboards; }
    @ModelAttribute void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    private Dashboards.Actor actor(HttpServletRequest request) {
        var principal=request.getUserPrincipal();var session=request.getSession(false);
        return new Dashboards.Actor(principal==null?null:UUID.fromString(principal.getName()),
                session==null?null:session.getId(),(Long)request.getAttribute(PurchaseSessions.AUTH_VERSION_ATTRIBUTE));
    }
    @GetMapping("/api/seller/dashboard")
    public Dashboards.View seller(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,HttpServletRequest request) {
        return dashboards.read(actor(request),"seller",from,to);
    }
    @GetMapping("/api/admin/{area:support|content|accounts|settlements|system}/dashboard")
    public Dashboards.View operations(@PathVariable String area,@RequestParam(required=false) LocalDate from,
                                     @RequestParam(required=false) LocalDate to,HttpServletRequest request) {
        return dashboards.read(actor(request),area,from,to);
    }
    public record Refresh(UUID key,String reason) { }
    @PostMapping("/api/admin/system/dashboard/refresh")
    public long refresh(@RequestBody Refresh command,HttpServletRequest request) {
        return dashboards.refresh(actor(request),command.key(),command.reason());
    }
}
