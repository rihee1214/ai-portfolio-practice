package com.example.marketplace.platform.infrastructure;

import com.example.marketplace.platform.api.JobInspection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class JdbcJobInspection implements JobInspection {
    private static final String FIELDS="SELECT id,type,status,attempts,scheduled_at,next_run_at,"
            +"CASE WHEN last_error IN ('LEASE_EXPIRED','HANDLER_EXCEPTION','INVALID_PAYLOAD','UNKNOWN_TYPE') THEN last_error "
            +"WHEN last_error IS NOT NULL THEN 'PROCESSING_ERROR' END AS error FROM marketplace.job ";
    private static final RowMapper<Summary> MAP=(r,n)->new Summary(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
            r.getInt(4),r.getTimestamp(5).toInstant(),r.getTimestamp(6).toInstant(),r.getString(7));
    private final JdbcTemplate db;
    public JdbcJobInspection(NamedParameterJdbcTemplate jdbc) { db=jdbc.getJdbcTemplate(); }
    @Override public List<Summary> list(String status,UUID after) {
        return db.query(FIELDS+"WHERE status=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT 40",MAP,status,after,after);
    }
    @Override public Summary find(UUID id) { return db.query(FIELDS+"WHERE id=?",MAP,id).stream().findFirst().orElse(null); }
}
