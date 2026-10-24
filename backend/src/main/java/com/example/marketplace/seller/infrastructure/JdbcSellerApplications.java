package com.example.marketplace.seller.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.seller.api.SellerApplications;
import com.example.marketplace.seller.api.SellerApplicationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSellerApplications implements SellerApplications {
    private final NamedParameterJdbcTemplate jdbc;
    private final SellerMembership members;
    private final AccessPolicy access;
    private final TransactionTemplate transaction;
    public JdbcSellerApplications(NamedParameterJdbcTemplate jdbc, SellerMembership members, AccessPolicy access, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.members = members; this.access = access; this.transaction = new TransactionTemplate(manager);
    }
    @Override public Application submit(UUID applicant, Input input) {
        if (input == null) { throw new SellerApplicationException(400); }
        String name = text(input.displayName(), 80);
        String description = text(input.description(), 500);
        return transaction.execute(status -> {
            if (members.lockActiveBuyer(applicant)) { throw new SellerApplicationException(409); }
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace.seller_application WHERE applicant_id=:id AND state IN ('PENDING','APPROVED'))",
                    Map.of("id", applicant), Boolean.class))) { throw new SellerApplicationException(409); }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.seller_application(id,applicant_id,display_name,description,state) VALUES(:id,:member,:name,:description,'PENDING')",
                    Map.of("id", id, "member", applicant, "name", name, "description", description));
            event(id, applicant, "PENDING", "모의 판매 신청");
            return application(id);
        });
    }
    @Override public Page own(UUID applicant, int page) { return listing(applicant, page); }
    @Override public Page reviewQueue(UUID operator, int page) { access.require(operator, AccessArea.ACCOUNTS); return listing(null, page); }
    private Page listing(UUID applicant, int page) {
        if (page < 0 || page > 10000) { throw new SellerApplicationException(400); }
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("offset", page * 20);
        if (applicant != null) { params.addValue("member", applicant); }
        var ids = jdbc.queryForList("SELECT id FROM marketplace.seller_application " + (applicant == null ? "" : "WHERE applicant_id=:member ")
                + "ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET :offset", params, UUID.class);
        return new Page(ids.stream().limit(20).map(this::application).toList(), page, ids.size() > 20);
    }
    @Override public Application decide(UUID operator, UUID id, String decision, String reason) {
        access.require(operator, AccessArea.ACCOUNTS);
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) { throw new SellerApplicationException(400); }
        String clean = text(reason, 300);
        return transaction.execute(status -> {
            var owners = jdbc.queryForList("SELECT applicant_id FROM marketplace.seller_application WHERE id=:id", Map.of("id", id), UUID.class);
            if (owners.isEmpty()) { throw new SellerApplicationException(404); }
            UUID applicant = owners.getFirst();
            if (operator.equals(applicant)) { throw new SellerApplicationException(403); }
            if (members.lockActiveBuyer(applicant)) { throw new SellerApplicationException(409); }
            var states = jdbc.queryForList("SELECT state FROM marketplace.seller_application WHERE id=:id FOR UPDATE", Map.of("id", id), String.class);
            access.require(operator, AccessArea.ACCOUNTS);
            if (!"PENDING".equals(states.getFirst())) { throw new SellerApplicationException(409); }
            jdbc.update("UPDATE marketplace.seller_application SET state=:state WHERE id=:id", Map.of("id", id, "state", decision));
            if ("APPROVED".equals(decision)) { members.grant(operator, applicant); }
            event(id, operator, decision, clean);
            return application(id);
        });
    }
    private Application application(UUID id) {
        // A single statement keeps the application and its history on the same DB snapshot.
        return jdbc.query("SELECT a.*,e.actor_id,e.state AS event_state,e.reason,e.occurred_at FROM marketplace.seller_application a LEFT JOIN marketplace.seller_application_event e ON e.application_id=a.id WHERE a.id=:id ORDER BY e.occurred_at,e.id",
                Map.of("id", id), row -> {
                    if (!row.next()) { throw new SellerApplicationException(404); }
                    UUID applicant = row.getObject("applicant_id", UUID.class);
                    String name = row.getString("display_name"), description = row.getString("description"), state = row.getString("state");
                    var created = row.getTimestamp("created_at").toInstant();
                    List<Event> events = new java.util.ArrayList<>();
                    do {
                        if (row.getObject("actor_id") != null) {
                            events.add(new Event(row.getObject("actor_id", UUID.class), row.getString("event_state"), row.getString("reason"), row.getTimestamp("occurred_at").toInstant()));
                        }
                    } while (row.next());
                    return new Application(id, applicant, name, description, state, created, List.copyOf(events));
                });
    }
    private void event(UUID id, UUID actor, String state, String reason) {
        jdbc.update("INSERT INTO marketplace.seller_application_event(id,application_id,actor_id,state,reason) VALUES(:event,:id,:actor,:state,:reason)",
                Map.of("event", UUID.randomUUID(), "id", id, "actor", actor, "state", state, "reason", reason));
    }
    private String text(String value, int max) {
        if (value == null) { throw new SellerApplicationException(400); }
        String clean = value.strip();
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > max
                || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) { throw new SellerApplicationException(400); }
        return clean;
    }
}
