package com.example.marketplace.content.infrastructure;

import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.seller.api.Stores;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ReviewResponses {
    public enum Kind { COMMENT, SELLER_REPLY }
    public enum Action { CREATE, EDIT, DELETE }
    public record Command(UUID key, Kind kind, Action action, UUID responseId, Long expectedVersion, Long edition, String body) { }
    public record Receipt(UUID id, long version) { }
    private final HistorySessions sessions;
    private final Stores stores;
    private final Browse browse;
    private final NamedParameterJdbcTemplate jdbc;

    public ReviewResponses(HistorySessions sessions, Stores stores, Browse browse, NamedParameterJdbcTemplate jdbc) {
        this.sessions = sessions;
        this.stores = stores;
        this.browse = browse;
        this.jdbc = jdbc;
    }

    @Transactional
    public Receipt apply(UUID review, OrderQueries.Authority actor, Command cmd) {
        if (actor.member() == null) { throw new OrderRejected(403); }
        if (cmd.key() == null || cmd.kind() == null || cmd.action() == null || cmd.edition() == null ||
                cmd.expectedVersion() == null || (cmd.action() == Action.CREATE) != (cmd.responseId() == null)) {
            throw new OrderRejected(400);
        }
        String body = cmd.body() == null ? null : cmd.body().strip();
        if (cmd.action() == Action.DELETE ? body != null : body == null || body.isBlank() ||
                body.codePointCount(0, body.length()) > (cmd.kind() == Kind.COMMENT ? 500 : 1000)) {
            throw new OrderRejected(400);
        }
        if (cmd.kind() == Kind.SELLER_REPLY) { stores.requireCapability(actor.member(), Stores.Capability.MANAGE_CATALOG); }
        if (!sessions.lockReadable(actor.browser(), actor.member(), actor.version())) { throw new OrderRejected(403); }
        var p = new MapSqlParameterSource("review", review).addValue("actor", actor.member()).addValue("key", cmd.key())
                .addValue("target", cmd.responseId()).addValue("expected", cmd.expectedVersion()).addValue("edition", cmd.edition())
                .addValue("kind", cmd.kind().name()).addValue("action", cmd.action().name()).addValue("body", body);
        var parents = jdbc.queryForList("""
                SELECT edition,product_id,store_id FROM marketplace.content_review
                WHERE id=:review AND NOT author_deleted AND NOT hidden_by_admin FOR UPDATE
                """, p);
        if (parents.isEmpty() || browse.forProduct((UUID) parents.getFirst().get("product_id")) == null) {
            throw new OrderRejected(404);
        }
        if (!sessions.lockReadable(actor.browser(), actor.member(), actor.version())) { throw new OrderRejected(403); }
        if (cmd.kind() == Kind.SELLER_REPLY) {
            var store = stores.own(actor.member());
            if (store == null || !store.id().equals(parents.getFirst().get("store_id"))) { throw new OrderRejected(403); }
        }
        String payload = "jsonb_build_array(CAST(:target AS uuid),CAST(:kind AS text),CAST(:action AS text)," +
                "CAST(:expected AS bigint),CAST(:edition AS bigint),CAST(:body AS text))";
        var receipts = jdbc.query("SELECT response_id,result_version,payload=" + payload + """
                 FROM marketplace.content_response_command WHERE member_id=:actor AND review_id=:review AND request_key=:key
                """, p, (r,n) -> {
                    if (!r.getBoolean(3)) { throw new OrderRejected(409); }
                    return new Receipt(r.getObject(1, UUID.class), r.getLong(2));
                });
        if (!receipts.isEmpty()) { return receipts.getFirst(); }
        if (!cmd.edition().equals(parents.getFirst().get("edition"))) { throw new OrderRejected(409); }
        UUID id = cmd.responseId();
        if (cmd.action() == Action.CREATE) {
            if (cmd.expectedVersion() != 0) { throw new OrderRejected(409); }
            id = UUID.randomUUID();
            int count = jdbc.update("""
                    INSERT INTO marketplace.content_review_response(id,review_id,edition,member_id,kind,body)
                    VALUES(:id,:review,:edition,:actor,:kind,:body) ON CONFLICT DO NOTHING
                    """, p.addValue("id", id));
            if (count != 1) { throw new OrderRejected(409); }
        } else {
            int count = jdbc.update("""
                    UPDATE marketplace.content_review_response SET author_deleted=(:action='DELETE'),
                    body=coalesce(:body,body),version=version+1
                    WHERE id=:id AND member_id=:actor AND review_id=:review AND edition=:edition AND kind=:kind
                    AND version=:expected AND NOT author_deleted AND NOT hidden_by_admin
                    """, p.addValue("id", id));
            if (count != 1) { throw new OrderRejected(409); }
        }
        long version = cmd.expectedVersion() + 1;
        jdbc.update("""
                INSERT INTO marketplace.content_response_command(review_id,member_id,request_key,response_id,result_version,payload)
                VALUES(:review,:actor,:key,:id,:version,
                """ + payload + ")", p.addValue("version", version));
        return new Receipt(id, version);
    }
}
