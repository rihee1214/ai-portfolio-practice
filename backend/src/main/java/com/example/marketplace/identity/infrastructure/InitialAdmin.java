package com.example.marketplace.identity.infrastructure;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Trusted local operator only. No public HTTP mapping. All changes commit together. */
public final class InitialAdmin {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public InitialAdmin(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    public void initialize(UUID member, String expectedDatabase, String confirmation) {
        if (member == null || expectedDatabase == null || expectedDatabase.isBlank()
                || !"INITIAL_ADMIN".equals(confirmation)) { throw new IllegalArgumentException("Explicit bootstrap inputs required"); }
        transaction.executeWithoutResult(status -> {
            if (!expectedDatabase.equals(jdbc.queryForObject("SELECT current_database()", Map.of(), String.class))) {
                throw new IllegalStateException("Bootstrap target mismatch");
            }
            var done = jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM marketplace.admin_bootstrap WHERE id=1 FOR UPDATE",
                    Map.of(), Boolean.class);
            if (Boolean.TRUE.equals(done) || jdbc.queryForObject(
                    "SELECT count(*) FROM marketplace.role_assignment WHERE role='ADMIN'", Map.of(), Integer.class) != 0) {
                throw new IllegalStateException("Bootstrap unavailable");
            }
            var eligible = jdbc.query("""
                    SELECT u.id FROM marketplace.identity_user u WHERE u.id=:id AND u.state='ACTIVE'
                    AND u.email_verified_at IS NOT NULL
                    AND EXISTS (SELECT 1 FROM marketplace.role_assignment r WHERE r.user_id=u.id AND r.role='BUYER')
                    FOR UPDATE
                    """, Map.of("id", member), (row, index) -> row.getObject(1, UUID.class));
            if (eligible.isEmpty()) { throw new IllegalStateException("Bootstrap target ineligible"); }
            jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'ADMIN')", Map.of("id", member));
            for (String permission : java.util.List.of("ACCOUNTS","CONTENT","SUPPORT","SETTLEMENTS","SYSTEM")) {
                jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,:permission)",
                        Map.of("id", member, "permission", permission));
            }
            jdbc.update("UPDATE marketplace.identity_user SET auth_version=auth_version+1 WHERE id=:id", Map.of("id", member));
            jdbc.update("DELETE FROM marketplace.spring_session WHERE principal_name=:id", Map.of("id", member.toString()));
            jdbc.update("""
                    INSERT INTO marketplace.authorization_audit(id,target_id,action,source,detail)
                    VALUES(:audit,:id,'INITIAL_ADMIN_GRANTED','LOCAL_BOOTSTRAP', 'ADMIN;ACCOUNTS,CONTENT,SUPPORT,SETTLEMENTS,SYSTEM')
                    """, Map.of("audit", UUID.randomUUID(), "id", member));
            jdbc.update("UPDATE marketplace.admin_bootstrap SET user_id=:id,completed_at=clock_timestamp() WHERE id=1",
                    Map.of("id", member));
        });
    }
}
