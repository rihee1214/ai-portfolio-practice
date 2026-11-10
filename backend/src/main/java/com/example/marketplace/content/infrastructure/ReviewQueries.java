package com.example.marketplace.content.infrastructure;

import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.ReviewPurchases;
import com.example.marketplace.media.api.ReviewImages;
import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.identity.api.AccessRejected;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ReviewQueries {
    public record Entry(UUID id, int rating, String body, long version, boolean deleted) { }
    public record Response(UUID id, String kind, String body, long version, boolean mine) { }
    public record PublicEntry(UUID id, int rating, String body, long edition, boolean refunded, boolean canReply,
            List<Response> responses, List<ReviewImages.Image> images) { }
    public record Published(List<PublicEntry> reviews, long ratingSum, int count) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final Browse browse;
    private final ReviewPurchases purchases;
    private final ObjectProvider<ReviewImages> images;
    private final Stores stores;

    public ReviewQueries(NamedParameterJdbcTemplate jdbc, Browse browse, ReviewPurchases purchases,
            ObjectProvider<ReviewImages> images, Stores stores) {
        this.jdbc = jdbc;
        this.browse = browse;
        this.purchases = purchases;
        this.images = images;
        this.stores = stores;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Published published(String slug, UUID viewer, boolean seller) {
        UUID product = browse.identify(slug);
        if (product == null) { throw new OrderRejected(404); }
        UUID ownStore = seller && viewer != null ? replyStore(viewer) : null;
        var rows = entries(product, null, ownStore, viewer);
        return new Published(rows, rows.stream().mapToLong(PublicEntry::rating).sum(), rows.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicEntry detail(UUID id, UUID viewer, boolean seller) {
        var parent = visibleParent(id);
        var rows = entries((UUID)parent.get("product_id"), id, seller && viewer != null ? replyStore(viewer) : null, viewer);
        if (rows.isEmpty()) { throw new OrderRejected(404); }
        return rows.getFirst();
    }

    private List<PublicEntry> entries(UUID product, UUID review, UUID ownStore, UUID viewer) {
        return jdbc.query("""
                SELECT id,rating,body,edition,order_item_id,store_id FROM marketplace.content_review
                WHERE product_id=:product AND (CAST(:review AS uuid) IS NULL OR id=:review) AND NOT author_deleted AND NOT hidden_by_admin ORDER BY id
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("product",product).addValue("review",review), (r, n) -> {
                    UUID id = r.getObject(1, UUID.class);
                    long edition = r.getLong(4);
                    var responses = jdbc.query("""
                            SELECT id,kind,body,version,member_id FROM marketplace.content_review_response
                            WHERE review_id=:id AND edition=:edition AND NOT author_deleted AND NOT hidden_by_admin ORDER BY id
                            """, Map.of("id", id, "edition", edition), (row,i) -> new Response(row.getObject(1, UUID.class),
                                    row.getString(2), row.getString(3), row.getLong(4), row.getObject(5, UUID.class).equals(viewer)));
                    var storage = images.getIfAvailable();
                    return new PublicEntry(id, r.getInt(2), r.getString(3), edition,
                            purchases.fullyRefunded(r.getObject(5, UUID.class)), r.getObject(6,UUID.class).equals(ownStore), responses,
                            storage == null ? List.of() : storage.list(id, edition));
                });
    }

    private UUID replyStore(UUID member) {
        try {
            if (!stores.capabilities(member).manageCatalog()) { return null; }
            var store = stores.own(member);
            return store == null ? null : store.id();
        } catch (AccessRejected revoked) { return null; }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] image(UUID id, UUID asset) {
        return images.getObject().read(id, (Long) visibleParent(id).get("edition"), asset);
    }

    private Map<String,Object> visibleParent(UUID id) {
        var reviews = jdbc.queryForList("""
                SELECT product_id,edition FROM marketplace.content_review
                WHERE id=:id AND NOT author_deleted AND NOT hidden_by_admin
                """, Map.of("id", id));
        if (reviews.isEmpty() || browse.forProduct((UUID) reviews.getFirst().get("product_id")) == null) {
            throw new OrderRejected(404);
        }
        return reviews.getFirst();
    }

    public Entry own(UUID member, UUID item) {
        var rows = jdbc.query("""
                SELECT id,rating,body,version,author_deleted FROM marketplace.content_review
                WHERE member_id=:member AND order_item_id=:item
                """, Map.of("member", member, "item", item), (r, n) -> new Entry(r.getObject(1, UUID.class),
                        r.getInt(2), r.getBoolean(5) ? "" : r.getString(3), r.getLong(4), r.getBoolean(5)));
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
