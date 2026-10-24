package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.domain.PasswordRecoveryStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcPasswordRecoveryStore implements PasswordRecoveryStore {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcPasswordRecoveryStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public UUID lockEligibleMember(String email) {
        var ids = jdbc.query("""
                SELECT id FROM marketplace.identity_user
                WHERE email=:email AND state='ACTIVE' AND email_verified_at IS NOT NULL FOR UPDATE
                """, Map.of("email", email), (row, index) -> row.getObject(1, UUID.class));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    @Override public void replaceToken(UUID member, String hash, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO marketplace.password_reset_token(user_id,token_hash,created_at,expires_at)
                VALUES(:id,:hash,:now,:expires)
                ON CONFLICT(user_id) DO UPDATE SET token_hash=:hash,created_at=:now,expires_at=:expires,consumed_at=NULL
                """, Map.of("id", member, "hash", hash, "now", Timestamp.from(createdAt), "expires", Timestamp.from(expiresAt)));
    }

    @Override public boolean consume(String tokenHash, String passwordHash, Supplier<Instant> currentTime) {
        // Resolve without a token lock, then take the member lock first: same lock order as token replacement.
        var members = jdbc.query("SELECT user_id FROM marketplace.password_reset_token WHERE token_hash=:hash",
                Map.of("hash", tokenHash), (row, index) -> row.getObject(1, UUID.class));
        if (members.isEmpty()) { return false; }
        UUID id = members.getFirst();
        if (jdbc.query("""
                SELECT id FROM marketplace.identity_user WHERE id=:id AND state='ACTIVE'
                AND email_verified_at IS NOT NULL FOR UPDATE
                """, Map.of("id", id), (row, index) -> row.getObject(1)).isEmpty()) { return false; }
        // Lock waits may cross the expiry boundary; sample time only after the member lock is held.
        Instant now = currentTime.get();
        int count = jdbc.update("""
                UPDATE marketplace.password_reset_token SET consumed_at=:now WHERE user_id=:id
                AND token_hash=:hash AND consumed_at IS NULL AND expires_at>:now
                """, Map.of("id", id, "hash", tokenHash, "now", Timestamp.from(now)));
        if (count != 1) { return false; }
        jdbc.update("UPDATE marketplace.user_credential SET password_hash=:password WHERE user_id=:id",
                Map.of("id", id, "password", passwordHash));
        jdbc.update("UPDATE marketplace.identity_user SET auth_version=auth_version+1 WHERE id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM marketplace.spring_session WHERE principal_name=:principal", Map.of("principal", id.toString()));
        return true;
    }
}
