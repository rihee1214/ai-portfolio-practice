package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.DashboardPayments;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcDashboardPayments implements DashboardPayments {
    private final JdbcTemplate jdbc;
    public JdbcDashboardPayments(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }
    @Override public List<Charge> charges() {
        return jdbc.query("""
                SELECT p.order_id,(e.occurred_at AT TIME ZONE 'Asia/Seoul')::date,p.amount
                FROM marketplace.payment_attempt p JOIN marketplace.payment_event e
                  ON e.attempt_id=p.id AND e.event_key=p.id::text||':succeeded' AND e.action='SUCCEEDED'
                """,(r,n)->new Charge(r.getObject(1,UUID.class),r.getObject(2,LocalDate.class),r.getLong(3)));
    }
    @Override public List<Refund> refunds() {
        return jdbc.query("""
                SELECT seller_order_id,purpose,(result_at AT TIME ZONE 'Asia/Seoul')::date,amount
                FROM marketplace.payment_refund_attempt WHERE state='SUCCEEDED'
                """,(r,n)->new Refund(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,LocalDate.class),r.getLong(4)));
    }
    @Override public List<Pending> unresolved() {
        return jdbc.query("""
                SELECT order_id,state FROM marketplace.payment_attempt WHERE state IN ('FAILED','UNKNOWN','PROCESSING','CREATED')
                """,(r,n)->new Pending(r.getObject(1,UUID.class),r.getString(2)));
    }
}
