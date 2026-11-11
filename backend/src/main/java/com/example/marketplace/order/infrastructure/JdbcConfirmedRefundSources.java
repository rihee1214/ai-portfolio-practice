package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.CancellationOrders;
import com.example.marketplace.order.api.ConfirmedRefundSources;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderRejected;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcConfirmedRefundSources implements ConfirmedRefundSources {
    private final NamedParameterJdbcTemplate jdbc;
    private final CancellationOrders orders;
    public JdbcConfirmedRefundSources(NamedParameterJdbcTemplate jdbc,CancellationOrders orders) {
        this.jdbc=jdbc;this.orders=orders;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Source lock(String number,UUID seller,UUID item,int ordinal) {
        var params=Map.of("number",number,"seller",seller,"item",item,"ordinal",ordinal);
        var ids=jdbc.queryForList("""
                SELECT o.id FROM marketplace.purchase_order o JOIN marketplace.seller_order s ON s.order_id=o.id
                WHERE o.order_number=:number AND s.id=:seller FOR UPDATE OF o
                """,params,UUID.class);
        if(ids.size()!=1) { throw new OrderRejected(404); }
        orders.lockUnits(ids.getFirst(),List.of(new OrderConfirmations.Unit(item,ordinal)));
        var rows=jdbc.query("""
                SELECT l.order_id,l.confirmation_id,u.net,o.policy_version FROM marketplace.order_unit_lifecycle l
                JOIN marketplace.order_confirmation_unit c ON (c.order_id,c.item_id,c.ordinal,c.confirmation_id)
                    =(l.order_id,l.item_id,l.ordinal,l.confirmation_id)
                JOIN marketplace.order_unit u ON (u.item_id,u.ordinal)=(l.item_id,l.ordinal)
                JOIN marketplace.purchase_order o ON o.id=l.order_id
                WHERE o.order_number=:number AND l.seller_order_id=:seller AND l.item_id=:item AND l.ordinal=:ordinal
                AND l.state='CONFIRMED'
                """,params,(r,n)->new Source(r.getObject(1,UUID.class),seller,item,ordinal,r.getObject(2,UUID.class),r.getLong(3),r.getString(4)));
        if(rows.size()!=1) { throw new OrderRejected(409); }
        return rows.getFirst();
    }
}
