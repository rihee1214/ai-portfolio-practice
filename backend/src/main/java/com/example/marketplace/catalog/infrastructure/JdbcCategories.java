package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.Categories;
import com.example.marketplace.catalog.api.CatalogException;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

@Service @Profile("db")
public final class JdbcCategories implements Categories {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    public JdbcCategories(NamedParameterJdbcTemplate jdbc,AccessPolicy access,PlatformTransactionManager manager) { this.jdbc=jdbc; this.access=access; tx=new TransactionTemplate(manager); }
    @Override public Page list(UUID operator,int page) {
        if(operator!=null) { access.require(operator,AccessArea.CONTENT); }
        CatalogValues.page(page);
        var rows=jdbc.query("SELECT * FROM marketplace.catalog_category WHERE (:admin OR active) ORDER BY id LIMIT 21 OFFSET :offset",Map.of("admin",operator!=null,"offset",page*20),this::map);
        return new Page(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Override public Category save(UUID operator,UUID id,Input input) {
        access.require(operator,AccessArea.CONTENT);
        if(input==null||input.active()==null) { throw new CatalogException(400); }
        CatalogValues.version(input.version());
        String name=CatalogValues.text(input.name(),80,true);
        UUID target=id==null?UUID.randomUUID():id;
        var p=Map.of("id",target,"name",name,"active",input.active(),"version",input.version());
        try {
            return tx.execute(status->{
                access.require(operator,AccessArea.CONTENT);
                Category before=null;
                if(id==null) {
                    if(input.version()!=0) { throw new CatalogException(400); }
                    jdbc.update("INSERT INTO marketplace.catalog_category(id,name,active) VALUES(:id,:name,:active)",p);
                } else {
                    var rows=jdbc.query("SELECT * FROM marketplace.catalog_category WHERE id=:id FOR UPDATE",p,this::map);
                    access.require(operator,AccessArea.CONTENT);
                    if(rows.isEmpty()) { throw new CatalogException(404); }
                    if(rows.getFirst().version()!=input.version()) { throw new CatalogException(409); }
                    before=rows.getFirst();
                    jdbc.update("UPDATE marketplace.catalog_category SET name=:name,active=:active,version=version+1 WHERE id=:id",p);
                }
                var audit=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("event",UUID.randomUUID()).addValue("id",target).addValue("actor",operator)
                        .addValue("beforeName",before==null?null:before.name()).addValue("beforeActive",before==null?null:before.active())
                        .addValue("name",name).addValue("active",input.active());
                jdbc.update("INSERT INTO marketplace.category_event(id,category_id,actor_id,before_name,before_active,after_name,after_active) VALUES(:event,:id,:actor,:beforeName,:beforeActive,:name,:active)",audit);
                return jdbc.queryForObject("SELECT * FROM marketplace.catalog_category WHERE id=:id",p,this::map);
            });
        } catch(DuplicateKeyException conflict) { throw new CatalogException(409); }
    }
    private Category map(java.sql.ResultSet row,int index) throws java.sql.SQLException { return new Category(row.getObject("id",UUID.class),row.getString("name"),row.getBoolean("active"),row.getLong("version")); }
}
