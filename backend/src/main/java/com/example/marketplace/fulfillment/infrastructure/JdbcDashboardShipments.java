package com.example.marketplace.fulfillment.infrastructure;

import com.example.marketplace.fulfillment.api.DashboardShipments;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcDashboardShipments implements DashboardShipments {
    private final JdbcTemplate jdbc;
    public JdbcDashboardShipments(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc.getJdbcTemplate(); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Fact> measure() {
        return jdbc.query("""
                SELECT 'SELLER_ORDER',seller_order_id,NULL::date,'SHIPMENT_'||state,count(*) FROM marketplace.shipment GROUP BY seller_order_id,state
                UNION ALL SELECT 'SELLER_ORDER',seller_order_id,NULL, 'SHIPMENT_'||exception_status,count(*) FROM marketplace.shipment WHERE exception_status IN ('DELAYED','FAILED') GROUP BY seller_order_id,exception_status
                UNION ALL SELECT 'SELLER_ORDER',seller_order_id,NULL,'REPLACEMENT_'||state,count(*) FROM marketplace.fulfillment_exchange_shipment GROUP BY seller_order_id,state
                UNION ALL SELECT 'SELLER_ORDER',seller_order_id,NULL,'REPLACEMENT_OLDEST_SECONDS',
                     greatest(0,floor(extract(epoch FROM transaction_timestamp()-min(shipped_at))))
                   FROM marketplace.fulfillment_exchange_shipment
                   WHERE state IN ('SHIPPED','IN_TRANSIT') GROUP BY seller_order_id
                UNION ALL SELECT 'SELLER_ORDER',s.seller_order_id,NULL,'SHIPPED_ORIGINAL_UNITS',count(*) FROM marketplace.shipment s JOIN marketplace.shipment_unit u ON u.shipment_id=s.id WHERE s.state<>'PREPARING' GROUP BY s.seller_order_id
                """,(r,n)->new Fact(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,LocalDate.class),
                        r.getString(4),r.getBigDecimal(5).toBigIntegerExact()));
    }
}
