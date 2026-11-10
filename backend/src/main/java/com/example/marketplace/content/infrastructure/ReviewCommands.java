package com.example.marketplace.content.infrastructure;

import com.example.marketplace.content.domain.ReviewEligibility;
import com.example.marketplace.media.api.ReviewImages;
import com.example.marketplace.identity.api.HistorySessions;
import org.springframework.beans.factory.ObjectProvider;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.ReviewPurchases;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ReviewCommands {
    public enum Action { CREATE, EDIT, DELETE, REWRITE }
    public record Command(UUID key, Action action, Long expectedVersion, Integer rating, String body) { }
    public record Receipt(UUID reviewId, long version) { }
    private record Existing(UUID id, boolean deleted, long version, long edition) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final ReviewPurchases purchases;
    private final ObjectProvider<ReviewImages> images;
    private final HistorySessions sessions;

    public ReviewCommands(NamedParameterJdbcTemplate jdbc, ReviewPurchases purchases, ObjectProvider<ReviewImages> images,
            HistorySessions sessions) {
        this.jdbc = jdbc;
        this.purchases = purchases;
        this.images = images;
        this.sessions = sessions;
    }

    @Transactional
    public Receipt apply(String number, UUID itemId, OrderQueries.Authority actor, Command command) {
        var item = purchases.lock(number, itemId, actor);
        if (command == null || command.key() == null || command.action() == null
                || command.expectedVersion() == null || command.expectedVersion() < 0) {
            throw new OrderRejected(400);
        }
        var body = command.body() == null ? null : command.body().strip();
        if (command.action() == Action.DELETE) {
            if (body != null || command.rating() != null) { throw new OrderRejected(400); }
        } else if (body == null || body.isBlank() || body.codePointCount(0, body.length()) > 2000
                || command.rating() == null || command.rating() < 1 || command.rating() > 5) {
            throw new OrderRejected(400);
        }
        var params = new MapSqlParameterSource("item", itemId).addValue("member", item.memberId())
                .addValue("product", item.productId()).addValue("store", item.storeId())
                .addValue("action", command.action().name()).addValue("key", command.key())
                .addValue("expected", command.expectedVersion()).addValue("rating", command.rating())
                .addValue("body", body);
        String payload = "jsonb_build_array(CAST(:expected AS bigint),CAST(:rating AS int),CAST(:body AS text))";
        var receipts = jdbc.query("SELECT review_id,result_version,payload=" + payload + """
                 FROM marketplace.content_review_command
                 WHERE member_id=:member AND order_item_id=:item AND request_key=:key AND action=:action
                """, params, (r, n) -> {
                    if (!r.getBoolean(3)) { throw new OrderRejected(409); }
                    return new Receipt(r.getObject(1, UUID.class), r.getLong(2));
                });
        if (!receipts.isEmpty()) { return receipts.getFirst(); }
        var existing = jdbc.query("""
                SELECT id,author_deleted,version,edition FROM marketplace.content_review
                WHERE order_item_id=:item AND member_id=:member FOR UPDATE
                """, params, (r, n) -> new Existing(r.getObject(1, UUID.class), r.getBoolean(2), r.getLong(3), r.getLong(4)));
        Action action = command.action();
        requireCurrent(actor);
        if (action == Action.CREATE || action == Action.REWRITE) {
            var units = item.units().stream().map(unit ->
                    new ReviewEligibility.Unit(unit.confirmation() != null, unit.refundCause() != null)).toList();
            if (!ReviewEligibility.mayWrite(units)) { throw new OrderRejected(409); }
        }
        Receipt result;
        if (action == Action.CREATE) {
            if (!existing.isEmpty() || command.expectedVersion() != 0) { throw new OrderRejected(409); }
            result = new Receipt(UUID.randomUUID(), 1);
            jdbc.update("""
                    INSERT INTO marketplace.content_review(id,order_item_id,member_id,product_id,store_id,rating,body)
                    VALUES(:id,:item,:member,:product,:store,:rating,:body)
                    """, params.addValue("id", result.reviewId()));
        } else {
            if (existing.isEmpty()) { throw new OrderRejected(404); }
            var review = existing.getFirst();
            if (review.version() != command.expectedVersion() || review.deleted() != (action == Action.REWRITE)) {
                throw new OrderRejected(409);
            }
            result = new Receipt(review.id(), review.version() + 1);
            if (action == Action.DELETE) {
                images.ifAvailable(storage -> storage.retireAll(review.id(), review.edition()));
            }
            params.addValue("id", review.id());
            jdbc.update("""
                    UPDATE marketplace.content_review SET rating=coalesce(:rating,rating),body=coalesce(:body,body),
                    author_deleted=(:action='DELETE'),edition=edition+CASE WHEN :action='REWRITE' THEN 1 ELSE 0 END,
                    version=version+1 WHERE id=:id
                    """, params);
        }
        jdbc.update("""
                INSERT INTO marketplace.content_review_command(member_id,order_item_id,request_key,action,
                    review_id,result_version,payload) VALUES(:member,:item,:key,:action,:id,:version,
                """ + payload + ")", params.addValue("version", result.version()));
        return result;
    }

    @Transactional
    public Receipt image(String number, UUID item, OrderQueries.Authority actor, UUID key, long version,
            int slot, String filename, byte[] bytes) {
        purchases.lock(number, item, actor);
        if (slot < 0 || slot >= 5 || version < 1 || bytes != null && bytes.length > 5242880) {
            throw new OrderRejected(400);
        }
        var p = new MapSqlParameterSource("item", item).addValue("member", actor.member()).addValue("key", key)
                .addValue("version", version).addValue("slot", slot).addValue("filename", filename)
                .addValue("digest", bytes == null ? null : sha256(bytes));
        String payload = "jsonb_build_array(CAST(:version AS bigint),CAST(:slot AS int),CAST(:filename AS text),CAST(:digest AS text))";
        var prior = jdbc.query("SELECT review_id,result_version,payload=" + payload + """
                 FROM marketplace.content_review_command WHERE order_item_id=:item AND member_id=:member AND action='IMAGE' AND request_key=:key
                """, p, (r,n) -> {
                    if (!r.getBoolean(3)) { throw new OrderRejected(409); }
                    return new Receipt(r.getObject(1,UUID.class),r.getLong(2));
                });
        if (!prior.isEmpty()) { return prior.getFirst(); }
        var rows = jdbc.queryForList("""
                SELECT id,edition FROM marketplace.content_review
                WHERE order_item_id=:item AND member_id=:member AND NOT author_deleted AND version=:version FOR UPDATE
                """, p);
        requireCurrent(actor);
        if (rows.isEmpty()) { throw new OrderRejected(409); }
        var id = (UUID) rows.getFirst().get("id");
        long edition = (Long) rows.getFirst().get("edition");
        var storage = images.getObject();
        if (bytes == null) { storage.remove(id, edition, slot); }
        else { storage.replace(actor.member(), id, edition, slot, filename, bytes); }
        p.addValue("id", id);
        jdbc.update("UPDATE marketplace.content_review SET version=version+1 WHERE id=:id", p);
        jdbc.update("""
                INSERT INTO marketplace.content_review_command(member_id,order_item_id,request_key,action,
                review_id,result_version,payload) VALUES(:member,:item,:key,'IMAGE',:id,:version+1,
                """ + payload + ")", p);
        return new Receipt(id, version+1);
    }
    private void requireCurrent(OrderQueries.Authority actor) {
        if (!sessions.lockReadable(actor.browser(), actor.member(), actor.version())) { throw new OrderRejected(404); }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
