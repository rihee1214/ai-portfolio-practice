package com.example.marketplace.inventory.infrastructure;

import com.example.marketplace.inventory.api.DashboardStocks;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardStocks implements DashboardStocks {
    private final JdbcTemplate jdbc;
    public JdbcDashboardStocks(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                SELECT 'SKU',sku_id,NULL::date,metric,v FROM marketplace.inventory_balance
                 CROSS JOIN LATERAL (VALUES ('STOCK_ON_HAND',on_hand),('STOCK_RESERVED',reserved),('STOCK_AVAILABLE',on_hand-reserved)) a(metric,v)
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
