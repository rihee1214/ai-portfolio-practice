package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccountStateOperations;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAccountStateOperations implements AccountStateOperations {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AccessPolicy access;
    public JdbcAccountStateOperations(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, AccessPolicy access) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.access = access;
    }
    @Override public void suspend(UUID actor, UUID target, String reason) { change(actor, target, reason, "ACTIVE", "SUSPENDED"); }
    @Override public void restore(UUID actor, UUID target, String reason) { change(actor, target, reason, "SUSPENDED", "ACTIVE"); }
    private void change(UUID actor, UUID target, String reason, String before, String after) {
        transaction.executeWithoutResult(status -> {
            access.require(actor, AccessArea.ACCOUNTS);
            if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 300
                    || reason.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) {
                throw new AccountException("INVALID_REASON");
            }
            // Lock the member before credentials/tokens, matching recovery/profile mutation order.
            lockTarget(target, before);
            access.require(actor, AccessArea.ACCOUNTS);
            transition(actor, target, reason.strip(), before, after);
        });
    }
    @Override public void withdraw(UUID member, String password, String confirmation) {
        if (!"WITHDRAW".equals(confirmation) || password == null || password.length() > 256 || password.isEmpty()) {
            throw new AccountException("INVALID_CONFIRMATION");
        }
        transaction.executeWithoutResult(status -> {
            lockTarget(member, "ACTIVE");
            var hashes = jdbc.queryForList("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class);
            if (hashes.isEmpty() || !new ArgonPasswordHasher().matches(password, hashes.getFirst())) {
                throw new AccountException("INVALID_PASSWORD");
            }
            transition(member, member, "SELF_WITHDRAWAL", "ACTIVE", "WITHDRAWN");
            jdbc.update("""
                    UPDATE marketplace.identity_user SET email=:email,display_name='',phone=NULL,email_verified_at=NULL WHERE id=:id
                    """, Map.of("id", member, "email", UUID.randomUUID() + "@withdrawn.invalid"));
            for (String table : java.util.List.of("member_address", "user_credential", "admin_permission", "role_assignment")) {
                jdbc.update("DELETE FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", member));
            }
        });
    }
    private void lockTarget(UUID target, String expected) {
        var states = jdbc.queryForList("SELECT state FROM marketplace.identity_user WHERE id=:id FOR UPDATE", Map.of("id", target), String.class);
        if (states.isEmpty()) { throw new AccountException("ACCOUNT_NOT_FOUND"); }
        if (!expected.equals(states.getFirst()) || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN')", Map.of("id", target), Boolean.class))) {
            throw new AccountException("STATE_CONFLICT");
        }
    }
    private void transition(UUID actor, UUID target, String reason, String before, String after) {
        jdbc.update("UPDATE marketplace.identity_user SET state=:state,auth_version=auth_version+1 WHERE id=:id", Map.of("id", target, "state", after));
        jdbc.update("DELETE FROM marketplace.spring_session WHERE principal_name=:id", Map.of("id", target.toString()));
        jdbc.update("DELETE FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", target));
        jdbc.update("DELETE FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", target));
        jdbc.update("""
                INSERT INTO marketplace.account_state_audit(id,actor_id,target_id,previous_state,next_state,reason)
                VALUES(:id,:actor,:target,:before,:after,:reason)
                """, Map.of("id", UUID.randomUUID(), "actor", actor, "target", target, "before", before, "after", after, "reason", reason));
    }
}
