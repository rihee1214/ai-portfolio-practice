package com.example.marketplace.promotion.infrastructure;
import com.example.marketplace.promotion.api.Coupons;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.promotion.domain.CouponRules;
import com.example.marketplace.promotion.domain.PromotionRules;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.seller.api.Stores;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service @Profile("db")
public final class JdbcCoupons implements Coupons {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final SellerMembership members;
    private final Stores stores;
    private final TransactionTemplate tx;
    private static final String TEMPLATE="SELECT t.*,s.slug AS store_slug,s.name AS store_name FROM marketplace.coupon_template t JOIN marketplace.seller_store s ON s.id=t.store_id";
    // Cross-module read projection; all identity/store mutations remain with their owners.
    private static final String VISIBLE="s.state='OPEN' AND s.visible AND EXISTS(SELECT 1 FROM marketplace.identity_user u WHERE u.id=s.owner_id AND u.state='ACTIVE') AND EXISTS(SELECT 1 FROM marketplace.role_assignment r WHERE r.user_id=s.owner_id AND r.role='SELLER')";
    public JdbcCoupons(NamedParameterJdbcTemplate jdbc,AccessPolicy access,SellerMembership members,Stores stores,PlatformTransactionManager manager) { this.jdbc=jdbc;this.access=access;this.members=members;this.stores=stores;tx=new TransactionTemplate(manager); }
    private void page(int p) { if(p<0||p>10000) { throw new PromotionRejected(400); } }
    private void buyer(UUID member) { if(member==null) { throw new PromotionRejected(401); } access.require(member,AccessArea.BUYER); }
    @Override public Template template(UUID actor,UUID id) {
        access.require(actor,AccessArea.SELLER);var rows=jdbc.query(TEMPLATE+" WHERE t.id=:id AND s.owner_id=:owner",Map.of("id",id,"owner",actor),this::templateMap);
        if(rows.isEmpty()) { throw new PromotionRejected(404); } return rows.getFirst();
    }
    @Override public Page<Template> templates(UUID actor,int page) {
        page(page);access.require(actor,AccessArea.SELLER);var rows=jdbc.query(TEMPLATE+" WHERE s.owner_id=:owner ORDER BY t.id LIMIT 21 OFFSET :offset",Map.of("owner",actor,"offset",page*20),this::templateMap);return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Page<Template> available(UUID member,int page) {
        page(page);buyer(member);var rows=jdbc.query(TEMPLATE+" WHERE "+VISIBLE+" AND t.state='ACTIVE' AND t.issue_start<=:at AND t.issue_end>:at AND t.issued_count<t.total_limit AND NOT EXISTS(SELECT 1 FROM marketplace.issued_coupon c WHERE c.template_id=t.id AND c.member_id=:member) ORDER BY t.id LIMIT 21 OFFSET :offset",Map.of("member",member,"offset",page*20,"at",java.sql.Timestamp.from(Instant.now())),this::templateMap);return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Template saveTemplate(UUID actor,UUID id,TemplateInput i) {
        access.require(actor,AccessArea.SELLER);
        if(i==null||i.title()==null||i.title().isBlank()||i.title().codePointCount(0,i.title().length())>120||i.value()==null||i.minimum()==null||i.totalLimit()==null||i.version()==null||i.version()<0||i.version()>9007199254740990L||!java.util.Set.of("DRAFT","ACTIVE","STOPPED","ARCHIVED").contains(i.state()==null?"":i.state())) { throw new PromotionRejected(400); }
        var terms=new Terms(i.kind(),i.value(),i.cap(),i.minimum(),i.startsAt(),i.endsAt());CouponRules.validate(terms,i.issueStart(),i.issueEnd(),i.totalLimit());
        return tx.execute(status->{
            stores.requireCapability(actor,Stores.Capability.MANAGE_CATALOG);access.require(actor,AccessArea.SELLER);Template old=id==null?null:template(actor,id);
            if(old==null) { if(!i.state().equals("DRAFT")||i.version()!=0) { throw new PromotionRejected(400); } }
            else { jdbc.queryForObject("SELECT id FROM marketplace.coupon_template WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);old=template(actor,id);
                if(old.version()!=i.version()||old.state().equals("ARCHIVED")||!old.state().equals("DRAFT")&&i.state().equals("DRAFT")||i.totalLimit()<old.issuedCount()) { throw new PromotionRejected(409); } }
            UUID key=id==null?UUID.randomUUID():id;long version=old==null?0:old.version()+1;
            var p=parameters(terms).addValue("id",key).addValue("store",stores.own(actor).id()).addValue("title",i.title().trim()).addValue("issueStart",java.sql.Timestamp.from(i.issueStart())).addValue("issueEnd",java.sql.Timestamp.from(i.issueEnd())).addValue("limit",i.totalLimit()).addValue("state",i.state()).addValue("version",version);
            if(old==null) { jdbc.update("INSERT INTO marketplace.coupon_template(id,store_id,title,kind,value,cap,minimum,issue_start,issue_end,starts_at,ends_at,total_limit,state,version) VALUES(:id,:store,:title,:kind,:value,:cap,:minimum,:issueStart,:issueEnd,:start,:end,:limit,:state,:version)",p); }
            else { jdbc.update("UPDATE marketplace.coupon_template SET title=:title,kind=:kind,value=:value,cap=:cap,minimum=:minimum,issue_start=:issueStart,issue_end=:issueEnd,starts_at=:start,ends_at=:end,total_limit=:limit,state=:state,version=:version WHERE id=:id",p); }
            event(key,null,actor,"TEMPLATE",version,i.toString());return template(actor,key);
        });
    }
    private List<Issued> existing(UUID member,UUID template) { return jdbc.query("SELECT c.*,s.slug AS store_slug,s.name AS store_name FROM marketplace.issued_coupon c JOIN marketplace.seller_store s ON s.id=c.store_id WHERE c.member_id=:member AND c.template_id=:template",Map.of("member",member,"template",template),this::issuedMap); }
    @Override public Issued claim(UUID member,UUID templateId) {
        buyer(member);if(templateId==null) { throw new PromotionRejected(400); }
        if(!existing(member,templateId).isEmpty()) { return tx.execute(status->{members.lockActiveBuyer(member);return existing(member,templateId).getFirst();}); }
        return tx.execute(status->{
            var owners=jdbc.queryForList("SELECT s.owner_id FROM marketplace.coupon_template t JOIN marketplace.seller_store s ON s.id=t.store_id WHERE t.id=:id",Map.of("id",templateId),UUID.class);
            if(owners.isEmpty()) { throw new PromotionRejected(404); } UUID owner=owners.getFirst();
            java.util.stream.Stream.of(member,owner).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).forEach(members::lockActiveBuyer);
            var prior=existing(member,templateId);if(!prior.isEmpty()) { return prior.getFirst(); }
            stores.requireCapability(owner,Stores.Capability.NEW_SALES);access.require(owner,AccessArea.SELLER);
            jdbc.queryForObject("SELECT id FROM marketplace.coupon_template WHERE id=:id FOR UPDATE",Map.of("id",templateId),UUID.class);var t=template(owner,templateId);Instant now=Instant.now();
            if(!t.state().equals("ACTIVE")||!PromotionRules.active(t.issueStart(),t.issueEnd(),now)||!now.isBefore(t.terms().endsAt())||t.issuedCount()>=t.totalLimit()) { throw new PromotionRejected(409); }
            UUID id=UUID.randomUUID();var p=parameters(t.terms()).addValue("id",id).addValue("member",member).addValue("template",templateId).addValue("store",t.storeId()).addValue("title",t.title()).addValue("version",t.version());
            int changed=jdbc.update("UPDATE marketplace.coupon_template SET issued_count=issued_count+1 WHERE id=:template AND issued_count<total_limit",p);if(changed!=1) { throw new PromotionRejected(409); }
            jdbc.update("INSERT INTO marketplace.issued_coupon(id,template_id,member_id,store_id,title,kind,value,cap,minimum,starts_at,ends_at,template_version) VALUES(:id,:template,:member,:store,:title,:kind,:value,:cap,:minimum,:start,:end,:version)",p);
            event(templateId,id,member,"CLAIM",0,t.toString());return own(member,id);
        });
    }
    @Override public Issued own(UUID member,UUID id) {
        buyer(member);if(id==null) { throw new PromotionRejected(400); }var rows=jdbc.query("SELECT c.*,s.slug AS store_slug,s.name AS store_name FROM marketplace.issued_coupon c JOIN marketplace.seller_store s ON s.id=c.store_id WHERE c.id=:id AND c.member_id=:member",Map.of("id",id,"member",member),this::issuedMap);if(rows.isEmpty()) { throw new PromotionRejected(404); }return rows.getFirst();
    }
    @Override public Page<Issued> owned(UUID member,int page) {
        page(page);buyer(member);var rows=jdbc.query("SELECT c.*,s.slug AS store_slug,s.name AS store_name FROM marketplace.issued_coupon c JOIN marketplace.seller_store s ON s.id=c.store_id WHERE c.member_id=:member ORDER BY c.id LIMIT 21 OFFSET :offset",Map.of("member",member,"offset",page*20),this::issuedMap);return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Eligibility eligible(UUID member,UUID id,UUID store,long subtotal,Instant at) {
        if(subtotal<0||at==null||store==null) { throw new PromotionRejected(400); }var c=own(member,id);
        boolean visible=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace.seller_store s WHERE s.id=:store AND "+VISIBLE+")",Map.of("store",c.storeId()),Boolean.class);
        String reason=!store.equals(c.storeId())?"STORE":!visible?"SELLER":!c.state().equals("AVAILABLE")?"STATE":!CouponRules.inUsePeriod(c.terms(),at)?"PERIOD":subtotal<c.terms().minimum()?"MINIMUM":"ELIGIBLE";
        return new Eligibility(reason.equals("ELIGIBLE"),reason,reason.equals("ELIGIBLE")?CouponRules.discount(c.terms(),subtotal,at):0,c);
    }
    private MapSqlParameterSource parameters(Terms t) { return new MapSqlParameterSource("kind",t.kind()).addValue("value",t.value()).addValue("cap",t.cap()).addValue("minimum",t.minimum()).addValue("start",java.sql.Timestamp.from(t.startsAt())).addValue("end",java.sql.Timestamp.from(t.endsAt())); }
    private void event(UUID template,UUID coupon,UUID actor,String action,long version,String snapshot) { jdbc.update("INSERT INTO marketplace.coupon_event(id,template_id,coupon_id,actor_id,action,version,snapshot) VALUES(:id,:template,:coupon,:actor,:action,:version,:snapshot)",new MapSqlParameterSource("id",UUID.randomUUID()).addValue("template",template).addValue("coupon",coupon).addValue("actor",actor).addValue("action",action).addValue("version",version).addValue("snapshot",snapshot)); }
    private Terms terms(java.sql.ResultSet r) throws java.sql.SQLException { return new Terms(r.getString("kind"),r.getLong("value"),r.getObject("cap",Long.class),r.getLong("minimum"),r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant()); }
    private Template templateMap(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Template(r.getObject("id",UUID.class),r.getObject("store_id",UUID.class),r.getString("title"),terms(r),r.getTimestamp("issue_start").toInstant(),r.getTimestamp("issue_end").toInstant(),r.getLong("total_limit"),r.getLong("issued_count"),r.getString("state"),r.getLong("version"),r.getString("store_slug"),r.getString("store_name")); }
    private Issued issuedMap(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Issued(r.getObject("id",UUID.class),r.getObject("template_id",UUID.class),r.getObject("store_id",UUID.class),r.getString("title"),terms(r),r.getLong("template_version"),r.getString("state"),r.getLong("version"),r.getString("store_slug"),r.getString("store_name")); }
}
