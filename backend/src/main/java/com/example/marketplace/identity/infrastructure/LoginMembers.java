package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.domain.SignupRules;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;

public final class LoginMembers implements UserDetailsService {
    private final NamedParameterJdbcTemplate jdbc;

    public LoginMembers(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public SessionMember loadUserByUsername(String input) {
        String email;
        try {
            email = SignupRules.email(input);
        } catch (IllegalArgumentException exception) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        var users = jdbc.query("""
                SELECT u.id, u.auth_version, u.state, c.password_hash
                FROM marketplace.identity_user u JOIN marketplace.user_credential c ON c.user_id = u.id
                WHERE u.email = :email
                """, Map.of("email", email), (rs, row) -> new SessionMember(rs.getString("id"),
                rs.getLong("auth_version"), roles(rs.getString("id")), rs.getString("password_hash"),
                "ACTIVE".equals(rs.getString("state"))));
        if (users.isEmpty()) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return users.getFirst();
    }

    public SessionMember current(String id) {
        var users = jdbc.query("""
                SELECT id, auth_version, state FROM marketplace.identity_user WHERE id = :id
                """, Map.of("id", UUID.fromString(id)), (rs, row) -> new SessionMember(id,
                rs.getLong("auth_version"), roles(id), null, "ACTIVE".equals(rs.getString("state"))));
        return users.isEmpty() ? null : users.getFirst();
    }

    private java.util.List<String> roles(String id) {
        return jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id = :id ORDER BY role",
                Map.of("id", UUID.fromString(id)), String.class);
    }
}
