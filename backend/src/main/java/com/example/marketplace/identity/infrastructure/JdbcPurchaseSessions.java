package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.PurchaseSessions;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
public final class JdbcPurchaseSessions implements PurchaseSessions,com.example.marketplace.identity.api.HistorySessions {
    private final NamedParameterJdbcTemplate jdbc;
    private final java.time.Clock clock;
    @org.springframework.beans.factory.annotation.Autowired public JdbcPurchaseSessions(NamedParameterJdbcTemplate jdbc) { this(jdbc,java.time.Clock.systemUTC()); }
    public JdbcPurchaseSessions(NamedParameterJdbcTemplate jdbc,java.time.Clock clock) { this.jdbc=jdbc;this.clock=clock; }
    @Override public boolean lockReadable(String sessionId,UUID memberId,Long version) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        if(memberId==null||version==null||sessionId==null) { return false; }
        var rows=jdbc.queryForList("SELECT state,auth_version FROM marketplace.identity_user WHERE id=:id FOR SHARE",java.util.Map.of("id",memberId));
        return rows.size()==1&&"ACTIVE".equals(rows.getFirst().get("state"))&&((Number)rows.getFirst().get("auth_version")).longValue()==version&&lockCurrent(sessionId,memberId,version);
    }
    @Override public boolean lockCurrent(String sessionId,UUID memberId,Long authVersion) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        var rows=jdbc.queryForList("SELECT principal_name,expiry_time FROM marketplace.spring_session WHERE session_id=:id FOR SHARE",new MapSqlParameterSource("id",sessionId));
        if(rows.size()!=1) { return false; }
        var row=rows.getFirst();
        if(!java.util.Objects.equals(row.get("principal_name"),memberId==null?null:memberId.toString())
                ||((Number)row.get("expiry_time")).longValue()<=clock.millis()) { return false; }
        return memberId==null||authVersion!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT auth_version=:version FROM marketplace.identity_user WHERE id=:id",new MapSqlParameterSource("version",authVersion).addValue("id",memberId),Boolean.class));
    }
}
