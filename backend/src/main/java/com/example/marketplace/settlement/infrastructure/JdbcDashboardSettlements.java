package com.example.marketplace.settlement.infrastructure;

import com.example.marketplace.settlement.api.DashboardSettlements;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardSettlements implements DashboardSettlements {
    private final JdbcTemplate jdbc;
    public JdbcDashboardSettlements(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                WITH amounts AS (
                 SELECT store_id,(occurred_at AT TIME ZONE 'Asia/Seoul')::date AS day,sum(amount::numeric) AS gross,sum(fee::numeric) AS fee
                 FROM marketplace.settlement_ledger GROUP BY 1,2
                ), balances AS (
                 SELECT b.id,b.week_start,b.state,(x->>'storeId')::uuid AS store_id,(x->>'opening')::numeric AS opening,(x->>'closing')::numeric AS closing,(x->>'payout')::numeric AS payout
                 FROM marketplace.settlement_batch b CROSS JOIN LATERAL jsonb_array_elements(b.store_balances) x
                ), latest AS (SELECT DISTINCT ON(store_id) * FROM balances ORDER BY store_id,week_start DESC),
                totals AS (SELECT i.batch_id,sum(l.amount::numeric) AS gross,sum(l.fee::numeric) AS fee FROM marketplace.settlement_batch_item i JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id GROUP BY i.batch_id)
                SELECT 'STORE',store_id,day,m,v FROM amounts CROSS JOIN LATERAL (VALUES ('LEDGER_GROSS',gross),('LEDGER_FEE',fee),('LEDGER_NET',gross-fee)) metrics(m,v)
                UNION ALL SELECT 'STORE',store_id,NULL,m,v FROM latest CROSS JOIN LATERAL (VALUES ('CARRY_OPENING',opening),('CARRY_CLOSING',closing)) metrics(m,v)
                UNION ALL SELECT 'GLOBAL',NULL,NULL,'BATCH_'||state,count(*) FROM marketplace.settlement_batch GROUP BY state
                UNION ALL SELECT 'STORE',b.store_id,(p.occurred_at AT TIME ZONE 'Asia/Seoul')::date,'MOCK_PAYOUT',sum(b.payout) FROM balances b JOIN marketplace.settlement_mock_payment p ON p.batch_id=b.id GROUP BY 2,3
                UNION ALL SELECT 'STORE',l.store_id,(p.occurred_at AT TIME ZONE 'Asia/Seoul')::date,'MOCK_PAYOUT',sum(l.amount::numeric-l.fee)
                 FROM marketplace.settlement_mock_payment p JOIN marketplace.settlement_batch b ON b.id=p.batch_id
                 JOIN marketplace.settlement_batch_item i ON i.batch_id=b.id JOIN marketplace.settlement_ledger l ON l.id=i.ledger_id
                 WHERE b.readiness_version=1 AND b.store_balances='[]'::jsonb GROUP BY 2,3
                UNION ALL SELECT 'GLOBAL',NULL,NULL,'BATCH_MISMATCH',count(*) FROM marketplace.settlement_batch b LEFT JOIN totals t ON t.batch_id=b.id
                 WHERE b.gross<>coalesce(t.gross,0) OR b.fee<>coalesce(t.fee,0) OR b.net<>coalesce(t.gross-t.fee,0)
                UNION ALL SELECT 'SELLER_ORDER',(h->>'sellerOrderId')::uuid,NULL,'SETTLEMENT_HELD',count(*) FROM marketplace.settlement_batch b CROSS JOIN LATERAL jsonb_array_elements(b.held) h WHERE b.state<>'PAID_MOCK' GROUP BY 2
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
