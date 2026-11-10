package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.SellerMembership;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcSellerMembership implements SellerMembership {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    public JdbcSellerMembership(NamedParameterJdbcTemplate jdbc, AccessPolicy access) { this.jdbc = jdbc; this.access = access; }
    @Override public boolean lockActiveBuyer(UUID member) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        var states = jdbc.queryForList("SELECT state FROM marketplace.identity_user WHERE id=:id FOR NO KEY UPDATE", Map.of("id", member), String.class);
        var roles = jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id", Map.of("id", member), String.class);
        if (states.isEmpty() || !"ACTIVE".equals(states.getFirst()) || !roles.contains("BUYER")) { throw new AccountException("STATE_CONFLICT"); }
        return roles.contains("SELLER");
    }
    @Override public void grant(UUID operator, UUID member) {
        access.require(operator, AccessArea.ACCOUNTS);
        if (operator.equals(member)) { throw new com.example.marketplace.identity.api.AccessRejected(); }
        if (lockActiveBuyer(member)) { throw new AccountException("STATE_CONFLICT"); }
        jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'SELLER')", Map.of("id", member));
    }
    @Override public void lockSeller(UUID member) {
        if (!lockActiveBuyer(member) || jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER' FOR SHARE", Map.of("id",member),String.class).size()!=1) { throw new AccountException("STATE_CONFLICT"); }
    }
}
