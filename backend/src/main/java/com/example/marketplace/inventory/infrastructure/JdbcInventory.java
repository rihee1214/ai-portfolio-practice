package com.example.marketplace.inventory.infrastructure;

import com.example.marketplace.inventory.api.Inventory;
import com.example.marketplace.inventory.api.InventoryException;
import com.example.marketplace.catalog.api.Skus;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcInventory implements Inventory {
    private final NamedParameterJdbcTemplate jdbc;
    private final Skus skus;
    private final TransactionTemplate tx;
    private static final String BALANCE="SELECT k.id,k.code,coalesce(b.on_hand,0) on_hand,coalesce(b.reserved,0) reserved,coalesce(b.version,0) version FROM marketplace.catalog_sku k LEFT JOIN marketplace.inventory_balance b ON b.sku_id=k.id ";
    public JdbcInventory(NamedParameterJdbcTemplate jdbc,Skus skus,PlatformTransactionManager manager) { this.jdbc=jdbc;this.skus=skus;tx=new TransactionTemplate(manager); }
    private void page(int page) { if(page<0||page>10000) { throw new InventoryException(400); } }
    @Override public Page<Balance> list(UUID actor,UUID product,boolean admin,int page) {
        skus.inventoryAccess(actor,product,null,admin,false); page(page);
        var rows=jdbc.query(BALANCE+"WHERE k.product_id=:product ORDER BY k.id LIMIT 21 OFFSET :offset",Map.of("product",product,"offset",page*20),this::balance);
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Page<Movement> history(UUID actor,UUID product,UUID sku,boolean admin,int page) {
        skus.inventoryAccess(actor,product,sku,admin,false);page(page);
        var rows=jdbc.query("SELECT * FROM marketplace.stock_movement WHERE sku_id=:sku ORDER BY version DESC LIMIT 21 OFFSET :offset",Map.of("sku",sku,"offset",page*20),this::movement);
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Balance adjust(UUID actor,UUID product,UUID sku,boolean admin,Change change) {
        skus.inventoryAccess(actor,product,sku,admin,false);
        if(change==null||change.onHand()==null||change.onHand()<0||change.onHand()>999999999||change.version()==null||change.version()<0||change.version()>9007199254740990L||change.requestId()==null||change.reason()==null) { throw new InventoryException(400); }
        String reason=change.reason().strip();
        if(reason.isEmpty()||reason.codePointCount(0,reason.length())>200||reason.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF)) { throw new InventoryException(400); }
        try {
            return tx.execute(status->{
                skus.inventoryAccess(actor,product,sku,admin,true);
                var p=new MapSqlParameterSource().addValue("sku",sku).addValue("actor",actor).addValue("request",change.requestId()).addValue("quantity",change.onHand()).addValue("expected",change.version()).addValue("reason",reason);
                var repeated=jdbc.query("SELECT m.*,k.code FROM marketplace.stock_movement m JOIN marketplace.catalog_sku k ON k.id=m.sku_id WHERE request_id=:request",p,(r,i)-> {
                    if(!actor.equals(r.getObject("actor_id",UUID.class))||!sku.equals(r.getObject("sku_id",UUID.class))||r.getLong("after_on_hand")!=change.onHand()||r.getLong("before_version")!=change.version()||!reason.equals(r.getString("reason"))) { throw new InventoryException(409); }
                    long quantity=r.getLong("after_on_hand"),reserved=r.getLong("reserved"); return new Balance(sku,r.getString("code"),quantity,reserved,quantity-reserved,r.getLong("version"));
                });
                if(!repeated.isEmpty()) { return repeated.getFirst(); }
                jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id) VALUES(:sku) ON CONFLICT DO NOTHING",p);
                jdbc.queryForObject("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",p,UUID.class);
                var before=jdbc.queryForObject(BALANCE+"WHERE k.id=:sku",p,this::balance);
                if(before.version()!=change.version()||before.onHand()==change.onHand()||change.onHand()<before.reserved()) { throw new InventoryException(409); }
                p.addValue("before",before.onHand()).addValue("reserved",before.reserved());
                int changed=jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=:quantity,version=version+1 WHERE sku_id=:sku AND version=:expected AND reserved<=:quantity",p);
                if(changed!=1) { throw new InventoryException(409); }
                jdbc.update("INSERT INTO marketplace.stock_movement(request_id,sku_id,actor_id,cause,reason,before_on_hand,after_on_hand,reserved,before_version,version) VALUES(:request,:sku,:actor,'MANUAL_ADJUST',:reason,:before,:quantity,:reserved,:expected,:expected+1)",p);
                return jdbc.queryForObject(BALANCE+"WHERE k.id=:sku",p,this::balance);
            });
        } catch(org.springframework.dao.DuplicateKeyException conflict) { throw new InventoryException(409); }
    }
    private Balance balance(java.sql.ResultSet r,int index) throws java.sql.SQLException { long quantity=r.getLong("on_hand"),reserved=r.getLong("reserved");return new Balance(r.getObject("id",UUID.class),r.getString("code"),quantity,reserved,quantity-reserved,r.getLong("version")); }
    private Movement movement(java.sql.ResultSet r,int index) throws java.sql.SQLException { return new Movement(r.getObject("request_id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("cause"),r.getString("reason"),r.getLong("before_on_hand"),r.getLong("after_on_hand"),r.getLong("reserved"),r.getLong("version"),r.getTimestamp("occurred_at").toInstant()); }
}
