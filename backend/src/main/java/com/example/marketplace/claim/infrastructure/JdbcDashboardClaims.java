package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.DashboardClaims;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardClaims implements DashboardClaims {
    private final JdbcTemplate jdbc;
    public JdbcDashboardClaims(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                WITH requests AS (
                 SELECT id,seller_order_id,'CANCEL' AS kind FROM marketplace.claim WHERE state NOT IN ('COMPLETED','WITHDRAWN')
                 UNION ALL SELECT id,seller_order_id,'RETURN' FROM marketplace.claim_return_request WHERE state NOT IN ('COMPLETED','WITHDRAWN','REJECTED')
                 UNION ALL SELECT id,seller_order_id,'EXCHANGE' FROM marketplace.claim_exchange_request WHERE state NOT IN ('COMPLETED','WITHDRAWN','REJECTED','REFUNDED')
                ), units AS (
                 SELECT claim_id AS id,'CANCEL' AS kind FROM marketplace.claim_unit
                 UNION ALL SELECT return_id,'RETURN' FROM marketplace.claim_return_unit
                 UNION ALL SELECT exchange_id,'EXCHANGE' FROM marketplace.claim_exchange_unit
                )
                SELECT 'SELLER_ORDER',seller_order_id,NULL::date,'OPEN_'||kind,count(*) FROM requests GROUP BY seller_order_id,kind
                UNION ALL SELECT 'SELLER_ORDER',r.seller_order_id,NULL,'OPEN_'||r.kind||'_UNITS',count(*)
                 FROM requests r JOIN units u ON r.id=u.id AND r.kind=u.kind GROUP BY r.seller_order_id,r.kind
                UNION ALL SELECT 'SELLER_ORDER',a.seller_order_id,(f.applied_at AT TIME ZONE 'Asia/Seoul')::date,
                  'RECOVERY_FEE_DEDUCTED',sum(f.deducted::numeric) FROM marketplace.claim_fee_application f
                  JOIN marketplace.claim_fee_assessment a ON a.id=f.assessment_id GROUP BY 2,3
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
