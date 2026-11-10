package com.example.marketplace.identity.web;

import com.example.marketplace.identity.api.AccountStateOperations;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.infrastructure.SessionMember;
import com.example.marketplace.identity.infrastructure.ManagedAccounts;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class AccountStateController {
    private final AccountStateOperations states;
    private final ManagedAccounts directory;
    public AccountStateController(AccountStateOperations states,ManagedAccounts directory) { this.states = states;this.directory=directory; }
    public record Reason(String reason,Long expectedVersion) { }
    public record Withdrawal(String password, String confirmation) { }
    private ManagedAccounts.Operator operator(SessionMember member,HttpServletRequest request){
        var session=request.getSession(false);
        return new ManagedAccounts.Operator(UUID.fromString(member.getUsername()),session==null?null:session.getId(),member.authVersion());
    }
    @PostMapping(path="/api/admin/accounts/search",consumes="application/json")
    public ManagedAccounts.Page search(@AuthenticationPrincipal SessionMember actor,@RequestBody ManagedAccounts.Search query,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return directory.search(operator(actor,request),query);
    }
    @GetMapping("/api/admin/accounts/members/{id}")
    public ManagedAccounts.Detail detail(@AuthenticationPrincipal SessionMember actor,@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        return directory.detail(operator(actor,request),id);
    }
    @PostMapping(path = "/api/admin/accounts/{id}/suspend", consumes = "application/json")
    public Map<String, String> suspend(@AuthenticationPrincipal SessionMember member, @PathVariable UUID id,
            @RequestBody Reason input, HttpServletRequest request,HttpServletResponse response) {
        directory.change(operator(member,request),id,input.reason(),input.expectedVersion(),true);
        return result(response, "SUSPENDED");
    }
    @PostMapping(path = "/api/admin/accounts/{id}/restore", consumes = "application/json")
    public Map<String, String> restore(@AuthenticationPrincipal SessionMember member, @PathVariable UUID id,
            @RequestBody Reason input, HttpServletRequest request,HttpServletResponse response) {
        directory.change(operator(member,request),id,input.reason(),input.expectedVersion(),false);
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
