package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.domain.IdentityStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcIdentityStore implements IdentityStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIdentityStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, String email, String passwordHash, String tokenHash, Instant now, Instant expiresAt) {
        jdbc.update("INSERT INTO marketplace.identity_user(id,email,state,created_at) VALUES(:id,:email,'PENDING_EMAIL',:now)",
                Map.of("id", id, "email", email, "now", Timestamp.from(now)));
        jdbc.update("INSERT INTO marketplace.user_credential(user_id,password_hash) VALUES(:id,:hash)",
                Map.of("id", id, "hash", passwordHash));
        jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'BUYER')", Map.of("id", id));
        jdbc.update("""
                INSERT INTO marketplace.email_verification_token(token_hash,user_id,created_at,expires_at)
                VALUES(:hash,:id,:now,:expires)
                """, Map.of("hash", tokenHash, "id", id, "now", Timestamp.from(now), "expires", Timestamp.from(expiresAt)));
    }

    @Override
    public boolean consumeAndActivate(String tokenHash, Instant now) {
        var ids = jdbc.query("""
                UPDATE marketplace.email_verification_token SET consumed_at=:now
                WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at>:now
                RETURNING user_id
                """, Map.of("hash", tokenHash, "now", Timestamp.from(now)), (row, index) -> row.getObject(1, UUID.class));
        if (ids.isEmpty()) {
            return false;
        }
        int updated = jdbc.update("""
                UPDATE marketplace.identity_user SET state='ACTIVE',email_verified_at=:now
                WHERE id=:id AND state='PENDING_EMAIL'
                """, Map.of("id", ids.getFirst(), "now", Timestamp.from(now)));
        if (updated != 1) {
            // Throw so the caller's transaction also rolls back the token consumption.
            throw new com.example.marketplace.identity.api.SignupException("INVALID_EMAIL_TOKEN");
        }
        return true;
    }
}
