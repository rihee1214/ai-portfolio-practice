package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.ReviewPurchases;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class JdbcReviewPurchases implements ReviewPurchases {
    private final OrderQueries orders;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReviewPurchases(OrderQueries orders, NamedParameterJdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Item get(String number, UUID itemId, OrderQueries.Authority authority) {
        requireMember(authority);
        return facts(orders.requireOwned(number, authority).id(), itemId, authority.member());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Item lock(String number, UUID itemId, OrderQueries.Authority authority) {
        requireMember(authority);
        return facts(orders.lockOwned(number, authority).id(), itemId, authority.member());
    }

    private static void requireMember(OrderQueries.Authority authority) {
        if (authority == null || authority.member() == null) {
            throw new OrderRejected(404);
        }
    }

    @Override
    public boolean fullyRefunded(UUID itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(l.refund_cause_id IS NOT NULL)
                FROM marketplace.order_unit_lifecycle l WHERE l.item_id=:item
                """, Map.of("item", itemId), Boolean.class));
    }

    private Item facts(UUID orderId, UUID itemId, UUID memberId) {
        var params = Map.of("orderId", orderId, "itemId", itemId);
        record Header(UUID productId, UUID storeId, long revision) { }
        var headers = jdbc.query("""
                SELECT CAST(i.line_snapshot->>'productId' AS uuid), s.store_id, c.revision
                FROM marketplace.order_item i
                JOIN marketplace.seller_order s ON s.id=i.seller_order_id AND s.order_id=i.order_id
                JOIN marketplace.order_confirmation_state c ON c.order_id=i.order_id
                WHERE i.order_id=:orderId AND i.id=:itemId
                """, params, (r, n) -> new Header(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)));
        if (headers.size() != 1) {
            throw new OrderRejected(404);
        }
        var units = jdbc.query("""
                SELECT l.ordinal, l.state, l.active_hold_id, l.refund_cause_id,
                       c.id, c.cause, CAST(c.result->>'revision' AS bigint), c.confirmed_at
                FROM marketplace.order_unit_lifecycle l
                LEFT JOIN marketplace.order_confirmation_unit u
                    ON (u.order_id,u.item_id,u.ordinal)=(l.order_id,l.item_id,l.ordinal)
                LEFT JOIN marketplace.order_confirmation c ON c.id=u.confirmation_id AND c.order_id=l.order_id
                WHERE l.order_id=:orderId AND l.item_id=:itemId ORDER BY l.ordinal
                """, params, (r, n) -> {
                    UUID id = r.getObject(5, UUID.class);
                    var confirmation = id == null ? null : new Confirmation(id, r.getString(6),
                            r.getLong(7), r.getTimestamp(8).toInstant());
                    return new Unit(r.getInt(1), r.getString(2), r.getObject(3, UUID.class),
                            r.getObject(4, UUID.class), confirmation);
                });
        Header header = headers.getFirst();
        return new Item(itemId, memberId, header.productId(), header.storeId(), header.revision(), units);
    }
}
