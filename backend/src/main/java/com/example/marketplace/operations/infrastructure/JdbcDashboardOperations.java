package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.operations.api.DashboardOperations;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardOperations implements DashboardOperations {
    private final JdbcTemplate jdbc;
    public JdbcDashboardOperations(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                WITH waiting AS (
                 SELECT c.id,c.seller_order_id,a.operator_id FROM marketplace.operations_support_case c
                 LEFT JOIN marketplace.operations_support_assignment a ON a.target_id=c.id
                 JOIN LATERAL (SELECT sender FROM marketplace.operations_case_message m WHERE m.case_id=c.id ORDER BY version DESC LIMIT 1) last ON true
                 WHERE last.sender<>'OPERATOR'
                )
                SELECT 'GLOBAL',NULL::uuid,NULL::date,'CASES_WAITING',count(*) FROM waiting
                UNION ALL SELECT 'ASSIGNEE',operator_id,NULL,'MY_CASES_WAITING',count(*) FROM waiting WHERE operator_id IS NOT NULL GROUP BY operator_id
                UNION ALL SELECT 'GLOBAL',NULL,NULL,'REPORT_'||state,count(*) FROM marketplace.operations_report GROUP BY state
                UNION ALL SELECT 'GLOBAL',NULL,(created_at AT TIME ZONE 'Asia/Seoul')::date,'REPORT_RECEIVED_TOTAL',count(*) FROM marketplace.operations_report GROUP BY 3
                UNION ALL SELECT 'GLOBAL',NULL,(occurred_at AT TIME ZONE 'Asia/Seoul')::date,'REPORT_CLOSURES',count(*) FROM marketplace.operations_report_event WHERE previous_state<>'CLOSED' AND next_state='CLOSED' GROUP BY 3
                UNION ALL SELECT 'GLOBAL',NULL,(occurred_at AT TIME ZONE 'Asia/Seoul')::date,CASE WHEN after_hidden THEN 'MODERATION_HIDE' ELSE 'MODERATION_RESTORE' END,count(*) FROM marketplace.operations_moderation_action GROUP BY 3,4
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
