package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.SettlementClaims;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcSettlementClaims implements SettlementClaims {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcSettlementClaims(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public View lock(UUID seller) {
        var p=Map.of("seller",seller);
        boolean pending=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment WHERE seller_order_id=:seller AND (state IN('RESERVED','BOUND') OR state='APPLIED' AND additional_due>0))",p,Boolean.class));
        var recoveries=jdbc.query("SELECT a.id,x.attempt_id,x.deducted,x.applied_at FROM marketplace.claim_fee_assessment a JOIN marketplace.claim_fee_application x ON x.assessment_id=a.id WHERE a.seller_order_id=:seller AND a.state='APPLIED' ORDER BY a.id",p,(r,n)->new Recovery(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getTimestamp(4).toInstant()));
        var completed=jdbc.queryForObject("""
                SELECT min(d.occurred_at) FROM marketplace.claim_seller_shipping_current c
                JOIN marketplace.claim_seller_shipping_decision current ON current.id=c.decision_id
                JOIN marketplace.claim_seller_shipping_decision d ON d.seller_order_id=c.seller_order_id
                AND d.state=current.state AND d.amount=current.amount
                WHERE c.seller_order_id=:seller AND current.state IN('RETAINED','REFUND_REQUIRED')
                """,p,java.sql.Timestamp.class);
        var reservations=jdbc.query("""
                SELECT a.id,a.accepted_at,CASE WHEN a.state='WITHDRAWN' THEN w.withdrawn_at
                WHEN a.state='APPLIED' AND a.additional_due=0 THEN app.applied_at END AS released_at
                FROM marketplace.claim_fee_assessment a LEFT JOIN marketplace.claim_fee_application app ON app.assessment_id=a.id
                LEFT JOIN marketplace.claim_fee_withdrawal w ON w.assessment_id=a.id
                WHERE a.seller_order_id=:seller ORDER BY a.accepted_at,a.id
                """,p,(r,n)->new Reservation(r.getObject(1,UUID.class),r.getTimestamp(2).toInstant(),r.getTimestamp(3)==null?null:r.getTimestamp(3).toInstant()));
        return new View(pending,recoveries,completed==null?null:completed.toInstant(),reservations);
    }
}
