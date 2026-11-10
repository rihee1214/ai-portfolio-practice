package com.example.marketplace.dbtest;


import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingIT extends PostgresContractFixture {
    @Test void wishlistOwnershipVisibilityAndIdempotency() throws Exception {
        var f=browseFixture("wish-http",1700,5);
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID owner=activeMember("wish-owner@example.com"),other=activeMember("wish-other@example.com");
        var browser=authBrowser();
        assertEquals(401,accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).statusCode());
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("wish-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        String csrf=csrfToken(browser),path="/api/wishlist/"+product.slug()+"?owner="+owner;
        assertEquals(200,accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).statusCode());
        assertEquals(403,accountRequest(browser,"PUT",path,Map.of(),null).statusCode());
        assertEquals(409,accountRequest(browser,"GET","/api/wishlist?owner="+other,null,null).statusCode());
        assertEquals(409,accountRequest(browser,"PUT","/api/wishlist/"+product.slug()+"?owner="+other,Map.of(),csrf).statusCode());
        for(int i=0;i<2;i++) { assertEquals(200,accountRequest(browser,"PUT",path,Map.of(),csrf).statusCode()); }
        var list=authMapper.readTree(accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).body());
        assertEquals(1,list.get("items").size());assertEquals(product.id().toString(),list.get("items").get(0).get("productId").asText());
        assertEquals("P05 wish-http",list.get("items").get(0).get("detail").get("product").get("title").asText());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product.id()));
        list=authMapper.readTree(accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).body());
        assertTrue(list.get("items").get(0).get("detail").isNull());
        assertEquals(404,accountRequest(browser,"PUT",path,Map.of(),csrf).statusCode());
        assertEquals(409,accountRequest(browser,"DELETE","/api/wishlist/"+product.id()+"?owner="+other,null,csrf).statusCode());
        for(int i=0;i<2;i++) { assertEquals(200,accountRequest(browser,"DELETE","/api/wishlist/"+product.id()+"?owner="+owner,null,csrf).statusCode()); }
        assertEquals(0,authMapper.readTree(accountRequest(browser,"GET","/api/wishlist?owner="+owner,null,null).body()).get("items").size());
        stockEquals((UUID)f.get("sku"),5,0,0);
    }

    @Test void wishlistCapacityConcurrencyAndSuspension() throws Exception {
        var api=context.getBean(com.example.marketplace.cart.api.Wishlists.class);
        UUID owner=activeMember("wish-race@example.com");var f=browseFixture("wish-race",100,5);
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        assertEquals(List.of("ok","ok"),race(()->{api.add(owner,product.slug());return "ok";}));
        assertEquals(1,api.get(owner).items().size());
        for(int i=0;i<99;i++) { jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:id)",Map.of("owner",owner,"id",UUID.randomUUID())); }
        assertEquals(100,api.add(owner,product.slug()).items().size());
        var second=(com.example.marketplace.catalog.api.Products.Product)browseFixture("wish-over",100,5).get("product");
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.add(owner,second.slug())).status());
        assertEquals(100,api.get(owner).items().size());
        UUID capOwner=activeMember("wish-cap-race@example.com");
        for(int i=0;i<99;i++) { jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:id)",Map.of("owner",capOwner,"id",UUID.randomUUID())); }
        var index=new java.util.concurrent.atomic.AtomicInteger();
        var accepted=new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
        var rejected=new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
        var contenders=List.of(product,second);
        var capResults=race(()->{
            var candidate=contenders.get(index.getAndIncrement());
            try { api.add(capOwner,candidate.slug());accepted.add(candidate.id());return "ok"; }
            catch(com.example.marketplace.cart.api.CartException e) { rejected.add(candidate.id());return ""+e.status(); }
        });
        assertEquals(1,capResults.stream().filter("ok"::equals).count());
        assertEquals(1,capResults.stream().filter("409"::equals).count());
        var capIds=api.get(capOwner).items().stream().map(com.example.marketplace.cart.api.Wishlists.Line::productId).toList();
        assertEquals(100,capIds.size());assertTrue(capIds.containsAll(accepted));
        assertFalse(capIds.contains(rejected.element()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.wishlist(user_id,product_id) VALUES(:owner,:id)",Map.of("owner",owner,"id",product.id())));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);
                future.set(executor.submit(()->{try{api.remove(owner,product.id());return 200;}catch(com.example.marketplace.identity.api.AccessRejected e){return 403;}}));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertTrue(waiting,"Wishlist removal must wait on member row");
                jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
            });
            assertEquals(403,future.get().get(10,TimeUnit.SECONDS));
        }
        assertEquals(100,jdbc.queryForObject("SELECT count(*) FROM marketplace.wishlist WHERE user_id=:id",Map.of("id",owner),Integer.class));
    }

    @Test void cartOwnershipCrudAndPublicAvailability() throws Exception {
        var f=browseFixture("cart-http",1700,5);UUID sku=(UUID)f.get("sku");var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID owner=activeMember("cart-owner@example.com"),other=activeMember("cart-other@example.com");var browser=authBrowser();
        assertEquals(401,accountRequest(browser,"GET","/api/cart",null,null).statusCode());
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("cart-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());String csrf=csrfToken(browser);
        assertEquals(200,accountRequest(browser,"GET","/api/cart?owner="+owner,null,null).statusCode());
        assertEquals(409,accountRequest(browser,"GET","/api/cart?owner="+other,null,null).statusCode());
        var body=Map.of("owner",owner,"quantity",2,"version",0);
        assertEquals(403,accountRequest(browser,"PUT","/api/cart/items/"+sku,body,null).statusCode());
        assertEquals(409,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",other,"quantity",2,"version",0),csrf).statusCode());
        var saved=accountRequest(browser,"PUT","/api/cart/items/"+sku,body,csrf);assertEquals(200,saved.statusCode(),saved.body());
        var api=carts();assertEquals(2,api.get(owner).items().getFirst().quantity());assertTrue(api.get(other).items().isEmpty());
        assertEquals(409,accountRequest(browser,"PUT","/api/cart/items/"+sku,body,csrf).statusCode());
        for(Object q:List.of(-1,100,1.5)) { assertEquals(400,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",owner,"quantity",q,"version",1),csrf).statusCode()); }
        jdbc.update("UPDATE marketplace.catalog_sku SET price=2200 WHERE id=:id",Map.of("id",sku));
        assertEquals(2200,api.get(owner).items().getFirst().detail().offers().getFirst().price());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",p.id()));
        assertNull(api.get(owner).items().getFirst().detail());
        assertEquals(400,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",owner,"quantity",2,"version",1.5),csrf).statusCode());
        for(String path:List.of("/api/cart/preview","/api/cart/merge")) {
            assertEquals(400,accountRequest(browser,"POST",path,Map.of("owner",owner,"requestId",UUID.randomUUID(),"items",List.of(Map.of("skuId",sku,"quantity",1.5))),csrf).statusCode());
        }
        var anon=authBrowser();assertEquals(200,accountRequest(anon,"POST","/api/cart/preview",Map.of("items",List.of(Map.of("skuId",sku,"quantity",1))),csrfToken(anon)).statusCode());
        assertEquals(403,accountRequest(anon,"POST","/api/cart/preview",Map.of("items",List.of()),null).statusCode());
        assertEquals(200,accountRequest(browser,"PUT","/api/cart/items/"+sku,Map.of("owner",owner,"quantity",0,"version",1),csrf).statusCode());
        assertTrue(api.get(owner).items().isEmpty());stockEquals(sku,5,0,0);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE sku_id=:id",Map.of("id",sku),Integer.class));
    }

    @Test void cartMergeReplayAndConcurrentUpdates() throws Exception {
        var api=carts();UUID owner=activeMember("cart-merge@example.com"),other=activeMember("cart-merge-other@example.com");var f=browseFixture("cart-merge",100,10);UUID sku=(UUID)f.get("sku");
        var first=api.set(owner,sku,70,0);UUID request=UUID.randomUUID();var input=List.of(new com.example.marketplace.cart.api.Carts.Input(sku,60));
        assertEquals(List.of("ok","ok"),race(()->{api.merge(owner,request,input);return "ok";}));
        var merged=api.get(owner);assertEquals(99,merged.items().getFirst().quantity());assertEquals(first.version()+1,merged.version());
        api.merge(owner,request,input);assertEquals(merged.version(),api.get(owner).version());
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.merge(other,request,input)).status());
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.merge(owner,request,List.of(new com.example.marketplace.cart.api.Carts.Input(sku,1)))).status());
        var results=race(()->{try{api.set(owner,sku,4,merged.version());return "ok";}catch(com.example.marketplace.cart.api.CartException e){return ""+e.status();}});
        assertEquals(1,results.stream().filter("ok"::equals).count());assertEquals(1,results.stream().filter("409"::equals).count());
        var current=api.get(owner);api.clear(owner,current.version());api.merge(owner,request,input);assertTrue(api.get(owner).items().isEmpty());
    }

    @Test void cartCapacityRollbackAndSuspension() throws Exception {
        var api=carts();UUID owner=activeMember("cart-capacity@example.com");
        var items=java.util.stream.IntStream.range(0,100).mapToObj(i->new com.example.marketplace.cart.api.Carts.Input(UUID.randomUUID(),1)).toList();
        api.merge(owner,UUID.randomUUID(),items);UUID failed=UUID.randomUUID();
        assertEquals(409,assertThrows(com.example.marketplace.cart.api.CartException.class,()->api.merge(owner,failed,List.of(new com.example.marketplace.cart.api.Carts.Input(UUID.randomUUID(),1)))).status());
        assertEquals(100,api.get(owner).items().size());assertEquals(1,api.get(owner).version());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.cart_merge WHERE request_id=:id",Map.of("id",failed),Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.cart_item SET quantity=100 WHERE user_id=:id",Map.of("id",owner)));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);
                future.set(executor.submit(()->{try{api.clear(owner,1);return 200;}catch(com.example.marketplace.identity.api.AccessRejected rejected){return 403;}}));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) {break;}
                    try{Thread.sleep(20);}catch(InterruptedException e){throw new IllegalStateException(e);}
                }
                assertTrue(waiting,"Cart mutation must wait on member row");
                jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
            });
            assertEquals(403,future.get().get(10,TimeUnit.SECONDS));
        }
        assertEquals(100,jdbc.queryForObject("SELECT count(*) FROM marketplace.cart_item WHERE user_id=:id",Map.of("id",owner),Integer.class));
    }
}
