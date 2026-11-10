package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.SettlementOrders;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class JdbcSettlementOrders implements SettlementOrders {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcSettlementOrders(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public List<UUID> candidates(String version) {
        return jdbc.queryForList("SELECT o.id FROM marketplace.purchase_order o JOIN marketplace.order_settlement_policy p ON p.order_id=o.id WHERE p.version=:version AND o.state IN('PAID','CLOSED','CANCELED') ORDER BY o.id",Map.of("version",version),UUID.class);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<Seller> lock(UUID order,String version) {
        var p=Map.of("order",order,"version",version);
        if(jdbc.queryForList("SELECT o.id FROM marketplace.purchase_order o JOIN marketplace.order_settlement_policy p ON p.order_id=o.id WHERE o.id=:order AND p.version=:version FOR UPDATE OF o",p).isEmpty()) { return List.of(); }
        return jdbc.query("SELECT s.id,s.store_id,s.shipping_base-s.shipping_discount AS shipping,o.created_at FROM marketplace.seller_order s JOIN marketplace.purchase_order o ON o.id=s.order_id WHERE s.order_id=:order ORDER BY s.id",p,(r,n)-> {
            UUID seller=r.getObject("id",UUID.class);var s=Map.of("seller",seller);
            var units=jdbc.query("""
                    SELECT u.item_id,u.ordinal,u.gross,u.promotion_discount,u.coupon_discount,u.net,l.state,
                    c.id AS confirmation_id,c.confirmed_at,l.refund_cause_id,
                    COALESCE(cs.refund_attempt_id,rc.item_attempt_id,ec.item_attempt_id) AS refund_attempt_id,
                    COALESCE(c.confirmed_at,event.occurred_at) AS terminal_at,
                    CASE WHEN rc.return_id IS NOT NULL THEN 'RETURN' WHEN ec.exchange_id IS NOT NULL THEN 'EXCHANGE_REFUND'
                    WHEN cs.cause_id IS NOT NULL THEN 'CANCEL' END AS refund_kind
                    FROM marketplace.order_item i JOIN marketplace.order_unit u ON u.item_id=i.id
                    JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal
                    LEFT JOIN marketplace.order_confirmation c ON c.id=l.confirmation_id
                    LEFT JOIN marketplace.order_cancellation_settlement cs ON cs.cause_id=l.refund_cause_id
                    LEFT JOIN marketplace.order_return_completion rc ON rc.cause_id=l.refund_cause_id
                    LEFT JOIN marketplace.order_exchange_refund_completion ec ON ec.cause_id=l.refund_cause_id
                    LEFT JOIN marketplace.order_unit_hold h ON h.cause_id=l.refund_cause_id
                    LEFT JOIN marketplace.order_lifecycle_event event ON event.cause_id=h.id AND event.action='REFUNDED'
                    WHERE i.seller_order_id=:seller ORDER BY u.item_id::text,u.ordinal
                    """,s,(u,k)->new Unit(u.getObject("item_id",UUID.class),u.getInt("ordinal"),u.getLong("gross"),u.getLong("promotion_discount"),u.getLong("coupon_discount"),u.getLong("net"),u.getString("state"),u.getObject("confirmation_id",UUID.class),u.getTimestamp("confirmed_at")==null?null:u.getTimestamp("confirmed_at").toInstant(),u.getObject("refund_cause_id",UUID.class),u.getObject("refund_attempt_id",UUID.class),u.getTimestamp("terminal_at")==null?null:u.getTimestamp("terminal_at").toInstant(),u.getString("refund_kind")));
            var refunds=jdbc.query("""
                    SELECT cause_id,refund_attempt_id,amount FROM marketplace.order_cancellation_shipping WHERE seller_order_id=:seller
                    UNION ALL SELECT cause_id,refund_attempt_id,amount FROM marketplace.order_aftercare_shipping WHERE seller_order_id=:seller
                    """,s,(u,k)->new ShippingRefund(u.getObject(1,UUID.class),u.getObject(2,UUID.class),u.getLong(3)));
            var decisions=jdbc.queryForList("SELECT d.state FROM marketplace.order_shipping_current c JOIN marketplace.order_shipping_decision d ON d.decision_id=c.decision_id WHERE c.seller_order_id=:seller",s,String.class);
            String shippingState=decisions.isEmpty()?null:decisions.getFirst();
            if(shippingState==null&&units.stream().anyMatch(u->u.state().equals("CONFIRMED"))&&Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT NOT EXISTS(SELECT 1 FROM marketplace.order_unit_lifecycle l
                    WHERE l.seller_order_id=:seller AND l.state='REFUNDED'
                    AND NOT EXISTS(SELECT 1 FROM marketplace.order_cancellation_settlement_unit c WHERE c.item_id=l.item_id AND c.ordinal=l.ordinal))
                    """,s,Boolean.class))) { shippingState="RETAINED"; }
            var holds=jdbc.query("""
                    SELECT DISTINCT h.id,h.acquired_at,e.occurred_at AS released_at FROM marketplace.order_unit_hold h
                    JOIN marketplace.order_hold_unit hu ON hu.hold_id=h.id JOIN marketplace.order_item i ON i.id=hu.item_id
                    LEFT JOIN marketplace.order_lifecycle_event e ON e.cause_id=h.id AND e.action IN('RELEASED','REFUNDED')
                    WHERE i.seller_order_id=:seller ORDER BY h.acquired_at,h.id
                    """,s,(h,k)->new Hold(h.getObject("id",UUID.class),h.getTimestamp("acquired_at").toInstant(),h.getTimestamp("released_at")==null?null:h.getTimestamp("released_at").toInstant()));
            return new Seller(order,seller,r.getObject("store_id",UUID.class),version,r.getLong("shipping"),units,refunds,shippingState,r.getTimestamp("created_at").toInstant(),holds);
        });
    }
}
