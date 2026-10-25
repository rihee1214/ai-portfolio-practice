package com.example.marketplace.media.infrastructure;

import com.example.marketplace.media.api.Media;
import com.example.marketplace.media.api.MediaException;
import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.seller.api.Stores;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="marketplace.media.enabled",matchIfMissing=true)
public final class JdbcMedia implements Media {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(JdbcMedia.class);
    private static final String PROJECTION="""
            SELECT m.slot,m.asset_id,m.alt,m.version,a.width,a.height FROM marketplace.media_slot m
            JOIN marketplace.media_asset a ON a.id=m.asset_id AND a.retired_at IS NULL
            LEFT JOIN marketplace.catalog_product p ON p.id=m.product_id
            JOIN marketplace.seller_store s ON s.id=coalesce(m.store_id,p.store_id)
            JOIN marketplace.identity_user u ON u.id=s.owner_id
            LEFT JOIN marketplace.catalog_category c ON c.id=p.category_id
            WHERE u.state='ACTIVE' AND EXISTS(SELECT 1 FROM marketplace.role_assignment r WHERE r.user_id=u.id AND r.role='SELLER')
            """;
    private static final String PUBLIC="s.state='OPEN' AND s.visible AND (m.kind='stores' OR (p.state='ACTIVE' AND NOT p.is_hidden_by_admin AND c.active))";
    private final NamedParameterJdbcTemplate jdbc;
    private final Products products;
    private final Stores stores;
    private final LocalImages files;
    private final ImageNormalizer codec;
    private final TransactionTemplate tx;
    public JdbcMedia(NamedParameterJdbcTemplate jdbc,Products products,Stores stores,LocalImages files,ImageNormalizer codec,PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.products=products; this.stores=stores; this.files=files; this.codec=codec; tx=new TransactionTemplate(manager);
    }
    private int slots(String kind) { if("products".equals(kind)) { return 5; } if("stores".equals(kind)) { return 1; } throw new MediaException(404); }
    private void own(UUID owner,String kind,UUID target,boolean lock) {
        slots(kind);
        if("products".equals(kind)) { if(lock) { products.lockForMedia(owner,target); } else { products.own(owner,target); } }
        else {
            if(lock) { stores.requireCapability(owner,Stores.Capability.MANAGE_CATALOG); }
            var store=stores.own(owner); if(store==null||!store.id().equals(target)) { throw new MediaException(404); }
        }
    }
    @Override public List<Slot> list(UUID owner,String kind,UUID target) { own(owner,kind,target,false); return rows(kind,target); }
    private List<Slot> rows(String kind,UUID target) {
        var rows=jdbc.query("SELECT m.*,a.width,a.height FROM marketplace.media_slot m LEFT JOIN marketplace.media_asset a ON a.id=m.asset_id WHERE m.kind=:kind AND m.target_id=:target ORDER BY slot",Map.of("kind",kind,"target",target),this::map);
        var all=new java.util.ArrayList<Slot>();
        for(int i=0;i<slots(kind);i++) { int slot=i; all.add(rows.stream().filter(row->row.slot()==slot).findFirst().orElse(new Slot(slot,null,"",0,0,0))); }
        return List.copyOf(all);
    }
    @Override public List<Slot> published(String kind,String slug) {
        slots(kind);
        // Eligibility of empty targets is checked through the owning module; binary access still has a single snapshot.
        if("products".equals(kind)) { products.published(slug); } else { stores.published(slug); }
        return jdbc.query(PROJECTION+" AND "+PUBLIC+" AND m.kind=:kind AND "+("products".equals(kind)?"p":"s")+".slug=:slug ORDER BY m.slot",Map.of("kind",kind,"slug",slug),this::map);
    }
    @Override public byte[] content(UUID viewer,UUID asset) {
        // Obtain the pooled connection before taking the filesystem monitor.
        return tx.execute(status->{
        synchronized(files) {
            var args=new MapSqlParameterSource().addValue("asset",asset).addValue("viewer",viewer);
            var allowed=jdbc.query(PROJECTION+" AND a.id=:asset AND (a.owner_id=:viewer OR ("+PUBLIC+"))",args,this::map);
            if(allowed.isEmpty()) { throw new MediaException(404); }
            try { return files.read(asset); } catch(java.io.IOException missing) { throw new MediaException(404); }
        }
        });
    }
    @Override public Slot upload(UUID owner,String kind,UUID target,int slot,long version,String alt,String filename,byte[] bytes) {
        own(owner,kind,target,false); validate(kind,slot,version);
        if(alt==null) { throw new MediaException(400); }
        String label=alt.strip();
        if(label.isEmpty()||label.codePointCount(0,label.length())>120||label.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF)) { throw new MediaException(400); }
        var image=codec.normalize(filename,bytes);
        return change(owner,kind,target,slot,version,label,image);
    }
    @Override public Slot delete(UUID owner,String kind,UUID target,int slot,long version) {
        own(owner,kind,target,false); validate(kind,slot,version); return change(owner,kind,target,slot,version,"",null);
    }
    private void validate(String kind,int slot,long version) { if(slot<0||slot>=slots(kind)||version<0||version>9007199254740990L) { throw new MediaException(400); } }
    private Slot change(UUID owner,String kind,UUID target,int slot,long version,String alt,ImageNormalizer.Image image) {
        // Acquire database locks before the short filesystem monitor, including REQUIRED callers.
        return tx.execute(status->{
                own(owner,kind,target,true);
                Slot old=rows(kind,target).get(slot);
                if(old.version()!=version||image==null&&old.assetId()==null) { throw new MediaException(409); }
                UUID asset=image==null?null:UUID.randomUUID();
                var p=new MapSqlParameterSource().addValue("kind",kind).addValue("target",target).addValue("slot",slot).addValue("asset",asset).addValue("alt",alt).addValue("version",version+1)
                        .addValue("product","products".equals(kind)?target:null).addValue("store","stores".equals(kind)?target:null);
                if(image!=null) {
                    synchronized(files) { files.inFlight.add(asset); }
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCompletion(int completion) {
                            synchronized(files) {
                                try { if(completion!=STATUS_COMMITTED) { files.remove(asset); } }
                                catch(java.io.IOException failure) { LOG.warn("Media rollback cleanup deferred"); }
                                finally { files.inFlight.remove(asset); }
                            }
                        }
                    });
                    synchronized(files) {
                        try { files.write(asset,image.png()); } catch(java.io.IOException failure) { throw new MediaException(500); }
                    }
                    p.addValue("owner",owner).addValue("width",image.width()).addValue("height",image.height()).addValue("size",image.png().length);
                    jdbc.update("INSERT INTO marketplace.media_asset(id,owner_id,width,height,byte_size) VALUES(:asset,:owner,:width,:height,:size)",p);
                }
                if(version==0) { jdbc.update("INSERT INTO marketplace.media_slot(kind,product_id,store_id,slot,asset_id,alt,version) VALUES(:kind,:product,:store,:slot,:asset,:alt,:version)",p); }
                else { jdbc.update("UPDATE marketplace.media_slot SET asset_id=:asset,alt=:alt,version=:version WHERE kind=:kind AND target_id=:target AND slot=:slot",p); }
                if(old.assetId()!=null) { jdbc.update("UPDATE marketplace.media_asset SET retired_at=clock_timestamp() WHERE id=:old",Map.of("old",old.assetId())); }
                return new Slot(slot,asset,alt,version+1,image==null?0:image.width(),image==null?0:image.height());
            });
    }
    @org.springframework.scheduling.annotation.Scheduled(initialDelay=3600000,fixedDelay=3600000)
    public void cleanup() {
        var cutoff=java.time.Instant.now().minus(java.time.Duration.ofHours(24));
        tx.executeWithoutResult(status->{
            var ids=jdbc.queryForList("SELECT id FROM marketplace.media_asset a WHERE retired_at<:cutoff AND NOT EXISTS(SELECT 1 FROM marketplace.media_slot m WHERE m.asset_id=a.id) FOR UPDATE SKIP LOCKED",Map.of("cutoff",java.sql.Timestamp.from(cutoff)),UUID.class);
            for(UUID id:ids) {
                synchronized(files) {
                    if(files.inFlight.contains(id)) { continue; }
                    try { files.remove(id); } catch(java.io.IOException failure) { LOG.warn("Retired media cleanup deferred"); continue; }
                }
                jdbc.update("DELETE FROM marketplace.media_asset WHERE id=:id",Map.of("id",id));
            }
        });
        try {
            List<UUID> candidates;
            synchronized(files) { candidates=files.oldFiles(cutoff); }
            for(UUID id:candidates) {
                tx.executeWithoutResult(status->{
                synchronized(files) {
                    // Plain MVCC lookup never takes a row lock. Recheck under the monitor against commit callbacks.
                    if(!files.inFlight.contains(id)&&jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE id=:id",Map.of("id",id),Integer.class)==0) {
                        try { files.remove(id); } catch(java.io.IOException failure) { LOG.warn("Orphan media cleanup deferred"); }
                    }
                }
                });
            }
        } catch(java.io.IOException failure) { LOG.warn("Media root scan deferred"); }
    }
    private Slot map(java.sql.ResultSet r,int i) throws java.sql.SQLException { return new Slot(r.getInt("slot"),r.getObject("asset_id",UUID.class),r.getString("alt"),r.getLong("version"),r.getInt("width"),r.getInt("height")); }
}
