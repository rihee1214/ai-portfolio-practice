package com.example.marketplace.identity.web;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessRejected;
import com.example.marketplace.identity.infrastructure.SessionMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class AccessController {
    private final AccessPolicy access;
    public AccessController(AccessPolicy access) { this.access = access; }
    @GetMapping("/api/seller/access")
    public Map<String, String> seller(@AuthenticationPrincipal SessionMember member, HttpServletResponse response) {
        return checked(member, AccessArea.SELLER, response);
    }
    @GetMapping("/api/admin/access")
    public Map<String, String> admin(@AuthenticationPrincipal SessionMember member, HttpServletResponse response) {
        return checked(member, AccessArea.ADMIN, response);
    }
    @GetMapping("/api/admin/{area}/access")
    public Map<String, String> operation(@AuthenticationPrincipal SessionMember member, @PathVariable String area,
            HttpServletResponse response) {
        return checked(member, AccessArea.forAdminPath("/api/admin/" + area + "/access"), response);
    }
    private Map<String, String> checked(SessionMember member, AccessArea area, HttpServletResponse response) {
        access.require(UUID.fromString(member.getUsername()), area);
        response.setHeader("Cache-Control", "no-store");
        return Map.of("access", area.name());
    }
    @ExceptionHandler(AccessRejected.class)
    public void denied(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthJson.error(request, response, 403);
    }
}
