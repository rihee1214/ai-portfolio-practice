package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.DashboardProducts;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db") @Transactional(propagation=Propagation.MANDATORY)
public class JdbcDashboardProducts implements DashboardProducts {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDashboardProducts(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public List<Product> products() {
        return jdbc.getJdbcTemplate().query("""
                SELECT p.id,p.store_id,p.state,p.is_hidden_by_admin,pub.id IS NOT NULL
                FROM marketplace.catalog_product p LEFT JOIN (
                """+JdbcProducts.PUBLIC+") pub ON pub.id=p.id",
                (r,n)->new Product(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getBoolean(4),r.getBoolean(5)));
    }
    @Override public List<Sku> skus() {
        return jdbc.getJdbcTemplate().query("SELECT s.id,p.store_id FROM marketplace.catalog_sku s JOIN marketplace.catalog_product p ON p.id=s.product_id",
                (r,n)->new Sku(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));
    }
}
