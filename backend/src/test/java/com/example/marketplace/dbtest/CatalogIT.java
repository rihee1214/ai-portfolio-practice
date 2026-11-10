package com.example.marketplace.dbtest;


import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogIT extends PostgresContractFixture {
    @Test
    void browseSearchFiltersSortAndPages() throws Exception {
        var f=browseFixture("pages",100,10);var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");UUID owner=(UUID)f.get("owner");
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);
        for(int i=1;i<=21;i++) {
            var p=products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"p05-page-"+i,"P05 pages "+i,"page fixture","DRAFT",0L));
            products.save(owner,p.id(),new com.example.marketplace.catalog.api.Products.Input(p.categoryId(),p.slug(),p.title(),p.description(),"ACTIVE",0L));
            var sku=skus.list(owner,p.id(),0).items().getFirst();skus.save(owner,p.id(),sku.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),100L,true,0L));
        }
        var client=authBrowser();String base="/api/browse/products?q=P05+pages&sort=price_asc";
        var response=accountRequest(client,"GET",base,null,null);assertEquals(200,response.statusCode(),response.body());
        var first=authMapper.readTree(response.body());var next=authMapper.readTree(accountRequest(client,"GET",base+"&page=1",null,null).body());
        assertEquals(20,first.get("items").size());assertTrue(first.get("hasMore").asBoolean());assertEquals(2,next.get("items").size());assertFalse(next.get("hasMore").asBoolean());
        var slugs=new java.util.HashSet<String>();first.get("items").forEach(v->assertTrue(slugs.add(v.get("slug").asText())));next.get("items").forEach(v->assertTrue(slugs.add(v.get("slug").asText())));
        assertEquals(response.body(),accountRequest(client,"GET",base,null,null).body());
        String store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner).slug();
        var filtered=authMapper.readTree(accountRequest(client,"GET",base+"&category="+product.categoryId()+"&store="+store+"&inStock=true&minPrice=100&maxPrice=100",null,null).body());
        assertEquals(1,filtered.get("items").size());assertEquals(product.slug(),filtered.get("items").get(0).get("slug").asText());
        assertEquals(0,authMapper.readTree(accountRequest(client,"GET",base+"&minPrice=101",null,null).body()).get("items").size());
        jdbc.update("UPDATE marketplace.catalog_sku SET price=200 WHERE id=:id",Map.of("id",f.get("sku")));
        jdbc.update("UPDATE marketplace.catalog_product SET created_at='2099-01-01T00:00:00Z',title='P05 pages 000' WHERE id=:id",Map.of("id",product.id()));
        for(String sort:List.of("price_desc","newest","name")) {
            var sorted=authMapper.readTree(accountRequest(client,"GET","/api/browse/products?q=P05+pages&sort="+sort,null,null).body());
            assertEquals(product.slug(),sorted.get("items").get(0).get("slug").asText(),sort);
        }
        assertFalse(authMapper.readTree(accountRequest(client,"GET",base,null,null).body()).get("items").get(0).get("slug").asText().equals(product.slug()));
        jdbc.update("UPDATE marketplace.catalog_product SET title='P05 literal %_!' WHERE id=:id",Map.of("id",product.id()));
        assertEquals(1,authMapper.readTree(accountRequest(client,"GET","/api/browse/products?q=%25_%21",null,null).body()).get("items").size());
    }

    @Test
    void browseVisibilityAndDetailAvailability() throws Exception {
        var f=browseFixture("visibility",1700,10);var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku");
        jdbc.update("UPDATE marketplace.inventory_balance SET reserved=4 WHERE sku_id=:id",Map.of("id",sku));
        var client=authBrowser();String path="/api/browse/products/"+p.slug();var response=accountRequest(client,"GET",path,null,null);assertEquals(200,response.statusCode(),response.body());
        var detail=authMapper.readTree(response.body());assertEquals(1700,detail.get("product").get("minPrice").asLong());assertFalse(detail.get("product").get("soldOut").asBoolean());assertEquals(6,detail.get("offers").get(0).get("available").asLong());
        String[] hide={"UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id","UPDATE marketplace.catalog_product SET state='STOPPED' WHERE id=:id","UPDATE marketplace.catalog_category SET active=false WHERE id=:category","UPDATE marketplace.seller_store SET visible=false WHERE owner_id=:owner","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE owner_id=:owner","UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:owner","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'"};
        for(String sql:hide) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.update(sql,Map.of("id",p.id(),"category",p.categoryId(),"owner",owner));
                var api=context.getBean(com.example.marketplace.catalog.api.Browse.class);
                assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->api.detail(p.slug())).status());
                assertTrue(api.search(new com.example.marketplace.catalog.api.Browse.Query("P05 visibility",null,null,null,null,false,"newest",0)).items().isEmpty());status.setRollbackOnly();
            });
        }
        jdbc.update("UPDATE marketplace.inventory_balance SET reserved=on_hand WHERE sku_id=:id",Map.of("id",sku));
        assertTrue(authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get("product").get("soldOut").asBoolean());
        jdbc.update("UPDATE marketplace.catalog_sku SET active=false WHERE id=:id",Map.of("id",sku));
        detail=authMapper.readTree(accountRequest(client,"GET",path,null,null).body());assertTrue(detail.get("product").get("minPrice").isNull());assertEquals(0,detail.get("offers").size());
    }

    @Test
    void browseRejectsInvalidQueriesAndIsReadOnly() throws Exception {
        var f=browseFixture("invalid",100,2);var client=authBrowser();
        for(String query:List.of("page=-1","page=10001","page=abc","minPrice=-1","maxPrice=1000000000","minPrice=20&maxPrice=10","sort=random","category=no","store=BAD","q=%00","q="+"a".repeat(81),"inStock=maybe")) {
            assertEquals(400,accountRequest(client,"GET","/api/browse/products?"+query,null,null).statusCode(),query);
        }
        assertEquals(404,accountRequest(client,"GET","/api/browse/products/does-not-exist",null,null).statusCode());
        assertEquals(403,accountRequest(client,"POST","/api/browse/products",Map.of(),null).statusCode());
        stockEquals((UUID)f.get("sku"),2,0,0);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE sku_id=:id",Map.of("id",f.get("sku")),Integer.class));
    }

    @Test
    void browseDistinctFiltersSortAndOptionPrices() {
        var a=browseFixture("contract-a",1,0);var b=browseFixture("contract-z",300,0);var c=browseFixture("contract-m",1,0);
        var pa=(com.example.marketplace.catalog.api.Products.Product)a.get("product");var pb=(com.example.marketplace.catalog.api.Products.Product)b.get("product");var pc=(com.example.marketplace.catalog.api.Products.Product)c.get("product");
        jdbc.update("UPDATE marketplace.catalog_sku SET active=false WHERE id IN (:a,:c)",Map.of("a",a.get("sku"),"c",c.get("sku")));
        UUID cheap=UUID.randomUUID(),stocked=UUID.randomUUID();
        for(UUID id:List.of(cheap,stocked)) {
            jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,options,price,active) VALUES(:id,:product,:code,CAST(:options AS jsonb),:price,true)",Map.of("id",id,"product",pa.id(),"code","sku-"+id,"options","[{\"name\":\"색\",\"value\":\""+(id.equals(cheap)?"파랑":"빨강")+"\"}]","price",id.equals(cheap)?100:500));
            jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand,reserved) VALUES(:id,:stock,:reserved)",Map.of("id",id,"stock",id.equals(cheap)?0:5,"reserved",id.equals(cheap)?0:2));
        }
        jdbc.update("UPDATE marketplace.catalog_product SET created_at=CAST(:time AS timestamptz) WHERE id=:id",Map.of("id",pa.id(),"time","2020-01-01T00:00:00Z"));
        jdbc.update("UPDATE marketplace.catalog_product SET created_at=CAST(:time AS timestamptz) WHERE id=:id",Map.of("id",pb.id(),"time","2022-01-01T00:00:00Z"));
        jdbc.update("UPDATE marketplace.catalog_product SET created_at=CAST(:time AS timestamptz) WHERE id=:id",Map.of("id",pc.id(),"time","2021-01-01T00:00:00Z"));
        var api=context.getBean(com.example.marketplace.catalog.api.Browse.class);
        var orders=Map.of("price_asc",List.of(pa.slug(),pb.slug(),pc.slug()),"price_desc",List.of(pb.slug(),pa.slug(),pc.slug()),"name",List.of(pa.slug(),pc.slug(),pb.slug()),"newest",List.of(pb.slug(),pc.slug(),pa.slug()));
        orders.forEach((sort,expected)->assertEquals(expected,api.search(new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,null,null,false,sort,0)).items().stream().map(com.example.marketplace.catalog.api.Browse.Item::slug).toList(),sort));
        String store=context.getBean(com.example.marketplace.seller.api.Stores.class).own((UUID)b.get("owner")).slug();
        var filters=List.of(new com.example.marketplace.catalog.api.Browse.Query("P05 contract",pa.categoryId(),null,null,null,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,store,null,null,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,150L,null,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,null,150L,false,"newest",0),new com.example.marketplace.catalog.api.Browse.Query("P05 contract",null,null,null,null,true,"newest",0));
        var expected=List.of(pa.slug(),pb.slug(),pb.slug(),pa.slug(),pa.slug());
        for(int i=0;i<filters.size();i++) { assertEquals(List.of(expected.get(i)),api.search(filters.get(i)).items().stream().map(com.example.marketplace.catalog.api.Browse.Item::slug).toList(),"filter "+i); }
        var detail=api.detail(pa.slug());assertEquals(100L,detail.product().minPrice());assertFalse(detail.product().soldOut());assertEquals(2,detail.offers().size());
        assertEquals(0,detail.offers().stream().filter(o->o.id().equals(cheap)).findFirst().orElseThrow().available());
        assertEquals(3,detail.offers().stream().filter(o->o.id().equals(stocked)).findFirst().orElseThrow().available());
        assertTrue(detail.offers().stream().noneMatch(o->o.id().equals(a.get("sku"))));
    }

    @Test
    void skuDefaultTransitionIsAtomicAndSerializedWithActivation() throws Exception {
        var f=skuFixture("mode"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class); var basic=skus.list(owner,product.id(),0).items().getFirst();
        skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),0L,true,0L));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku ADD CONSTRAINT p4b_fail_variant CHECK(options='[]') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("종류","실패")),1L,true,0L)));
            var retained=skus.list(owner,product.id(),0).items(); assertEquals(1,retained.size()); assertTrue(retained.getFirst().active()); assertEquals(1,retained.getFirst().version());
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku DROP CONSTRAINT p4b_fail_variant"); }
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        race(()->{ try { if(sequence.incrementAndGet()==1) { skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),0L,true,1L)); }
                else { skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("종류","성공")),1L,true,0L)); } return "ok";
            } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        var rows=skus.list(owner,product.id(),0).items(); assertEquals(2,rows.size()); assertFalse(rows.stream().filter(row->row.options().isEmpty()).findFirst().orElseThrow().active()); assertEquals(1,rows.stream().filter(row->row.active()).count());
    }

    @Test
    void skuWritesRecheckSuspensionAndArchiveAfterActualLockWait() throws Exception {
        for(boolean member:List.of(true,false)) {
            String suffix=member?"wait-member":"wait-product";
            var f=skuFixture(suffix); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
            var basic=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
            var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-"+suffix+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
            String csrf=csrfToken(client); String table=member?"identity_user":"catalog_product"; UUID lock=member?owner:product.id();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);
                    future.set(executor.submit(()->accountRequest(client,"PUT","/api/seller/products/"+product.id()+"/skus/"+basic.id(),Map.of("options",List.of(),"price",100,"active",true,"version",0),csrf)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+(member?"%FOR NO KEY UPDATE%":"%FOR UPDATE%")),Boolean.class));
                        if(waiting) { break; }
                        try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertTrue(waiting,"SKU request must wait on "+table);
                    jdbc.update("UPDATE marketplace."+table+" SET state=:state WHERE id=:id",Map.of("id",lock,"state",member?"SUSPENDED":"ARCHIVED"));
                });
                var response=future.get().get(5,TimeUnit.SECONDS); assertEquals(409,response.statusCode(),response.body());
                assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE id=:id AND price=0 AND NOT active AND version=0",Map.of("id",basic.id()),Integer.class));
            }
        }
    }

    @Test
    void skuConcurrentChangesDuplicatesAndCapacityHaveOneWinner() throws Exception {
        var f=skuFixture("race"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class); var basic=skus.list(owner,product.id(),0).items().getFirst();
        var updated=race(()->{ try { skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),1L,true,0L)); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,updated.stream().filter("ok"::equals).count());
        var options=List.of(new com.example.marketplace.catalog.api.Skus.Option("색상","빨강"));
        var created=race(()->{ try { skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(options,0L,true,0L)); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count());
        assertEquals(2,jdbc.queryForObject("SELECT version FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",basic.id()),Integer.class));
        for(int i=0;i<97;i++) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO marketplace.catalog_sku(id,product_id,code,options) VALUES(:id,:product,:code,CAST(:options AS jsonb))",Map.of("id",id,"product",product.id(),"code","sku-"+id,"options","[{\"name\":\"n\",\"value\":\""+i+"\"}]")); }
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var capacity=race(()->{ try { skus.save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("limit",String.valueOf(sequence.incrementAndGet()))),1L,false,0L)); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,capacity.stream().filter("ok"::equals).count());
        assertEquals(100,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_sku WHERE product_id=:id",Map.of("id",product.id()),Integer.class));
        var all=new java.util.HashSet<UUID>(); for(int page=0;page<5;page++) { var rows=skus.list(owner,product.id(),page); assertEquals(page<4,rows.hasMore()); rows.items().forEach(row->assertTrue(all.add(row.id()))); } assertEquals(100,all.size());
    }

    @Test
    void skuHttpTypesAndPublicFiltersRemainStrict() throws Exception {
        var f=skuFixture("public"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class); var basic=skus.list(owner,product.id(),0).items().getFirst();
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-public@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String csrf=csrfToken(client), path="/api/seller/products/"+product.id()+"/skus/"+basic.id();
        for(Object price:List.of("10",true,new BigDecimal("1.0"))) { assertEquals(400,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",price,"active",true,"version",0),csrf).statusCode()); }
        var invalid=new java.util.HashMap<String,Object>(Map.of("options",List.of(),"active",true,"version",0)); invalid.put("price",null);
        assertEquals(400,accountRequest(client,"PUT",path,invalid,csrf).statusCode());
        assertEquals(400,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",10,"active","true","version",0),csrf).statusCode());
        assertEquals(400,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",10,"active",true,"version",new BigDecimal("0.1")),csrf).statusCode());
        skus.save(owner,product.id(),basic.id(),new com.example.marketplace.catalog.api.Skus.Input(List.of(),0L,true,0L));
        context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        var guest=authBrowser(); String url="/api/products/"+product.slug()+"/skus";
        assertEquals(1,authMapper.readTree(accountRequest(guest,"GET",url,null,null).body()).get("items").size());
        jdbc.update("UPDATE marketplace.catalog_category SET active=false WHERE id=:id",Map.of("id",product.categoryId())); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("UPDATE marketplace.catalog_category SET active=true WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product.id())); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=false WHERE id=:id",Map.of("id",product.id()));
        jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE owner_id=:id",Map.of("id",owner)); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("UPDATE marketplace.seller_store SET visible=true WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("UPDATE marketplace.seller_store SET visible=false,state='SUSPENDED' WHERE owner_id=:id",Map.of("id",owner));
        assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path,Map.of("options",List.of(),"price",10,"active",true,"version",1),csrf).statusCode());
        jdbc.update("UPDATE marketplace.seller_store SET visible=true,state='OPEN' WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",owner)); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner)); assertEquals(404,accountRequest(guest,"GET",url,null,null).statusCode());
    }

    @Test
    void skuOptionsPricesOwnershipAndVersionsAreEnforcedOverHttp() throws Exception {
        var f=skuFixture("rules"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-rules@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String csrf=csrfToken(client), path="/api/seller/products/"+product.id()+"/skus";
        var basic=authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get("items").get(0);
        var enabled=accountRequest(client,"PUT",path+"/"+basic.get("id").asText(),Map.of("options",List.of(),"price",100,"active",true,"version",0),csrf);
        assertEquals(200,enabled.statusCode(),enabled.body());
        var opts=List.of(Map.of("name"," 크기 ","value"," M "),Map.of("name","색상","value","빨강"));
        var input=Map.of("options",opts,"price",2500,"active",true,"version",0);
        assertEquals(403,accountRequest(client,"POST",path,input,null).statusCode());
        var created=accountRequest(client,"POST",path,input,csrf); assertEquals(201,created.statusCode(),created.body());
        var sku=authMapper.readTree(created.body()); String id=sku.get("id").asText();
        var rows=authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get("items");
        assertEquals(2,rows.size()); assertEquals(1,java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(row->row.get("active").asBoolean()).count());
        var normalized=List.of(Map.of("name","색상","value","빨강"),Map.of("name","크기","value","M"));
        assertEquals(409,accountRequest(client,"POST",path,Map.of("options",normalized,"price",1,"active",false,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"POST",path,Map.of("options",List.of(),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(201,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","café","value","é")),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","cafe\u0301","value","e\u0301")),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","x".repeat(41),"value","v")),"price",0,"active",false,"version",0),csrf).statusCode());
        assertEquals(201,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","문자경계","value","😀".repeat(40))),"price",0,"active",false,"version",0),csrf).statusCode());
        for(String invalid:List.of("😀".repeat(41),"a\nb")) {
            assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","문자경계","value",invalid)),"price",0,"active",false,"version",0),csrf).statusCode());
        }
        assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","a","value","1"),Map.of("name","b","value","2"),Map.of("name","c","value","3"),Map.of("name","d","value","4")),"price",0,"active",false,"version",0),csrf).statusCode());
        for(Object price:List.of(-1,new BigDecimal("1.5"),1000000000L)) {
            assertEquals(400,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",price,"active",true,"version",0),csrf).statusCode());
        }
        assertEquals(400,accountRequest(client,"POST",path,Map.of("options",List.of(Map.of("name","색상","value","a"),Map.of("name"," 색상 ","value","b")),"price",1,"active",true,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+basic.get("id").asText(),Map.of("options",List.of(),"price",0,"active",true,"version",2),csrf).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+id,Map.of("options",List.of(Map.of("name","색상","value","변경")),"price",0,"active",true,"version",0),csrf).statusCode());
        assertEquals(200,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",999999999,"active",false,"version",0),csrf).statusCode());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",0,"active",false,"version",0),csrf).statusCode());
        var other=skuFixture("foreign"); var outsider=authBrowser(); assertEquals(200,authRequest(outsider,"/api/auth/login",loginForm("p4b-foreign@example.com",SIGNUP_PASSWORD),csrfToken(outsider)).statusCode());
        assertEquals(404,accountRequest(outsider,"GET",path,null,null).statusCode());
        assertEquals(404,accountRequest(outsider,"POST",path,input,csrfToken(outsider)).statusCode());
        String otherPath="/api/seller/products/"+((com.example.marketplace.catalog.api.Products.Product)other.get("product")).id()+"/skus/"+id;
        assertEquals(404,accountRequest(outsider,"PUT",otherPath,Map.of("options",normalized,"price",1,"active",true,"version",1),csrfToken(outsider)).statusCode());
        jdbc.update("UPDATE marketplace.catalog_product SET state='ARCHIVED' WHERE id=:id",Map.of("id",product.id()));
        assertEquals(409,accountRequest(client,"PUT",path+"/"+id,Map.of("options",normalized,"price",0,"active",false,"version",1),csrf).statusCode());
    }

    @Test
    void productCreatesOneInactiveDefaultSkuWithPublicEligibility() throws Exception {
        var fixture=skuFixture("default"); var owner=(UUID)fixture.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)fixture.get("product");
        var client=authBrowser();
        assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-default@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        var response=accountRequest(client,"GET","/api/seller/products/"+product.id()+"/skus",null,null);
        assertEquals(200,response.statusCode(),response.body());
        var items=authMapper.readTree(response.body()).get("items");
        assertEquals(1,items.size()); assertEquals(0,items.get(0).get("price").asLong());
        assertFalse(items.get(0).get("active").asBoolean()); assertEquals(0,items.get(0).get("options").size());
        var code=items.get(0).get("code").asText(); assertTrue(code.startsWith("sku-"));
        assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/"+product.slug()+"/skus",null,null).statusCode());
        context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        var visible=accountRequest(authBrowser(),"GET","/api/products/"+product.slug()+"/skus",null,null);
        assertEquals(200,visible.statusCode()); assertEquals(0,authMapper.readTree(visible.body()).get("items").size());
    }

    @Test
    void storeHistoryKeepsCausalOrderWhenRecordedClockMovesBackwardOrTies() {
        UUID owner=activeMember("p4-history-owner@example.com"), admin=activeMember("p4-history-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        try {
            var store=stores.save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-history","P04","intro","address","","","",true,0L));
            stores.transition(admin,store.id(),"suspend",new com.example.marketplace.seller.api.Stores.Change(0L,"suspend"));
            stores.transition(admin,store.id(),"restore",new com.example.marketplace.seller.api.Stores.Change(1L,"restore"));
            stores.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(2L,"close"));
            jdbc.update("UPDATE marketplace.store_operation_event SET occurred_at='2000-01-01T00:00:00Z' WHERE store_id=:id AND action='close'",Map.of("id",store.id()));
            assertEquals(List.of("close","restore","suspend"),stores.history(owner,store.id(),false).stream().map(com.example.marketplace.seller.api.Stores.Event::action).toList());
            jdbc.update("UPDATE marketplace.store_operation_event SET occurred_at='2000-01-01T00:00:00Z' WHERE store_id=:id",Map.of("id",store.id()));
            assertEquals(List.of("close","restore","suspend"),stores.history(admin,store.id(),true).stream().map(com.example.marketplace.seller.api.Stores.Event::action).toList());
            jdbc.update("INSERT INTO marketplace.store_operation_event(id,store_id,actor_id,action,before_state,after_state,reason,occurred_at) VALUES(:event,:store,:actor,'suspend','OPEN','SUSPENDED','legacy','2100-01-01T00:00:00Z')",Map.of("event",UUID.randomUUID(),"store",store.id(),"actor",admin));
            assertEquals(List.of("close","restore","suspend","legacy"),stores.history(owner,store.id(),false).stream().map(com.example.marketplace.seller.api.Stores.Event::reason).toList());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.store_operation_event WHERE store_id=:id AND store_version IS NULL",Map.of("id",store.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void productSaveWaitingForSuspendedMemberReturnsConflictWithoutWrite() throws Exception {
        UUID owner=activeMember("p4-suspend-race@example.com"), category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-suspend-race","P04","intro","address","","","",true,0L));
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,'P04 suspend race')",Map.of("id",category));
        var client=authBrowser();
        assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4-suspend-race@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String csrf=csrfToken(client);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);
                future.set(executor.submit(()->accountRequest(client,"POST","/api/seller/products",Map.of("categoryId",category,"slug","p04-suspend-product","title","상품","description","","state","DRAFT","version",0),csrf)));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                assertTrue(waiting,"product write must wait on member lock");
                jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
            });
            var response=future.get().get(5,TimeUnit.SECONDS);
            assertEquals(409,response.statusCode(),response.body());
            assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE slug='p04-suspend-product'",Map.of(),Integer.class));
        }
    }

    @Test
    void memberReadsNeedOnlyOneDatabaseConnection() {
        UUID member=activeMember("p4-single-connection@example.com");
        var original=(com.zaxxer.hikari.HikariDataSource)context.getBean(DataSource.class);
        try(var single=new com.zaxxer.hikari.HikariDataSource()) {
            single.setJdbcUrl(original.getJdbcUrl()); single.setUsername(original.getUsername()); single.setPassword(original.getPassword());
            single.setMaximumPoolSize(1); single.setMinimumIdle(0); single.setConnectionTimeout(300);
            var members=new com.example.marketplace.identity.infrastructure.LoginMembers(new NamedParameterJdbcTemplate(single));
            assertEquals(member.toString(),members.current(member.toString()).getUsername());
            assertEquals(member.toString(),members.loadUserByUsername("p4-single-connection@example.com").getUsername());
        }
    }

    @Test
    void publicStoreReadNeedsOnlyOneDatabaseConnection() {
        UUID owner=activeMember("p4-single-store@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-single-store","P04","intro","address","","","",true,0L));
        var original=(com.zaxxer.hikari.HikariDataSource)context.getBean(DataSource.class);
        try(var single=new com.zaxxer.hikari.HikariDataSource()) {
            single.setJdbcUrl(original.getJdbcUrl()); single.setUsername(original.getUsername()); single.setPassword(original.getPassword());
            single.setMaximumPoolSize(1); single.setMinimumIdle(0); single.setConnectionTimeout(300);
            var template=new NamedParameterJdbcTemplate(single);
            var service=new com.example.marketplace.seller.infrastructure.JdbcStores(template,context.getBean(com.example.marketplace.identity.api.SellerMembership.class),new com.example.marketplace.identity.infrastructure.JdbcAccessPolicy(template),transactionManager);
            assertEquals("p04-single-store",service.published("p04-single-store").slug());
        }
    }

    @Test
    void categoryWaiterRechecksRevokedPermissionAndWritesNoAudit() throws Exception {
        UUID admin=activeMember("p4-revoked-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var categories=context.getBean(com.example.marketplace.catalog.api.Categories.class);
        var category=categories.save(admin,null,new com.example.marketplace.catalog.api.Categories.Input("P04 revoked",true,0L));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.catalog_category WHERE id=:id FOR UPDATE",Map.of("id",category.id()),UUID.class);
                future.set(executor.submit(()->{
                    try { categories.save(admin,category.id(),new com.example.marketplace.catalog.api.Categories.Input("unauthorized",false,0L)); return "changed"; }
                    catch(com.example.marketplace.identity.api.AccessRejected rejected) { return "denied"; }
                }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%catalog_category%FOR UPDATE%')",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                assertTrue(waiting,"must observe category lock wait before revoking permission");
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            });
            assertEquals("denied",future.get().get(5,TimeUnit.SECONDS));
            assertEquals("P04 revoked",jdbc.queryForObject("SELECT name FROM marketplace.catalog_category WHERE id=:id",Map.of("id",category.id()),String.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.category_event WHERE category_id=:id",Map.of("id",category.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void productDescriptionAcceptsMultilineButTitleRemainsSingleLine() {
        UUID owner=activeMember("p4-multiline@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-multiline","P04","intro","address","","","",true,0L));
        UUID category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,'P04 multiline')",Map.of("id",category));
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var input=new com.example.marketplace.catalog.api.Products.Input(category,"p04-multiline-product","title","첫 줄\r\n둘째 줄\t설명","DRAFT",0L);
        var saved=products.save(owner,null,input);
        assertEquals("첫 줄\n둘째 줄\t설명",products.own(owner,saved.id()).description());
        assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p04-invalid","첫 줄\n둘째 줄","","DRAFT",0L))).status());
        assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p04-control","title","control\u0000","DRAFT",0L))).status());
    }

    @Test
    void catalogValidationPublicEligibilityAndConcurrentUpdates() throws Exception {
        UUID admin=activeMember("p4-bounds-admin@example.com"), owner=activeMember("p4-bounds-owner@example.com"), buyer=activeMember("p4-bounds-buyer@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var categories=context.getBean(com.example.marketplace.catalog.api.Categories.class);
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var catInput=new com.example.marketplace.catalog.api.Categories.Input("P04 boundaries",true,0L);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->categories.save(admin,null,catInput));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        try {
            var cat=categories.save(admin,null,catInput);
            var store=stores.save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-boundaries","P04","intro","address","","","",true,0L));
            var draft=new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-bounds","😀".repeat(120),"","DRAFT",0L);
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->products.save(buyer,null,draft));
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-long","a".repeat(121),"","DRAFT",0L))).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-long","ok","x".repeat(4001),"DRAFT",0L))).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,new com.example.marketplace.catalog.api.Products.Input(cat.id(),"p04-long","ok","","ACTIVE",0L))).status());
            var product=products.save(owner,null,draft);
            assertEquals(409,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,null,draft)).status());
            var active=new com.example.marketplace.catalog.api.Products.Input(cat.id(),draft.slug(),draft.title(),"","ACTIVE",0L);
            var results=race(()->{ try { products.save(owner,product.id(),active); return "ok"; } catch(com.example.marketplace.catalog.api.CatalogException failure) { assertEquals(409,failure.status()); return "conflict"; } });
            assertEquals(1,results.stream().filter("ok"::equals).count());
            assertEquals(1,products.own(owner,product.id()).version());
            assertEquals(draft.slug(),products.published(draft.slug()).slug());
            assertEquals(409,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(cat.id(),draft.slug(),"ok","","DRAFT",1L))).status());
            for(String restriction:List.of("store-private","store-suspended","seller-suspended","seller-role","category")) {
                switch(restriction) {
                    case "store-private" -> jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",store.id()));
                    case "store-suspended" -> jdbc.update("UPDATE marketplace.seller_store SET visible=false,state='SUSPENDED' WHERE id=:id",Map.of("id",store.id()));
                    case "seller-suspended" -> jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
                    case "seller-role" -> jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",owner));
                    default -> jdbc.update("UPDATE marketplace.catalog_category SET active=false WHERE id=:id",Map.of("id",cat.id()));
                }
                assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.published(draft.slug())).status());
                assertFalse(products.published(0).items().stream().anyMatch(published->published.slug().equals(draft.slug())));
                jdbc.update("UPDATE marketplace.seller_store SET state='OPEN',visible=true WHERE id=:id",Map.of("id",store.id()));
                jdbc.update("UPDATE marketplace.identity_user SET state='ACTIVE' WHERE id=:id",Map.of("id",owner));
                jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER') ON CONFLICT DO NOTHING",Map.of("id",owner));
                jdbc.update("UPDATE marketplace.catalog_category SET active=true WHERE id=:id",Map.of("id",cat.id()));
                assertEquals(draft.slug(),products.published(draft.slug()).slug());
            }
            var sequence=new java.util.concurrent.atomic.AtomicInteger();
            race(()->{
                if(sequence.getAndIncrement()==0) { categories.save(admin,cat.id(),new com.example.marketplace.catalog.api.Categories.Input(cat.name(),false,0L)); return "disabled"; }
                try { products.save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(cat.id(),draft.slug(),"edited","","ACTIVE",1L)); return "edited"; }
                catch(com.example.marketplace.catalog.api.CatalogException failure) { assertEquals(409,failure.status()); return "conflict"; }
            });
            assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.published(draft.slug())).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->products.published(-1)).status());
            assertEquals(400,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->categories.list(null,10001)).status());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void categoryAuditIsAtomicAndOwnedByOperator() {
        UUID admin=activeMember("p4-audit-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var categories=context.getBean(com.example.marketplace.catalog.api.Categories.class);
        try {
            var input=new com.example.marketplace.catalog.api.Categories.Input("P04 audit",true,0L);
            var created=categories.save(admin,null,input);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.category_event WHERE category_id=:id AND actor_id=:actor",Map.of("id",created.id(),"actor",admin),Integer.class));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.category_event ADD CONSTRAINT p4_fail_audit CHECK(category_id <> '"+created.id()+"'::uuid) NOT VALID");
            try { assertThrows(DataIntegrityViolationException.class,()->categories.save(admin,created.id(),new com.example.marketplace.catalog.api.Categories.Input("P04 lost",false,0L))); }
            finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.category_event DROP CONSTRAINT p4_fail_audit"); }
            assertEquals("P04 audit",jdbc.queryForObject("SELECT name FROM marketplace.catalog_category WHERE id=:id",Map.of("id",created.id()),String.class));
            assertEquals(0,jdbc.queryForObject("SELECT version FROM marketplace.catalog_category WHERE id=:id",Map.of("id",created.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void catalogProductHttpOwnershipLifecycleAndPublicFilters() throws Exception {
        UUID admin=activeMember("p4-product-admin@example.com"), owner=activeMember("p4-product-owner@example.com"), other=activeMember("p4-product-other@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        for(UUID id:List.of(owner,other)) { jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",id)); }
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var store=stores.save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p04-store","P04","intro","address","","","",true,0L));
        stores.save(other,null,new com.example.marketplace.seller.api.Stores.Input("p04-other","P04 other","intro","address","","","",true,0L));
        var adminClient=authBrowser(); var seller=authBrowser(); var stranger=authBrowser();
        assertEquals(200,authRequest(adminClient,"/api/auth/login",loginForm("p4-product-admin@example.com",SIGNUP_PASSWORD),csrfToken(adminClient)).statusCode());
        assertEquals(200,authRequest(seller,"/api/auth/login",loginForm("p4-product-owner@example.com",SIGNUP_PASSWORD),csrfToken(seller)).statusCode());
        assertEquals(200,authRequest(stranger,"/api/auth/login",loginForm("p4-product-other@example.com",SIGNUP_PASSWORD),csrfToken(stranger)).statusCode());
        try {
            var category=accountRequest(adminClient,"POST","/api/admin/content/categories",Map.of("name","P04 product category","active",true,"version",0),csrfToken(adminClient));
            assertEquals(201,category.statusCode());
            String categoryId=authMapper.readTree(category.body()).get("id").asText();
            var body=new java.util.HashMap<String,Object>(Map.of("slug","p04-product","title","상품","description","소개","categoryId",categoryId,"state","DRAFT","version",0));
            assertEquals(403,accountRequest(seller,"POST","/api/seller/products",body,null).statusCode());
            var created=accountRequest(seller,"POST","/api/seller/products",body,csrfToken(seller));
            assertEquals(201,created.statusCode(),created.body());
            String id=authMapper.readTree(created.body()).get("id").asText();
            String path="/api/seller/products/"+id;
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            assertEquals(404,accountRequest(stranger,"PUT",path,body,csrfToken(stranger)).statusCode());
            assertEquals(404,accountRequest(stranger,"GET",path,null,null).statusCode());
            body.put("state","ACTIVE");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(409,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            var published=accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null);
            assertEquals(200,published.statusCode()); assertFalse(published.body().contains("ownerId")); assertFalse(published.body().contains("hiddenByAdmin"));
            jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",UUID.fromString(id)));
            body.put("version",1); body.put("isHiddenByAdmin",false);
            var hiddenBefore=jdbc.queryForList("SELECT * FROM marketplace.catalog_product WHERE id=:id",Map.of("id",UUID.fromString(id)));
            assertEquals(400,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode(),"admin visibility is read-only and cannot be written by a seller");
            assertEquals(hiddenBefore,jdbc.queryForList("SELECT * FROM marketplace.catalog_product WHERE id=:id",Map.of("id",UUID.fromString(id))));
            body.remove("isHiddenByAdmin");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            assertFalse(accountRequest(authBrowser(),"GET","/api/products",null,null).body().contains("p04-product"));
            jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=false WHERE id=:id",Map.of("id",UUID.fromString(id)));
            assertEquals(200,accountRequest(adminClient,"PUT","/api/admin/content/categories/"+categoryId,Map.of("name","P04 product category","active",false,"version",0),csrfToken(adminClient)).statusCode());
            body.put("version",2);
            assertEquals(409,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            body.put("state","STOPPED");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            body.put("version",3); body.put("state","ARCHIVED");
            assertEquals(200,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            body.put("version",4); body.put("state","ACTIVE");
            assertEquals(409,accountRequest(seller,"PUT",path,body,csrfToken(seller)).statusCode());
            assertEquals(404,accountRequest(authBrowser(),"GET","/api/products/p04-product",null,null).statusCode());
            assertFalse(accountRequest(authBrowser(),"GET","/api/products",null,null).body().contains("p04-product"));
            stores.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(0L,"closed"));
            body.put("slug","p04-new"); body.put("state","DRAFT"); body.put("version",0);
            assertEquals(409,accountRequest(seller,"POST","/api/seller/products",body,csrfToken(seller)).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void catalogCategoryHttpContract() throws Exception {
        UUID admin=activeMember("p4-category-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var browser=authBrowser();
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p4-category-admin@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        try {
            var body=Map.of("name","P04 category","active",true,"version",0);
            assertEquals(403,accountRequest(browser,"POST","/api/admin/content/categories",body,null).statusCode());
            var created=accountRequest(browser,"POST","/api/admin/content/categories",body,csrfToken(browser));
            assertEquals(201,created.statusCode(),created.body());
            String id=authMapper.readTree(created.body()).get("id").asText();
            assertEquals(409,accountRequest(browser,"POST","/api/admin/content/categories",body,csrfToken(browser)).statusCode());
            assertEquals(200,accountRequest(browser,"PUT","/api/admin/content/categories/"+id,Map.of("name","P04 edited","active",false,"version",0),csrfToken(browser)).statusCode());
            assertEquals(409,accountRequest(browser,"PUT","/api/admin/content/categories/"+id,body,csrfToken(browser)).statusCode());
            assertEquals(400,accountRequest(browser,"POST","/api/admin/content/categories",Map.of("name"," ","active",true,"version",0),csrfToken(browser)).statusCode());
            assertEquals(200,accountRequest(authBrowser(),"GET","/api/categories",null,null).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }

    @Test
    void storeOperatorPermissionsAndConcurrentEditSuspendKeepRestriction() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3c-permissions-owner@example.com"), admin=activeMember("p3c-permissions-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        var input=new com.example.marketplace.seller.api.Stores.Input("permission-store","모의","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var change=new com.example.marketplace.seller.api.Stores.Change(0L,"모의 정지");
        try {
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.transition(admin,store.id(),"suspend",change));
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.queue(admin,0));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",owner));
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.transition(owner,store.id(),"suspend",change));
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",owner));
            assertEquals(403,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(owner,store.id(),"suspend",change)).status());
            assertEquals(store,service.own(owner));
            assertTrue(service.history(owner,store.id(),false).isEmpty());
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
            var sequence=new java.util.concurrent.atomic.AtomicInteger();
            var results=race(()->{
                try {
                    if(sequence.getAndIncrement()==0) { service.save(owner,store.id(),input); return "edit"; }
                    service.transition(admin,store.id(),"suspend",change); return "suspend";
                } catch(com.example.marketplace.seller.api.StoreException failure) { assertEquals(409,failure.status()); return "conflict"; }
            });
            assertEquals(1,results.stream().filter("conflict"::equals).count());
            assertEquals(1,service.own(owner).version());
            if(results.contains("edit")) {
                service.transition(admin,store.id(),"suspend",new com.example.marketplace.seller.api.Stores.Change(1L,"최신 버전 정지"));
            }
            var suspended=service.own(owner);
            assertEquals("SUSPENDED",suspended.state());
            assertFalse(suspended.visible());
            var updated=new com.example.marketplace.seller.api.Stores.Input(input.slug(),"덮어쓰기",input.introduction(),input.address(),"","","",true,suspended.version());
            assertEquals(409,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,store.id(),updated)).status());
            assertEquals(suspended,service.own(owner));
            assertFalse(service.capabilities(owner).manageCatalog());
            assertFalse(service.capabilities(owner).newSales());
            assertTrue(service.capabilities(owner).existingTrades());
            service.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(suspended.version(),"정지 중 폐점"));
            assertEquals("CLOSED",service.own(owner).state());
            assertEquals(2,service.history(owner,store.id(),false).size());
        } finally {
            for(UUID id:List.of(owner,admin)) {
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",id));
                jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",id));
            }
        }
    }
    @Test void catalogSessionSaveAndAccountRevocationKeepCanonicalLockOrder() throws Exception {
        UUID admin=activeMember("c-r03-admin@example.com");jdbc.update("INSERT INTO marketplace.role_assignment(user_id,role) VALUES(:id,'ADMIN')",Map.of("id",admin));jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) VALUES(:id,'ACCOUNTS')",Map.of("id",admin));var observations=new java.util.ArrayList<String>();
        for(boolean create:List.of(false,true)) { for(String revocation:List.of("SUSPEND","WITHDRAW")) { for(boolean saveFirst:List.of(true,false)) {
        var f=paymentFixture("a-r03-"+create+"-"+revocation.toLowerCase(java.util.Locale.ROOT)+"-"+saveFirst,false);UUID owner=f.authority().member();var current=ownedPaymentProduct(f);String slug=create?"c-r03-create-"+UUID.randomUUID().toString().substring(0,8):current.slug();UUID product=create?null:current.id();var input=new com.example.marketplace.catalog.api.Products.Input(current.categoryId(),slug,"회수비 권한경합","",create?"DRAFT":current.state(),create?0:current.version(),"5000");var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var once=new AtomicBoolean();var real=context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class);com.example.marketplace.identity.api.PurchaseSessions sessions=(browser,member,authVersion)->{boolean valid=real.lockCurrent(browser,member,authVersion);if(saveFirst&&once.compareAndSet(false,true)) { entered.countDown();try { if(!resume.await(8,TimeUnit.SECONDS)) { throw new IllegalStateException("R03_BARRIER_TIMEOUT"); } }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); } }return valid;};var api=new com.example.marketplace.catalog.infrastructure.JdbcProducts(jdbc,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);org.springframework.test.util.ReflectionTestUtils.setField(api,"sessions",sessions);var account=context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
          java.util.concurrent.Callable<Throwable> save=()->{try { api.saveForSession(owner,f.authority().browser(),f.authority().version(),product,input);return null; }catch(Throwable failure) { return failure; }};
          java.util.concurrent.Callable<Throwable> revoke=()->{try { new TransactionTemplate(transactionManager).executeWithoutResult(t->{if(!saveFirst) { jdbc.queryForList("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner));entered.countDown();try { if(!resume.await(8,TimeUnit.SECONDS)) { throw new IllegalStateException("R03_REVOKE_BARRIER_TIMEOUT"); } }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); } }if(revocation.equals("SUSPEND")) { account.suspend(admin,owner,"상품 회수비 편집 동시 권한폐기 검사"); }else { account.withdraw(owner," A1-private-passphrase-가나다 ","WITHDRAW"); }});return null; }catch(Throwable failure) { return failure; }};
          var first=pool.submit(saveFirst?save:revoke);assertTrue(entered.await(5,TimeUnit.SECONDS));var second=pool.submit(saveFirst?revoke:save);try { awaitJobCondition(()->jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND(query LIKE '%marketplace.identity_user%' OR query LIKE '%marketplace.spring_session%'))",Map.of(),Boolean.class),"actual account/session row wait must be observed"); }finally { resume.countDown(); }Throwable firstError=first.get(12,TimeUnit.SECONDS),secondError=second.get(12,TimeUnit.SECONDS);Throwable saveError=saveFirst?firstError:secondError,revokeError=saveFirst?secondError:firstError;observations.add(create+"/"+revocation+"/saveFirst="+saveFirst+"/save="+(saveError==null?"OK":saveError.getClass().getSimpleName())+"/revoke="+(revokeError==null?"OK":revokeError.getClass().getSimpleName()));java.nio.file.Files.write(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"catalog-account-lock-observed.txt"),observations);assertNull(revokeError,"account revocation must finish without deadlock");if(saveFirst) { assertNull(saveError,"catalog save holding a valid session must finish without account/session deadlock"); }else { assertTrue(saveError!=null,"revocation first must reject the catalog save");assertFalse(saveError instanceof org.springframework.dao.ConcurrencyFailureException,"revocation rejection must not be a deadlock"); }
        }finally { resume.countDown(); }
        assertEquals(revocation.equals("SUSPEND")?"SUSPENDED":"WITHDRAWN",jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner),String.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",Map.of("id",owner.toString()),Integer.class));if(create) { assertEquals(saveFirst?1:0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE slug=:slug",Map.of("slug",slug),Integer.class)); }else { assertEquals(saveFirst?"5000":current.returnPolicy().amount(),jdbc.queryForObject("SELECT return_fee::text FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product),String.class));assertEquals(saveFirst?current.version()+1:current.version(),jdbc.queryForObject("SELECT version FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product),Long.class)); }
        } } }
    }
    @Test void catalogSessionExpiryAfterCategoryWaitAndStaleVersionLeaveNoPolicyWrite() throws Exception {
        for(boolean create:List.of(false,true)) {
        var f=paymentFixture("a-r03-expiry-"+create,false);UUID owner=f.authority().member();var current=ownedPaymentProduct(f);var api=new com.example.marketplace.catalog.infrastructure.JdbcProducts(jdbc,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);org.springframework.test.util.ReflectionTestUtils.setField(api,"sessions",context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class));String slug=create?"c-r03-expiry-"+UUID.randomUUID().toString().substring(0,8):current.slug();var input=new com.example.marketplace.catalog.api.Products.Input(current.categoryId(),slug,"만료 뒤 저장금지","",create?"DRAFT":current.state(),create?0:current.version(),"5000");long expiry=System.currentTimeMillis()+1500;jdbc.update("UPDATE marketplace.spring_session SET expiry_time=:at WHERE session_id=:id",Map.of("at",expiry,"id",f.authority().browser()));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) { var blocker=pool.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(t->{jdbc.queryForList("SELECT id FROM marketplace.catalog_category WHERE id=:id FOR UPDATE",Map.of("id",current.categoryId()));locked.countDown();try { if(!release.await(8,TimeUnit.SECONDS)) { throw new IllegalStateException("CATEGORY_BARRIER_TIMEOUT"); } }catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }}));assertTrue(locked.await(5,TimeUnit.SECONDS));var save=pool.submit(()->{try { api.saveForSession(owner,f.authority().browser(),f.authority().version(),create?null:current.id(),input);return 200; }catch(com.example.marketplace.catalog.api.CatalogException rejected) { return rejected.status(); }});try { awaitCouponLock("catalog_category");awaitJobCondition(()->System.currentTimeMillis()>expiry,"natural session deadline must pass during actual category row wait"); }finally { release.countDown(); }blocker.get(10,TimeUnit.SECONDS);assertEquals(401,save.get(10,TimeUnit.SECONDS)); }finally { release.countDown(); }
        if(create) { assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE slug=:slug",Map.of("slug",slug),Integer.class)); }else { assertEquals(current.returnPolicy().amount(),jdbc.queryForObject("SELECT return_fee::text FROM marketplace.catalog_product WHERE id=:id",Map.of("id",current.id()),String.class));assertEquals(current.version(),jdbc.queryForObject("SELECT version FROM marketplace.catalog_product WHERE id=:id",Map.of("id",current.id()),Long.class)); }
        var stale=paymentFixture("a-r03-stale-"+create,false);var before=ownedPaymentProduct(stale);var rejected=new com.example.marketplace.catalog.api.Products.Input(before.categoryId(),before.slug(),"stale auth","",before.state(),before.version(),"5000");assertEquals(401,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->context.getBean(com.example.marketplace.catalog.api.Products.class).saveForSession(stale.authority().member(),stale.authority().browser(),stale.authority().version()+1,before.id(),rejected)).status());assertEquals(before,context.getBean(com.example.marketplace.catalog.api.Products.class).own(stale.authority().member(),before.id()));
        }
    }
}
