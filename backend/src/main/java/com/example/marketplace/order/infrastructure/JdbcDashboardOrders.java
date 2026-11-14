package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.DashboardOrders;
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
public class JdbcDashboardOrders implements DashboardOrders {
    private final JdbcTemplate jdbc;
    public JdbcDashboardOrders(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override public List<Created> created() {
        return jdbc.query("""
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date,NULL::uuid,count(*)
                FROM marketplace.purchase_order GROUP BY 1
                UNION ALL
                SELECT (o.created_at AT TIME ZONE 'Asia/Seoul')::date,s.store_id,count(*)
                FROM marketplace.seller_order s JOIN marketplace.purchase_order o ON o.id=s.order_id GROUP BY 1,2
                """,(r,n)->new Created(r.getObject(1,LocalDate.class),r.getObject(2,UUID.class),r.getLong(3)));
    }

    @Override public List<Allocation> allocations() {
        return jdbc.query("""
                SELECT order_id,id,store_id,subtotal,product_discount,coupon_discount,
                       shipping_base-shipping_discount,total FROM marketplace.seller_order
                """,(r,n)->new Allocation(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getLong(4),r.getLong(5),r.getLong(6),r.getLong(7),r.getLong(8)));
    }

    @Override public List<Confirmed> confirmed() {
        return jdbc.query("""
                SELECT (c.confirmed_at AT TIME ZONE 'Asia/Seoul')::date,s.store_id,sum(u.net::numeric)
                FROM marketplace.order_confirmation c JOIN marketplace.order_confirmation_unit cu ON cu.confirmation_id=c.id
                JOIN marketplace.order_unit u ON u.item_id=cu.item_id AND u.ordinal=cu.ordinal
                JOIN marketplace.order_item i ON i.id=cu.item_id
                JOIN marketplace.seller_order s ON s.id=i.seller_order_id GROUP BY 1,2
                """,(r,n)->new Confirmed(r.getObject(1,LocalDate.class),r.getObject(2,UUID.class),
                r.getBigDecimal(3).toBigIntegerExact()));
    }
}
