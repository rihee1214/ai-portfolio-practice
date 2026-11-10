package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.SettlementPayments;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcSettlementPayments implements SettlementPayments {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcSettlementPayments(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Payment> lock(UUID order) {
        return jdbc.query("SELECT id,amount,mode FROM marketplace.payment_attempt WHERE order_id=:order AND state='SUCCEEDED' AND compensation='NONE' ORDER BY id FOR UPDATE",Map.of("order",order),(r,n)-> {
            UUID id=r.getObject("id",UUID.class);
            var refunds=jdbc.query("""
                    SELECT p.id,p.cause_id,p.seller_order_id,p.claim_id,p.sequence,p.purpose,p.amount,p.state,p.result_at,p.created_at,
                    CASE WHEN p.state IN('SUCCEEDED','FAILED','WITHDRAWN') THEN greatest(p.result_at,
                    (SELECT max(e.occurred_at) FROM marketplace.payment_refund_event e WHERE e.attempt_id=p.id AND e.action=p.state)) END AS released_at
                    FROM marketplace.payment_refund_attempt p WHERE p.payment_id=:payment ORDER BY p.id FOR UPDATE OF p
                    """,Map.of("payment",id),(x,k)->new Refund(x.getObject(1,UUID.class),x.getObject(2,UUID.class),x.getObject(3,UUID.class),x.getObject(4,UUID.class),x.getInt(5),x.getString(6),x.getLong(7),x.getString(8),x.getTimestamp(9)==null?null:x.getTimestamp(9).toInstant(),x.getTimestamp(10).toInstant(),x.getTimestamp(11)==null?null:x.getTimestamp(11).toInstant()));
            return new Payment(id,r.getLong("amount"),r.getString("mode"),List.copyOf(refunds));
        });
    }
}
