package com.example.marketplace.content.infrastructure;

import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.content.domain.InquiryText;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.seller.api.Stores;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ProductQuestions {
    public enum Visibility { PUBLIC, PRIVATE }
    public record Create(UUID key, Visibility visibility, String body) { }
    public record Answer(UUID key, Long expectedVersion, String body) { }
    public record Receipt(UUID id,long version) { }
    public record View(UUID id,String visibility,String body,long version,UUID answerId,String answer,boolean canAnswer,
                       boolean ownQuestion,boolean ownAnswer,boolean canReport) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final Browse browse;
    private final Stores stores;
    private final HistorySessions sessions;
    private final SupportAssignments assignments;
    public ProductQuestions(NamedParameterJdbcTemplate jdbc,Browse browse,Stores stores,HistorySessions sessions,SupportAssignments assignments) {
        this.jdbc=jdbc; this.browse=browse; this.stores=stores; this.sessions=sessions; this.assignments=assignments;
    }
    private boolean current(SupportAssignments.Actor actor) {
        return actor.member()!=null && sessions.lockReadable(actor.session(),actor.member(),actor.version());
    }
    private UUID product(String slug) {
        UUID id=browse.identify(slug);
        if (id==null) { throw new OrderRejected(404); }
        return id;
    }
    private String body(String raw) {
        try { return InquiryText.normalize(raw); } catch (IllegalArgumentException invalid) { throw new OrderRejected(400); }
    }
    @Transactional
    public Receipt create(String slug,SupportAssignments.Actor actor,Create cmd) {
        if (!current(actor)) { throw new OrderRejected(403); }
        if (cmd.key()==null || cmd.visibility()==null) { throw new OrderRejected(400); }
        UUID product=product(slug);
        var p=new MapSqlParameterSource("id",UUID.randomUUID()).addValue("product",product).addValue("member",actor.member())
                .addValue("key",cmd.key()).addValue("body",body(cmd.body())).addValue("visibility",cmd.visibility().name());
        var created=jdbc.queryForList("""
                INSERT INTO marketplace.content_product_question(id,product_id,author_id,visibility,body,request_key)
                VALUES(:id,:product,:member,:visibility,:body,:key)
                ON CONFLICT(author_id,request_key) DO NOTHING RETURNING id
                """,p,UUID.class);
        if (!current(actor)) { throw new OrderRejected(403); }
        if (!created.isEmpty()) {
            if (cmd.visibility()==Visibility.PRIVATE) { assignments.register(created.getFirst(),"PRODUCT_QUESTION"); }
            return new Receipt(created.getFirst(),1);
        }
        var same=jdbc.queryForList("""
                SELECT id FROM marketplace.content_product_question WHERE author_id=:member AND request_key=:key
                AND body=:body AND visibility=:visibility AND product_id=:product
                """,p,UUID.class);
        if (same.isEmpty()) { throw new OrderRejected(409); }
        return new Receipt(same.getFirst(),1);
    }
    private boolean owns(SupportAssignments.Actor actor, String storeSlug, boolean seller) {
        if (!seller || !current(actor)) { return false; }
        var own=stores.own(actor.member());
        return own!=null && storeSlug.equals(own.slug()) && stores.capabilities(actor.member()).manageCatalog();
    }
    private static final String SELECT="""
            SELECT q.id,q.product_id,q.author_id,q.visibility,q.body,q.version,a.id AS answer_id,a.author_id AS answer_author,a.body AS answer
            FROM marketplace.content_product_question q LEFT JOIN marketplace.content_product_answer a ON a.question_id=q.id
            """;
    boolean currentSeller(UUID question,SupportAssignments.Actor actor,boolean seller){
        UUID product=jdbc.queryForObject("SELECT product_id FROM marketplace.content_product_question WHERE id=:q",Map.of("q",question),UUID.class);
        var detail=browse.forProduct(product);
        return detail!=null && owns(actor,detail.product().storeSlug(),seller);
    }
    private View visible(Map<String,Object> row,SupportAssignments.Actor actor,boolean selfStore) {
        UUID id=(UUID)row.get("id");
        if ("PRIVATE".equals(row.get("visibility")) && !selfStore
                && !(actor.member()!=null && actor.member().equals(row.get("author_id")) && current(actor))
                && !assignments.assigned(id,actor)) { return null; }
        return new View(id,(String)row.get("visibility"),(String)row.get("body"),(Long)row.get("version"),
                (UUID)row.get("answer_id"),(String)row.get("answer"),selfStore && row.get("answer_id")==null,
                actor.member()!=null && actor.member().equals(row.get("author_id")) && current(actor),
                selfStore && actor.member().equals(row.get("answer_author")),
                actor.member()!=null && ("PUBLIC".equals(row.get("visibility")) || selfStore || actor.member().equals(row.get("author_id"))));
    }
    @Transactional
    public List<View> list(String slug,SupportAssignments.Actor actor,boolean seller) {
        UUID id=product(slug);
        var detail=browse.forProduct(id);
        if (detail==null) { throw new OrderRejected(404); }
        boolean selfStore=owns(actor,detail.product().storeSlug(),seller);
        return jdbc.queryForList(SELECT+" WHERE q.product_id=:p ORDER BY q.id",Map.of("p",id)).stream()
                .map(row->visible(row,actor,selfStore)).filter(java.util.Objects::nonNull).toList();
    }
    @Transactional
    public View get(UUID id,SupportAssignments.Actor actor,boolean seller) {
        var rows=jdbc.queryForList(SELECT+" WHERE q.id=:id",Map.of("id",id));
        if (rows.isEmpty()) { throw new OrderRejected(404); }
        var row=rows.getFirst();
        var detail=browse.forProduct((UUID)row.get("product_id"));
        if (detail==null) { throw new OrderRejected(404); }
        var view=visible(row,actor,owns(actor,detail.product().storeSlug(),seller));
        if (view==null) { throw new OrderRejected(404); }
        return view;
    }
    @Transactional
    public Receipt answer(UUID id,SupportAssignments.Actor actor,Answer cmd) {
        if (actor.member()==null) { throw new OrderRejected(403); }
        stores.requireCapability(actor.member(),Stores.Capability.MANAGE_CATALOG);
        if (!current(actor)) { throw new OrderRejected(403); }
        if (cmd.key()==null || cmd.expectedVersion()==null) { throw new OrderRejected(400); }
        String body=body(cmd.body());
        var p=new MapSqlParameterSource("id",id).addValue("member",actor.member()).addValue("key",cmd.key())
                .addValue("version",cmd.expectedVersion()).addValue("body",body);
        var rows=jdbc.queryForList("SELECT product_id,version FROM marketplace.content_product_question WHERE id=:id FOR UPDATE",p);
        if (rows.isEmpty() || !current(actor)) { throw new OrderRejected(404); }
        var product=browse.forProduct((UUID)rows.getFirst().get("product_id"));
        if (product==null || !owns(actor,product.product().storeSlug(),true)) { throw new OrderRejected(404); }
        var existing=jdbc.queryForList("SELECT id FROM marketplace.content_product_answer WHERE question_id=:id AND author_id=:member AND request_key=:key AND expected_version=:version AND body=:body",p,UUID.class);
        if (!existing.isEmpty()) { return new Receipt(existing.getFirst(),cmd.expectedVersion()+1); }
        if (!cmd.expectedVersion().equals(rows.getFirst().get("version"))) { throw new OrderRejected(409); }
        UUID answer=UUID.randomUUID();
        if (jdbc.update("""
                INSERT INTO marketplace.content_product_answer(id,question_id,author_id,body,request_key,expected_version)
                VALUES(:answer,:id,:member,:body,:key,:version) ON CONFLICT(question_id) DO NOTHING
                """,p.addValue("answer",answer))!=1) { throw new OrderRejected(409); }
        jdbc.update("UPDATE marketplace.content_product_question SET version=version+1 WHERE id=:id",p);
        return new Receipt(answer,cmd.expectedVersion()+1);
    }
    @Transactional
    public com.example.marketplace.media.api.MessageImages.Target messageAccess(UUID id,UUID message,SupportAssignments.Actor actor,
            boolean seller,boolean write) {
        var p=Map.of("id",id);
        if(write && !id.equals(message)) {
            if(!seller || actor.member()==null) { throw new OrderRejected(404); }
            stores.requireCapability(actor.member(),Stores.Capability.MANAGE_CATALOG);
        }
        // All authenticated message reads acquire the member/session lock before the question row.
        if(actor.member()!=null && !current(actor)) { throw new OrderRejected(404); }
        var locked=jdbc.queryForList("SELECT author_id FROM marketplace.content_product_question WHERE id=:id"+
                (write?" FOR UPDATE":" FOR SHARE"),p,UUID.class);
        if(locked.isEmpty()) { throw new OrderRejected(404); }
        var view=get(id,actor,seller);
        if(id.equals(message)) {
            if(write && (!locked.getFirst().equals(actor.member()) || !current(actor))) { throw new OrderRejected(404); }
            return new com.example.marketplace.media.api.MessageImages.Target(com.example.marketplace.media.api.MessageImages.Kind.PRODUCT_QUESTION,message);
        }
        if(!message.equals(view.answerId())) { throw new OrderRejected(404); }
        if(write) {
            var answerers=jdbc.queryForList("SELECT author_id FROM marketplace.content_product_answer WHERE question_id=:id",p,UUID.class);
            if(!seller || !answerers.contains(actor.member()) || !current(actor)) { throw new OrderRejected(404); }
            if(!view.ownAnswer()) { throw new OrderRejected(404); }
        }
        return new com.example.marketplace.media.api.MessageImages.Target(com.example.marketplace.media.api.MessageImages.Kind.PRODUCT_ANSWER,message);
    }
}
