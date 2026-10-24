package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAccessPolicy implements AccessPolicy {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAccessPolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean allowed(UUID member, AccessArea area) {
        if (member == null || area == null) { return false; }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM marketplace.identity_user u
                JOIN marketplace.role_assignment r ON r.user_id=u.id AND r.role=:role
                WHERE u.id=:id AND u.state='ACTIVE'
                AND (NOT :required OR EXISTS (SELECT 1 FROM marketplace.admin_permission p
                    WHERE p.user_id=u.id AND p.permission=:permission)))
                """, Map.of("id", member, "role", area.role(), "required", area.permissionRequired(),
                    "permission", area.name()), Boolean.class));
    }
}
