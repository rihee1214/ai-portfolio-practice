package com.example.marketplace.identity.web;

import com.example.marketplace.identity.api.AccountStateOperations;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.infrastructure.SessionMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class AccountStateController {
    private final AccountStateOperations states;
    public AccountStateController(AccountStateOperations states) { this.states = states; }
    public record Reason(String reason) { }
    public record Withdrawal(String password, String confirmation) { }
    @PostMapping(path = "/api/admin/accounts/{id}/suspend", consumes = "application/json")
    public Map<String, String> suspend(@AuthenticationPrincipal SessionMember member, @PathVariable UUID id,
            @RequestBody Reason input, HttpServletResponse response) {
        states.suspend(UUID.fromString(member.getUsername()), id, input.reason());
        return result(response, "SUSPENDED");
    }
    @PostMapping(path = "/api/admin/accounts/{id}/restore", consumes = "application/json")
    public Map<String, String> restore(@AuthenticationPrincipal SessionMember member, @PathVariable UUID id,
            @RequestBody Reason input, HttpServletResponse response) {
        states.restore(UUID.fromString(member.getUsername()), id, input.reason());
        return result(response, "ACTIVE");
    }
    @PostMapping(path = "/api/account/withdraw", consumes = "application/json")
    public Map<String, String> withdraw(@AuthenticationPrincipal SessionMember member, @RequestBody Withdrawal input,
            HttpServletRequest request, HttpServletResponse response) {
        states.withdraw(UUID.fromString(member.getUsername()), input.password(), input.confirmation());
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) { session.invalidate(); }
        return result(response, "WITHDRAWN");
    }
    private Map<String, String> result(HttpServletResponse response, String state) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("status", state);
    }
    @ExceptionHandler(AccountException.class)
    public void rejected(AccountException error, HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthJson.error(request, response, switch (error.getMessage()) {
            case "ACCOUNT_NOT_FOUND" -> 404;
            case "STATE_CONFLICT" -> 409;
            default -> 400;
        });
    }
    @ExceptionHandler(AccessRejected.class)
    public void denied(HttpServletRequest request, HttpServletResponse response) throws IOException { AuthJson.error(request, response, 403); }
}
