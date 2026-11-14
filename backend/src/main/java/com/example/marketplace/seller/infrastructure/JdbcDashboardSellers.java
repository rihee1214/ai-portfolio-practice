package com.example.marketplace.seller.infrastructure;

import com.example.marketplace.seller.api.DashboardSellers;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardSellers implements DashboardSellers {
    private final JdbcTemplate jdbc;
    public JdbcDashboardSellers(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                SELECT 'GLOBAL',NULL::uuid,NULL::date,'APPLICATION_'||state,count(*) FROM marketplace.seller_application GROUP BY state
                UNION ALL SELECT 'GLOBAL',NULL,(created_at AT TIME ZONE 'Asia/Seoul')::date,'APPLICATION_RECEIVED',count(*) FROM marketplace.seller_application GROUP BY 3
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
