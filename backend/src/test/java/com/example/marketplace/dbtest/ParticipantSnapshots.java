package com.example.marketplace.dbtest;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** Full-row participant observations in one PostgreSQL statement; no business commands. */
final class ParticipantSnapshots {
    static final List<String> ORDER_TABLES = List.of("guest_checkout", "guest_attempt", "guest_buyer",
            "guest_access_session", "guest_order_reference", "order_checkout_attempt", "purchase_order",
            "seller_order", "order_item", "order_unit", "order_contact", "order_reservation", "order_event",
            "inventory_balance", "inventory_reservation", "inventory_reservation_event", "coupon_reservation_batch",
            "issued_coupon", "coupon_reservation", "coupon_reservation_event", "job", "outbox_event");
    static final List<String> AFTERCARE_TABLES = List.of("order_aftercare_policy", "order_policy_migration",
            "order_confirmation_state", "order_unit_lifecycle", "order_confirmation", "order_confirmation_unit",
            "order_unit_hold", "order_hold_unit", "order_lifecycle_event", "order_dispatch", "order_dispatch_unit",
            "order_confirmation_schedule", "order_confirmation_scan", "order_initial_delivery", "order_initial_delivery_unit",
            "shipment", "shipment_item", "shipment_unit", "shipment_event", "shipment_audit", "shipment_preparation",
            "shipment_command", "shipment_tracking_history");
    static final List<String> RETURN_APPLICATION_TABLES = List.of("order_return_application", "claim_return_request",
            "claim_return_unit", "claim_return_history", "claim_return_command", "claim_return_collection",
            "claim_return_inspection", "claim_return_inspection_unit", "inventory_return_restitution",
            "inventory_restitution_unit", "inventory_restitution_movement", "claim_return_fee_quote_context",
            "claim_return_financial_consent", "claim_return_financial_result", "claim_return_support_command",
            "order_return_physical_unit", "claim_return_physical_source", "inventory_return_physical_restitution",
            "claim_return_item_ready", "claim_return_ready_unit", "claim_seller_shipping_decision",
            "claim_seller_shipping_current", "claim_return_shipping_binding", "order_return_completion",
            "order_return_completion_unit", "order_shipping_decision", "order_shipping_current", "order_aftercare_shipping",
            "claim_shipping_result", "claim_return_completion", "claim_shipping_support_command");

    private ParticipantSnapshots() { }

    static Map<String,String> read(JdbcTemplate jdbc, String schema, List<String> tables) {
        if (!schema.matches("[a-z][a-z0-9_]*") || tables.isEmpty()
                || tables.stream().distinct().count() != tables.size()
                || tables.stream().anyMatch(table -> !table.matches("[a-z][a-z0-9_]*"))) {
            throw new IllegalArgumentException("Explicit unique participant identifiers required");
        }
        String sql = tables.stream().map(table -> "SELECT '" + table
                + "' AS participant, coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text AS snapshot FROM "
                + schema + "." + table + " t").collect(Collectors.joining(" UNION ALL "));
        var result = new TreeMap<String,String>();
        jdbc.query(sql, row -> { result.put(row.getString("participant"), row.getString("snapshot")); });
        if (result.size() != tables.size()) { throw new IllegalStateException("Incomplete participant snapshot"); }
        return result;
    }
}
