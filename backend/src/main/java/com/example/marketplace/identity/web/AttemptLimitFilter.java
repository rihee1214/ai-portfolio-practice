package com.example.marketplace.identity.web;

import com.example.marketplace.identity.application.TokenSecrets;
import com.example.marketplace.identity.domain.SignupRules;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class AttemptLimitFilter extends OncePerRequestFilter {
    private final AttemptWindow attempts = new AttemptWindow(Clock.systemUTC());
    private static final Set<String> PATHS = Set.of("/api/auth/login", "/api/auth/signup", "/api/auth/verify-email");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && PATHS.contains(request.getRequestURI())) {
            boolean allowed = attempts.allow("ip:" + request.getRemoteAddr(), 100);
            if (allowed && "/api/auth/login".equals(request.getRequestURI())) {
                String email;
                try { email = SignupRules.email(request.getParameter("email")); }
                catch (IllegalArgumentException exception) { email = "invalid"; }
                allowed = attempts.allow("login:" + request.getRemoteAddr() + ":" + TokenSecrets.hash(email), 5);
            }
            if (!allowed) {
                response.setHeader("Retry-After", "60");
                AuthJson.error(request, response, 429);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
