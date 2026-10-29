package com.example.marketplace.identity.web;

import com.example.marketplace.identity.infrastructure.LoginMembers;
import com.example.marketplace.identity.infrastructure.SessionMember;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class CurrentMemberFilter extends OncePerRequestFilter {
    private final LoginMembers members;
    CurrentMemberFilter(LoginMembers members) { this.members = members; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionMember saved) {
            SessionMember current;
            try {
                current = members.current(saved.getUsername());
            } catch (DataAccessException exception) {
                AuthJson.error(request, response, 503);
                return;
            }
            if (current == null || !current.isEnabled() || current.authVersion() != saved.authVersion()) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) { session.invalidate(); }
                AuthJson.error(request, response, 401);
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    current, null, current.getAuthorities()));
            SecurityContextHolder.setContext(context);
            request.setAttribute(com.example.marketplace.identity.api.PurchaseSessions.AUTH_VERSION_ATTRIBUTE,current.authVersion());
        }
        chain.doFilter(request, response);
    }
}
