package com.example.marketplace.identity.web;

import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccountOperations;
import com.example.marketplace.identity.api.AccountOperations.Address;
import com.example.marketplace.identity.api.AccountOperations.AddressInput;
import com.example.marketplace.identity.api.AccountOperations.Profile;
import com.example.marketplace.identity.infrastructure.SessionMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.context.annotation.Profile("db")
@RequestMapping("/api/account")
public class AccountController {
    private final AccountOperations accounts;
    public AccountController(AccountOperations accounts) { this.accounts = accounts; }
    public record ProfileInput(String displayName, String phone) { }

    @GetMapping("/profile")
    public Profile profile(@AuthenticationPrincipal SessionMember member, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return accounts.profile(id(member));
    }

    @PutMapping(path = "/profile", consumes = "application/json")
    public Profile updateProfile(@AuthenticationPrincipal SessionMember member, @RequestBody ProfileInput input,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return accounts.updateProfile(id(member), input.displayName(), input.phone());
    }

    @GetMapping("/addresses")
    public Map<String, List<Address>> addresses(@AuthenticationPrincipal SessionMember member, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("addresses", accounts.addresses(id(member)));
    }

    @PostMapping(path = "/addresses", consumes = "application/json")
    public Address create(@AuthenticationPrincipal SessionMember member, @RequestBody AddressInput input,
            HttpServletResponse response) {
        response.setStatus(201); response.setHeader("Cache-Control", "no-store");
        return accounts.saveAddress(id(member), null, input);
    }

    @PutMapping(path = "/addresses/{addressId}", consumes = "application/json")
    public Address update(@AuthenticationPrincipal SessionMember member, @PathVariable UUID addressId,
            @RequestBody AddressInput input, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return accounts.saveAddress(id(member), addressId, input);
    }

    @DeleteMapping("/addresses/{addressId}")
    public Map<String, String> delete(@AuthenticationPrincipal SessionMember member, @PathVariable UUID addressId,
            HttpServletResponse response) {
        accounts.deleteAddress(id(member), addressId); response.setHeader("Cache-Control", "no-store");
        return Map.of("status", "ADDRESS_DELETED");
    }

    @ExceptionHandler(AccountException.class)
    public void invalid(AccountException exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        int status = switch (exception.getMessage()) {
            case "ACCOUNT_NOT_FOUND", "ADDRESS_NOT_FOUND" -> 404;
            case "ADDRESS_LIMIT" -> 409;
            default -> 400;
        };
        AuthJson.error(request, response, status);
    }

    private UUID id(SessionMember member) { return UUID.fromString(member.getUsername()); }
}
