package com.example.marketplace.content.infrastructure;

import com.example.marketplace.content.api.DashboardContent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardContent implements DashboardContent {
    private final JdbcTemplate jdbc;
    public JdbcDashboardContent(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                SELECT 'PRODUCT',q.product_id,NULL::date,'QUESTIONS_UNANSWERED',count(*)
                 FROM marketplace.content_product_question q LEFT JOIN marketplace.content_product_answer a ON a.question_id=q.id
                 WHERE a.id IS NULL GROUP BY q.product_id
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
