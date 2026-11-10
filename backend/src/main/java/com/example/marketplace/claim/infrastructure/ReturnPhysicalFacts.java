package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.Returns;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Claim-owned original-unit projection; neither collection nor inspection implies payment completion. */
final class ReturnPhysicalFacts {
 private ReturnPhysicalFacts() { }
 static List<Returns.Physical> read(NamedParameterJdbcTemplate jdbc,UUID id) {
  return jdbc.query("SELECT u.item_id,u.ordinal,c.id AS collection_id,c.status,i.inspection_id,i.condition,i.responsibility,i.restitution_id,p.source_snapshot::text AS physical_snapshot FROM marketplace.claim_return_unit u LEFT JOIN LATERAL (SELECT c.id,c.status FROM marketplace.claim_return_collection c WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal ORDER BY c.version DESC LIMIT 1) c ON true LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=u.return_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal LEFT JOIN marketplace.claim_return_physical_source p ON p.return_id=u.return_id AND p.item_id=u.item_id AND p.ordinal=u.ordinal WHERE u.return_id=:id ORDER BY u.item_id::text,u.ordinal",Map.of("id",id),(r,n)->new Returns.Physical(r.getObject("item_id",UUID.class),r.getInt("ordinal"),r.getString("status")==null?"PENDING":r.getString("status"),r.getString("condition")==null?"NOT_INSPECTED":r.getString("condition"),r.getString("responsibility")==null?null:Returns.Responsibility.valueOf(r.getString("responsibility")),r.getObject("collection_id",UUID.class),r.getObject("inspection_id",UUID.class),r.getObject("restitution_id",UUID.class),r.getString("physical_snapshot")==null?null:new tools.jackson.databind.ObjectMapper().readValue(r.getString("physical_snapshot"),com.example.marketplace.order.api.PhysicalOrders.Source.class)));
 }
}
