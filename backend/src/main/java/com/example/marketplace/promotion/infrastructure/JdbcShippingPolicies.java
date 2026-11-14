package com.example.marketplace.promotion.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.promotion.api.PromotionRejected;
import com.example.marketplace.promotion.api.ShippingPolicies;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public class JdbcShippingPolicies implements ShippingPolicies {
    public record Actor(UUID member,String session,Long authVersion) { }
    public record Change(UUID key,long expectedVersion,Long baseFee,Long freeThreshold,String reason) { }
    public record Revision(long version,long baseFee,long freeThreshold,String reason,UUID actor,Instant createdAt) { }
    private final JdbcTemplate db;
    private final HistorySessions sessions;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    private static final String CURRENT="SELECT p.version,p.base_fee,p.free_threshold FROM marketplace.site_shipping_head h "
            +"JOIN marketplace.site_shipping_policy p ON p.version=h.version WHERE h.singleton";

    public JdbcShippingPolicies(NamedParameterJdbcTemplate jdbc,HistorySessions sessions,AccessPolicy access,PlatformTransactionManager manager) {
        this.db=jdbc.getJdbcTemplate();this.sessions=sessions;this.access=access;this.tx=new TransactionTemplate(manager);
    }
    private Policy load(String lock) {
        return db.queryForObject(CURRENT+lock,(rs,n)->new Policy(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
    }
    @Override public Policy current() { return load(""); }
    @Override public Policy lockForCheckout() {
        long version=db.queryForObject("SELECT version FROM marketplace.site_shipping_head WHERE singleton FOR SHARE",Long.class);
        return db.queryForObject("SELECT version,base_fee,free_threshold FROM marketplace.site_shipping_policy WHERE version=?",
                (rs,n)->new Policy(rs.getLong(1),rs.getLong(2),rs.getLong(3)),version);
    }
    private void authorize(Actor actor) {
        if(actor.member()==null||!sessions.lockMemberThenSession(actor.session(),actor.member(),actor.authVersion())
                ||!access.allowed(actor.member(),AccessArea.SYSTEM)) { throw new PromotionRejected(403); }
    }
    public List<Revision> history(Actor actor,long before) {
        return tx.execute(status->{
            authorize(actor);
            return db.query("SELECT version,base_fee,free_threshold,reason,actor_id,created_at FROM marketplace.site_shipping_policy "
                    +"WHERE version<? ORDER BY version DESC LIMIT 50",(rs,n)->new Revision(rs.getLong(1),rs.getLong(2),
                    rs.getLong(3),rs.getString(4),rs.getObject(5,UUID.class),rs.getTimestamp(6).toInstant()),before);
        });
    }
    public Policy change(Actor actor,Change change) {
        if(change==null||change.key()==null||change.expectedVersion()<1||!money(change.baseFee())||!money(change.freeThreshold())
                ||change.reason()==null||change.reason().isBlank()||change.reason().codePoints().count()>500
                ||change.reason().codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')) { throw new PromotionRejected(400); }
        return tx.execute(status->{
            authorize(actor);
            db.queryForObject("SELECT version FROM marketplace.site_shipping_head WHERE singleton FOR UPDATE",Long.class);
            authorize(actor);
            var prior=db.query("SELECT version,base_fee,free_threshold,reason FROM marketplace.site_shipping_policy WHERE actor_id=? AND request_key=?",
                    (rs,n)->{var policy=new Policy(rs.getLong(1),rs.getLong(2),rs.getLong(3));
                        if(policy.version()-1!=change.expectedVersion()||policy.baseFee()!=change.baseFee()||policy.freeThreshold()!=change.freeThreshold()
                                ||!rs.getString(4).equals(change.reason().strip())) { throw new PromotionRejected(409); }
                        return policy;},actor.member(),change.key());
            if(!prior.isEmpty()) { return prior.getFirst(); }
            if(current().version()!=change.expectedVersion()) { throw new PromotionRejected(409); }
            long next=Math.incrementExact(change.expectedVersion());
            db.update("INSERT INTO marketplace.site_shipping_policy(version,base_fee,free_threshold,actor_id,request_key,reason) VALUES(?,?,?,?,?,?)",
                    next,change.baseFee(),change.freeThreshold(),actor.member(),change.key(),change.reason().strip());
            db.update("UPDATE marketplace.site_shipping_head SET version=? WHERE singleton",next);
            return new Policy(next,change.baseFee(),change.freeThreshold());
        });
    }
    private static boolean money(Long value) { return value!=null&&value>=0&&value<=Integer.MAX_VALUE; }
}
