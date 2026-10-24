package com.example.marketplace.identity.web;

import com.example.marketplace.identity.infrastructure.SessionMember;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class AuthController {
    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(@AuthenticationPrincipal SessionMember member, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("id", member.getUsername(), "roles", member.roles());
    }
}
