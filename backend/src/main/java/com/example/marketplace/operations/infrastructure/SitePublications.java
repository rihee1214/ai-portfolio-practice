package com.example.marketplace.operations.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.media.api.SiteImages;
import com.example.marketplace.order.api.OrderRejected;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public class SitePublications {
    public enum Kind { NOTICE, FAQ, BANNER }
    public enum State { DRAFT, PUBLISHED, ARCHIVED }
    public record Actor(UUID member,String session,Long authVersion) { }
    public record Edit(UUID key,long expectedVersion,Kind kind,State state,String title,String body,String alt,
                       String destination,int priority,Instant startsAt,Instant endsAt,String reason) { }
    public record Post(UUID id,long version,Kind kind,State state,String title,String body,String alt,String destination,
                       int priority,Instant startsAt,Instant endsAt,UUID asset) { }
    public record Revision(Post post,UUID actor,String reason,Instant changedAt) { }
    private static final String FROM=" FROM marketplace.site_post h JOIN marketplace.site_post_revision r "
            +"ON (r.post_id,r.version)=(h.id,h.version) ";
    private static final String VISIBLE="marketplace.site_post_visible(r.state,r.starts_at,r.ends_at,statement_timestamp())";
    private final JdbcTemplate db;
    private final HistorySessions sessions;
    private final AccessPolicy access;
    private final org.springframework.beans.factory.ObjectProvider<SiteImages> images;
    private final TransactionTemplate tx;
    public SitePublications(NamedParameterJdbcTemplate jdbc,HistorySessions sessions,AccessPolicy access,org.springframework.beans.factory.ObjectProvider<SiteImages> images,PlatformTransactionManager manager) {
        this.db=jdbc.getJdbcTemplate();this.sessions=sessions;this.access=access;this.images=images;this.tx=new TransactionTemplate(manager);
    }
    void authorize(Actor a,AccessArea area) {
        if(a.member()==null||!sessions.lockMemberThenSession(a.session(),a.member(),a.authVersion())||!access.allowed(a.member(),area)) {
            throw new OrderRejected(403);
        }
    }
    public List<Post> list(Kind kind,int page,Actor actor) {
        if(page<0||page>100000) { throw new OrderRejected(400); }
        return tx.execute(status->{
            if(actor!=null) { authorize(actor,AccessArea.CONTENT); }
            return db.query("SELECT r.*"+FROM+" WHERE r.kind=? "+(actor==null?" AND "+VISIBLE:"")
                    +" ORDER BY r.priority,h.id LIMIT 30 OFFSET ?",SitePublications::map,kind.name(),page*30);
        });
    }
    public Post detail(UUID id,Actor actor) {
        return tx.execute(status->{if(actor!=null) { authorize(actor,AccessArea.CONTENT); }return find(id,actor==null);});
    }
    private Post find(UUID id,boolean publiclyVisible) {
        var rows=db.query("SELECT r.*"+FROM+" WHERE h.id=?"+(publiclyVisible?" AND "+VISIBLE:""),SitePublications::map,id);
        if(rows.isEmpty()) { throw new OrderRejected(404); }
        return rows.getFirst();
    }
    public byte[] image(UUID id,UUID asset,Actor actor) {
        return tx.execute(status->{
            if(actor!=null) { authorize(actor,AccessArea.CONTENT); }
            db.query("SELECT version FROM marketplace.site_post WHERE id=? FOR SHARE",(r,n)->r.getLong(1),id);
            if(!asset.equals(find(id,actor==null).asset())) { throw new OrderRejected(404); }
            return storage().read(asset);
        });
    }
    public List<Revision> history(UUID id,long before,Actor actor) {
        return tx.execute(status->{
            authorize(actor,AccessArea.CONTENT);
            return db.query("SELECT * FROM marketplace.site_post_revision WHERE post_id=? AND version<? ORDER BY version DESC LIMIT 30",
                    (r,n)->new Revision(map(r,n),r.getObject("actor_id",UUID.class),r.getString("reason"),instant(r,"changed_at")),id,before);
        });
    }
    public Post edit(UUID id,Edit c,String filename,byte[] bytes,Actor actor) {
        validate(c,filename,bytes);
        String fingerprint=digest(id,c,filename,bytes);
        return tx.execute(status->{
            authorize(actor,AccessArea.CONTENT);
            db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(r,n)->r.getObject(1),"site-post:"+id);
            authorize(actor,AccessArea.CONTENT);
            var receipts=db.query("SELECT * FROM marketplace.site_post_revision WHERE actor_id=? AND request_key=?",
                    (r,n)->{if(!fingerprint.equals(r.getString("fingerprint"))) { throw new OrderRejected(409); }return map(r,n);},actor.member(),c.key());
            if(!receipts.isEmpty()) { return receipts.getFirst(); }
            var old=db.query("SELECT r.*"+FROM+" WHERE h.id=? FOR UPDATE OF h",SitePublications::map,id);
            Post previous=old.isEmpty()?null:old.getFirst();
            long version=previous==null?0:previous.version();
            if(version!=c.expectedVersion()||previous!=null&&(previous.kind()!=c.kind()||previous.state()==State.ARCHIVED)) { throw new OrderRejected(409); }
            UUID asset=previous==null?null:previous.asset();
            if(bytes!=null) { asset=storage().save(actor.member(),filename,bytes); }
            Instant start=c.startsAt();
            if(c.state()==State.PUBLISHED&&start==null) { start=db.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
            if(c.endsAt()!=null&&start!=null&&!c.endsAt().isAfter(start)||c.state()==State.PUBLISHED&&c.kind()==Kind.BANNER&&asset==null) { throw new OrderRejected(400); }
            db.update("INSERT INTO marketplace.site_post_revision(post_id,version,kind,state,title,body,alt,destination,priority,starts_at,ends_at,asset_id,actor_id,request_key,fingerprint,reason) "
                    +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,version+1,c.kind().name(),c.state().name(),c.title().strip(),c.body(),c.alt(),c.destination(),c.priority(),
                    stamp(start),stamp(c.endsAt()),asset,actor.member(),c.key(),fingerprint,c.reason().strip());
            db.update("INSERT INTO marketplace.site_post(id,version) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version",id,version+1);
            return find(id,false);
        });
    }
    private static void validate(Edit c,String filename,byte[] bytes) {
        if(c==null||c.key()==null||c.kind()==null||c.state()==null||c.expectedVersion()<0||!text(c.title(),120)||!text(c.reason(),500)
                ||!plain(c.body(),10000)||!plain(c.alt(),300)
                ||c.startsAt()!=null&&c.startsAt().getNano()%1000!=0||c.endsAt()!=null&&c.endsAt().getNano()%1000!=0
                ||c.kind()!=Kind.BANNER&&(bytes!=null||!c.alt().isEmpty()||c.destination()!=null||c.body().isBlank())
                ||c.kind()==Kind.BANNER&&c.state()==State.PUBLISHED&&c.alt().isBlank()
                ||(filename==null)!=(bytes==null)||!safePath(c.destination())) { throw new OrderRejected(400); }
    }
    static boolean safePath(String value) {
        return value==null||value.matches("/(?:products|events|notices|stores)(?:/[A-Za-z0-9_-]{1,120})?|/faq|/");
    }
    private SiteImages storage() {
        var available=images.getIfAvailable();
        if(available==null) { throw new OrderRejected(503); }
        return available;
    }
    static boolean text(String value,int max) { return plain(value,max)&&!value.isBlank(); }
    private static boolean plain(String value,int max) {
        return value!=null&&value.codePoints().count()<=max&&value.codePoints().noneMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t');
    }
    private static Timestamp stamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static Instant instant(ResultSet r,String name) throws SQLException { var t=r.getTimestamp(name);return t==null?null:t.toInstant(); }
    private static Post map(ResultSet r,int n) throws SQLException {
        return new Post(r.getObject("post_id",UUID.class),r.getLong("version"),Kind.valueOf(r.getString("kind")),State.valueOf(r.getString("state")),
                r.getString("title"),r.getString("body"),r.getString("alt"),r.getString("destination"),r.getInt("priority"),instant(r,"starts_at"),
                instant(r,"ends_at"),r.getObject("asset_id",UUID.class));
    }
    private static String digest(UUID id,Edit c,String filename,byte[] bytes) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(Object v:new Object[]{id,c.expectedVersion(),c.kind(),c.state(),c.title().strip(),c.body(),c.alt(),c.destination(),c.priority(),
                    c.startsAt(),c.endsAt(),c.reason().strip(),filename}) {
                byte[] field=v==null?null:v.toString().getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(field==null?-1:field.length).array());
                if(field!=null) { hash.update(field); }
            }
            if(bytes!=null) { hash.update(bytes); }
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
