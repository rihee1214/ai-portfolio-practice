package com.example.marketplace.order.infrastructure;

import com.example.marketplace.notification.api.NotificationEvents;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.seller.api.Stores;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public class OrderNotifications {
    private final NamedParameterJdbcTemplate jdbc;
    private final Stores stores;
    private final NotificationEvents events;
    public OrderNotifications(NamedParameterJdbcTemplate jdbc,Stores stores,NotificationEvents events) {
        this.jdbc=jdbc;this.stores=stores;this.events=events;
    }
    public void paid(Orders.Snapshot order,UUID event) {
        if(order.memberId()!=null) { events.record(NotificationEvents.Kind.PAYMENT_BUYER,event,order.memberId(),order.id()); }
        for(var seller:order.sellers()) {
            events.record(NotificationEvents.Kind.PAYMENT_SELLER,event,stores.tradeOwner(seller.storeId()),seller.id());
        }
    }
    public void confirmed(UUID receipt) {
        var sellers=jdbc.queryForList("""
                SELECT DISTINCT s.id,s.store_id FROM marketplace.order_confirmation_unit u
                JOIN marketplace.order_item i ON i.id=u.item_id JOIN marketplace.seller_order s ON s.id=i.seller_order_id
                WHERE u.confirmation_id=:id
                """,Map.of("id",receipt));
        for(var seller:sellers) {
            events.record(NotificationEvents.Kind.PURCHASE_CONFIRMED,receipt,stores.tradeOwner((UUID)seller.get("store_id")),(UUID)seller.get("id"));
        }
    }
    public void shipped(UUID order,UUID event,boolean exchange) {
        UUID member=jdbc.queryForObject("SELECT member_id FROM marketplace.purchase_order WHERE id=:id",Map.of("id",order),UUID.class);
        if(member!=null) { events.record(exchange?NotificationEvents.Kind.EXCHANGE_SHIPPED:NotificationEvents.Kind.SHIPPED,event,member,order); }
    }
}
