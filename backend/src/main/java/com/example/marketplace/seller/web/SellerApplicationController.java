package com.example.marketplace.seller.web;

import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.seller.api.SellerApplications;
import com.example.marketplace.seller.api.SellerApplicationException;
import com.example.marketplace.platform.web.ApiErrorResponse;
import com.example.marketplace.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class SellerApplicationController {
    private final SellerApplications applications;
    public SellerApplicationController(SellerApplications applications) { this.applications = applications; }
    public record Decision(String decision, String reason) { }
    @GetMapping("/api/seller/applications")
    public SellerApplications.Page own(Principal principal, @RequestParam(defaultValue = "0") int page, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); return applications.own(id(principal), page);
    }
    @PostMapping(path = "/api/seller/applications", consumes = "application/json")
    public SellerApplications.Application submit(Principal principal, @RequestBody SellerApplications.Input input, HttpServletResponse response) {
        var result = applications.submit(id(principal), input);
        response.setStatus(201); response.setHeader("Cache-Control", "no-store"); return result;
    }
    @GetMapping("/api/admin/accounts/seller-applications")
    public SellerApplications.Page queue(Principal principal, @RequestParam(defaultValue = "0") int page, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); return applications.reviewQueue(id(principal), page);
    }
    @PostMapping(path = "/api/admin/accounts/seller-applications/{id}/decision", consumes = "application/json")
    public SellerApplications.Application decide(Principal principal, @PathVariable UUID id, @RequestBody Decision decision, HttpServletResponse response) {
        var result = applications.decide(id(principal), id, decision.decision(), decision.reason());
        response.setHeader("Cache-Control", "no-store"); return result;
    }
    @ExceptionHandler({SellerApplicationException.class, AccessRejected.class, AccountException.class})
    public ResponseEntity<ApiErrorResponse> rejected(RuntimeException exception, HttpServletRequest request) {
        int status = exception instanceof SellerApplicationException seller ? seller.status() : exception instanceof AccessRejected ? 403 : 409;
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(ApiErrorResponse.forStatus(status, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
    private UUID id(Principal principal) { return UUID.fromString(principal.getName()); }
}
