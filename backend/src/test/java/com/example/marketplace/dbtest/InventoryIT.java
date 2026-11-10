package com.example.marketplace.dbtest;


import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryIT extends PostgresContractFixture {
    @Test
    void reservationCrossSkuReverseReservationKeysAreSerialized() throws Exception { reservationCrossKeys(false); }

    @Test
    void reservationCrossSkuReverseOrderItemKeysAreSerialized() throws Exception { reservationCrossKeys(true); }

    @Test
    void reservationTerminalAndExpiryRaces() throws Exception {
        UUID sku=stockedSku("reserve-terminal",10);var api=reservations();var request=reservationRequest(sku,2);api.reserve(List.of(request));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(race(()->reservationAttempt(()-> { if(calls.getAndIncrement()==0) { api.commit(request.checkoutId(),List.of(request.id())); }
            else { api.release(request.checkoutId(),List.of(request.id())); } }))).containsExactlyInAnyOrder("ok","409");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id=:id AND cause<>'RESERVE'",Map.of("id",request.id()),Integer.class));
        var due=reservationRequest(sku,1);api.reserve(List.of(due));
        assertEquals("RESERVED",api.expire(due.checkoutId(),List.of(due.id())).getFirst().status());
        jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",due.id()));
        assertEquals("409",reservationAttempt(()->api.commit(due.checkoutId(),List.of(due.id()))));
        assertThat(race(()->reservationAttempt(()->api.expire(due.checkoutId(),List.of(due.id()))))).containsExactly("ok","ok");
        assertEquals("RELEASED",api.expire(due.checkoutId(),List.of(due.id())).getFirst().status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id=:id AND cause='EXPIRE'",Map.of("id",due.id()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku),Long.class));
        var expired=reservationRequest(sku,1);api.reserve(List.of(expired));
        jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",expired.id()));
        calls.set(0);
        assertThat(race(()->reservationAttempt(()-> { if(calls.getAndIncrement()==0) { api.commit(expired.checkoutId(),List.of(expired.id())); }
            else { api.expire(expired.checkoutId(),List.of(expired.id())); } }))).containsExactlyInAnyOrder("409","ok");
    }

    @Test
    void reservationExpiryIsCheckedAfterBalanceLock() throws Exception {
        UUID sku=stockedSku("reserve-lock-time",3);var api=reservations();var request=reservationRequest(sku,1);api.reserve(List.of(request));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=:sku FOR UPDATE",Map.of("sku",sku),UUID.class);
                pending.set(pool.submit(()->reservationAttempt(()->api.commit(request.checkoutId(),List.of(request.id())))));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%inventory_balance%')",Map.of(),Boolean.class));
                    if(waiting) { break; } try { Thread.sleep(20); } catch(InterruptedException ex) { throw new IllegalStateException(ex); }
                }
                assertTrue(waiting,"commit must actually wait on the balance lock");
                // Advance the fixture to the DB boundary only after the worker is observed waiting.
                jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp() WHERE id=:id",Map.of("id",request.id()));
                var boundary=jdbc.queryForMap("SELECT expires_at,clock_timestamp() AS db_now,expires_at<=clock_timestamp() AS due FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",request.id()));
                assertEquals(Boolean.TRUE,boundary.get("due"),boundary.toString());
            });
            assertEquals("409",pending.get().get(5,TimeUnit.SECONDS));stockEquals(sku,3,1,2);
        }
        api.expire(request.checkoutId(),List.of(request.id()));stockEquals(sku,3,0,3);
    }

    @Test
    void reservationBatchesAndOuterTransactionRollBack() {
        UUID a=stockedSku("reserve-rollback-a",5),b=stockedSku("reserve-rollback-b",1);var api=reservations();
        var first=reservationRequest(a,2);var insufficient=reservationRequest(b,2);
        assertEquals("409",reservationAttempt(()->api.reserve(List.of(first,insufficient))));stockEquals(a,5,0,1);stockEquals(b,1,0,1);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE id IN (:a,:b)",Map.of("a",first.id(),"b",insufficient.id()),Integer.class));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            api.reserve(List.of(first));throw new IllegalStateException("outer operation failed");
        }));stockEquals(a,5,0,1);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE sku_id=:sku",Map.of("sku",a),Integer.class));
        api.reserve(List.of(first));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.inventory_reservation_event ADD CONSTRAINT p4e_audit_failure CHECK (cause<>'COMMIT') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->api.commit(first.checkoutId(),List.of(first.id())));stockEquals(a,5,2,2);
            assertEquals("RESERVED",jdbc.queryForObject("SELECT status FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",first.id()),String.class));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.inventory_reservation_event DROP CONSTRAINT p4e_audit_failure"); }
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            api.release(first.checkoutId(),List.of(first.id()));throw new IllegalStateException("outer release rollback");
        }));stockEquals(a,5,2,2);
        api.commit(first.checkoutId(),List.of(first.id()));stockEquals(a,3,0,3);
        var x=reservationRequest(a,1);var original=reservationRequest(b,1);
        var y=new com.example.marketplace.inventory.api.Reservations.Request(original.id(),x.checkoutId(),original.orderItemId(),b,1,original.expiresAt());
        api.reserve(List.of(x,y));api.release(y.checkoutId(),List.of(y.id()));
        assertEquals("409",reservationAttempt(()->api.commit(x.checkoutId(),List.of(x.id(),y.id()))));stockEquals(a,3,1,4);
        assertEquals("RESERVED",jdbc.queryForObject("SELECT status FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",x.id()),String.class));
    }

    @Test
    void reservationReverseBatchesAndManualAdjustmentSerialize() throws Exception {
        UUID a=stockedSku("reserve-batch-a",6),b=stockedSku("reserve-batch-b",6);var api=reservations();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var outcomes=race(()-> {var x=reservationRequest(a,2);var y=reservationRequest(b,2);
            return reservationAttempt(()->api.reserve(calls.getAndIncrement()==0?List.of(x,y):List.of(y,x)));});
        assertThat(outcomes).containsExactly("ok","ok");stockEquals(a,6,4,3);stockEquals(b,6,4,3);
        var x=reservationRequest(a,1);var original=reservationRequest(b,1);
        var y=new com.example.marketplace.inventory.api.Reservations.Request(original.id(),x.checkoutId(),original.orderItemId(),b,1,original.expiresAt());
        api.reserve(List.of(x,y));calls.set(0);
        assertThat(race(()->reservationAttempt(()->{if(calls.getAndIncrement()==0) { api.commit(x.checkoutId(),List.of(x.id(),y.id())); }
            else { api.release(x.checkoutId(),List.of(y.id(),x.id())); } }))).containsExactlyInAnyOrder("ok","409");
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id IN (:x,:y) AND cause<>'RESERVE'",Map.of("x",x.id(),"y",y.id()),Integer.class));
        var fixture=skuFixture("reserve-manual");UUID owner=(UUID)fixture.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)fixture.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst().id();var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        inventory.adjust(owner,product.id(),sku,false,new com.example.marketplace.inventory.api.Inventory.Change(5L,0L,"입고",UUID.randomUUID()));
        calls.set(0);assertThat(race(()->reservationAttempt(()-> { if(calls.getAndIncrement()==0) { api.reserve(List.of(reservationRequest(sku,4))); }
            else { inventory.adjust(owner,product.id(),sku,false,new com.example.marketplace.inventory.api.Inventory.Change(2L,1L,"실사",UUID.randomUUID())); } }))).containsExactlyInAnyOrder("ok","409");
        assertTrue(jdbc.queryForObject("SELECT reserved<=on_hand AND reserved>=0 FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku),Boolean.class));
    }

    @Test
    void reservationValidationAndDatabaseConstraints() {
        UUID sku=stockedSku("reserve-boundary",999999999);var api=reservations();var valid=reservationRequest(sku,999999999);
        assertEquals("400",reservationAttempt(()->api.reserve(null)));assertEquals("400",reservationAttempt(()->api.reserve(List.of())));
        assertEquals("400",reservationAttempt(()->api.reserve(List.of(valid,valid))));
        for(long quantity:new long[]{0,-1,1000000000L}) {
            var bad=new com.example.marketplace.inventory.api.Reservations.Request(valid.id(),valid.checkoutId(),valid.orderItemId(),sku,quantity,valid.expiresAt());
            assertEquals("400",reservationAttempt(()->api.reserve(List.of(bad))));
        }
        var past=new com.example.marketplace.inventory.api.Reservations.Request(valid.id(),valid.checkoutId(),valid.orderItemId(),sku,1,Instant.EPOCH);
        assertEquals("409",reservationAttempt(()->api.reserve(List.of(past))));
        assertEquals("404",reservationAttempt(()->api.reserve(List.of(reservationRequest(UUID.randomUUID(),1)))));
        assertEquals("400",reservationAttempt(()->api.commit(null,List.of(valid.id()))));assertEquals("400",reservationAttempt(()->api.release(valid.checkoutId(),List.of())));
        assertEquals("400",reservationAttempt(()->api.expire(valid.checkoutId(),java.util.Arrays.asList((UUID)null))));
        api.reserve(List.of(valid));stockEquals(sku,999999999,999999999,2);
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_reservation SET quantity=0 WHERE id=:id",Map.of("id",valid.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_reservation SET status='COMMITTED' WHERE id=:id",Map.of("id",valid.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=0 WHERE sku_id=:id",Map.of("id",sku)));
        api.release(valid.checkoutId(),List.of(valid.id()));stockEquals(sku,999999999,0,3);
        jdbc.update("UPDATE marketplace.inventory_balance SET version=9007199254740991 WHERE sku_id=:id",Map.of("id",sku));
        var overflow=reservationRequest(sku,1);assertEquals("409",reservationAttempt(()->api.reserve(List.of(overflow))));stockEquals(sku,999999999,0,9007199254740991L);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE id=:id",Map.of("id",overflow.id()),Integer.class));
        assertEquals("400",reservationAttempt(()->api.reserve(java.util.Collections.nCopies(101,valid))));
        assertEquals("400",reservationAttempt(()->api.commit(valid.checkoutId(),List.of(valid.id(),valid.id()))));
        var precise=new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),valid.checkoutId(),UUID.randomUUID(),sku,1,valid.expiresAt().plusNanos(123));
        jdbc.update("UPDATE marketplace.inventory_balance SET version=3 WHERE sku_id=:id",Map.of("id",sku));
        assertEquals(valid.expiresAt(),api.reserve(List.of(precise)).getFirst().expiresAt());api.reserve(List.of(precise));stockEquals(sku,999999999,1,4);
        var row=jdbc.queryForMap("SELECT * FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku));
        var ds=freshLegacyDatabase("p4e-upgrade");var target=new NamedParameterJdbcTemplate(ds);String schema="marketplace";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("17").cleanDisabled(true).load().migrate();var p=Map.of("id",sku);
        copyLegacyRows(target,"identity_user","SELECT u.* FROM marketplace.identity_user u JOIN marketplace.seller_store s ON s.owner_id=u.id JOIN marketplace.catalog_product p ON p.store_id=s.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",p);copyLegacyRows(target,"seller_store","SELECT s.* FROM marketplace.seller_store s JOIN marketplace.catalog_product p ON p.store_id=s.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",p);copyLegacyRows(target,"catalog_category","SELECT c.* FROM marketplace.catalog_category c JOIN marketplace.catalog_product p ON p.category_id=c.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",p);copyLegacyRows(target,"catalog_product","SELECT p.id,p.store_id,p.category_id,p.slug,p.title,p.description,p.state,p.is_hidden_by_admin,p.version FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",p);copyLegacyRows(target,"catalog_sku","SELECT * FROM marketplace.catalog_sku WHERE id=:id",p);copyLegacyRows(target,"inventory_balance","SELECT * FROM marketplace.inventory_balance WHERE sku_id=:id",p);copyLegacyRows(target,"stock_movement","SELECT * FROM marketplace.stock_movement WHERE sku_id=:id",p);
        var movement=target.queryForList("SELECT * FROM marketplace.stock_movement",Map.of());var migration=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();assertPlannedCurrentUpgrade(migration,17);assertEquals(1,target.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE created_at IS NOT NULL",Map.of(),Integer.class));assertEquals(row,target.queryForMap("SELECT * FROM marketplace.inventory_balance WHERE sku_id=:id",p));assertEquals(movement,target.queryForList("SELECT * FROM marketplace.stock_movement",Map.of()));
    }

    @Test
    void reservationLifecycleHasOneEffectAndCompleteAudit() {
        UUID sku=stockedSku("reserve-basic",10); var api=reservations(); var request=reservationRequest(sku,3);
        assertEquals("RESERVED",api.reserve(List.of(request)).getFirst().status()); stockEquals(sku,10,3,2);
        api.reserve(List.of(request)); stockEquals(sku,10,3,2);
        assertEquals("COMMITTED",api.commit(request.checkoutId(),List.of(request.id())).getFirst().status()); stockEquals(sku,7,0,3);
        api.commit(request.checkoutId(),List.of(request.id())); api.reserve(List.of(request)); stockEquals(sku,7,0,3);
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.release(request.checkoutId(),List.of(request.id()))).status());
        var released=reservationRequest(sku,2); api.reserve(List.of(released));
        api.release(released.checkoutId(),List.of(released.id())); api.release(released.checkoutId(),List.of(released.id())); stockEquals(sku,7,0,5);
        var events=jdbc.queryForList("SELECT cause,before_on_hand,after_on_hand,before_reserved,after_reserved,before_version,version FROM marketplace.inventory_reservation_event WHERE sku_id=:sku ORDER BY version",Map.of("sku",sku));
        assertEquals(List.of("RESERVE","COMMIT","RESERVE","RELEASE"),events.stream().map(e->e.get("cause")).toList());
        long[][] expected={{10,10,0,3,1,2},{10,7,3,0,2,3},{7,7,0,2,3,4},{7,7,2,0,4,5}};
        String[] keys={"before_on_hand","after_on_hand","before_reserved","after_reserved","before_version","version"};
        for(int i=0;i<4;i++) { for(int j=0;j<6;j++) { assertEquals(expected[i][j],((Number)events.get(i).get(keys[j])).longValue()); } }
    }

    @Test
    void reservationLastUnitRaceAndKeyCollisions() throws Exception {
        UUID sku=stockedSku("reserve-last",1); var api=reservations();
        var outcomes=race(()-> { try { api.reserve(List.of(reservationRequest(sku,1)));return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException ex) { return ""+ex.status(); } });
        assertThat(outcomes).containsExactlyInAnyOrder("ok","409"); stockEquals(sku,1,1,2);
        UUID another=stockedSku("reserve-key",5); var request=reservationRequest(another,2);
        assertThat(race(()->{api.reserve(List.of(request));return "ok";})).containsExactly("ok","ok"); stockEquals(another,5,2,2);
        assertEquals(404,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.commit(UUID.randomUUID(),List.of(request.id()))).status());
        var changed=new com.example.marketplace.inventory.api.Reservations.Request(request.id(),request.checkoutId(),request.orderItemId(),another,3,request.expiresAt());
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.reserve(List.of(changed))).status());
        var collision=new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),request.checkoutId(),request.orderItemId(),sku,1,request.expiresAt());
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->api.reserve(List.of(collision))).status());
        stockEquals(another,5,2,2); stockEquals(sku,1,1,2);
        UUID other=stockedSku("reserve-cross-key",5);var cross=new com.example.marketplace.inventory.api.Reservations.Request(request.id(),request.checkoutId(),UUID.randomUUID(),other,1,request.expiresAt());
        assertEquals("409",reservationAttempt(()->api.reserve(List.of(cross))));stockEquals(other,5,0,1);
        var key=UUID.randomUUID();var checkout=UUID.randomUUID();var index=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(race(()-> {UUID target=index.getAndIncrement()==0?another:other;
            return reservationAttempt(()->api.reserve(List.of(new com.example.marketplace.inventory.api.Reservations.Request(key,checkout,UUID.randomUUID(),target,1,request.expiresAt()))));
        })).containsExactlyInAnyOrder("ok","409");
        assertEquals(3,jdbc.queryForObject("SELECT sum(reserved) FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)",Map.of("a",another,"b",other),Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE reservation_id=:id",Map.of("id",key),Integer.class));
    }

    @Test
    void inventoryAdjustmentIsVersionedAuditedAndIdempotent() throws Exception {
        var f=skuFixture("stock-basic"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-stock-basic@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String path="/api/seller/products/"+product.id()+"/inventory";
        var list=accountRequest(client,"GET",path,null,null); assertEquals(200,list.statusCode(),list.body());
        assertEquals(0,authMapper.readTree(list.body()).get("items").get(0).get("onHand").asLong());
        var input=Map.of("onHand",10,"version",0,"reason","초기 입고","requestId",UUID.randomUUID().toString());
        var saved=accountRequest(client,"PUT",path+"/"+sku.id(),input,csrfToken(client)); assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(1,authMapper.readTree(saved.body()).get("version").asLong());
        assertEquals(saved.body(),accountRequest(client,"PUT",path+"/"+sku.id(),input,csrfToken(client)).body());
        assertEquals(409,accountRequest(client,"PUT",path+"/"+sku.id(),Map.of("onHand",20,"version",0,"reason","낡은 조정","requestId",UUID.randomUUID().toString()),csrfToken(client)).statusCode());
        var history=accountRequest(client,"GET",path+"/"+sku.id()+"/history",null,null); assertEquals(200,history.statusCode());
        var events=authMapper.readTree(history.body()).get("items"); assertEquals(1,events.size()); assertEquals(0,events.get(0).get("beforeOnHand").asLong()); assertEquals(10,events.get(0).get("afterOnHand").asLong()); assertEquals("초기 입고",events.get(0).get("reason").asText());
    }

    @Test
    void inventoryBoundariesOwnershipAndReservedFloorAreEnforced() throws Exception {
        var f=skuFixture("stock-limits"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        var client=authBrowser(); authRequest(client,"/api/auth/login",loginForm("p4b-stock-limits@example.com",SIGNUP_PASSWORD),csrfToken(client));
        String path="/api/seller/products/"+product.id()+"/inventory/"+sku.id();
        for(Object value:List.of(-1,1000000000L,"1",true,1.5)) {
            assertEquals(400,accountRequest(client,"PUT",path,Map.of("onHand",value,"version",0,"reason","검사","requestId",UUID.randomUUID().toString()),csrfToken(client)).statusCode());
        }
        for(Object value:List.of(-1,"0",true,0.5,9007199254740991L)) {
            assertEquals(400,accountRequest(client,"PUT",path,Map.of("onHand",1,"version",value,"reason","검사","requestId",UUID.randomUUID().toString()),csrfToken(client)).statusCode());
        }
        assertEquals(403,accountRequest(client,"PUT",path,Map.of("onHand",1,"version",0,"reason","검사","requestId",UUID.randomUUID().toString()),null).statusCode());
        UUID stranger=(UUID)skuFixture("stock-stranger").get("owner");
        assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->inventory.list(stranger,product.id(),false,0)).status());
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->inventory.list(owner,product.id(),true,0));
        inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"입고",UUID.randomUUID()));
        jdbc.update("UPDATE marketplace.inventory_balance SET reserved=4 WHERE sku_id=:id",Map.of("id",sku.id()));
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(3L,1L,"예약 하한",UUID.randomUUID()))).status());
        var result=inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(4L,1L,"실사",UUID.randomUUID()));
        assertEquals(0,result.available()); assertEquals(4,result.reserved());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=3 WHERE sku_id=:id",Map.of("id",sku.id())));
        assertEquals(400,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.history(owner,product.id(),sku.id(),false,-1)).status());
    }

    @Test
    void inventoryConcurrentKeysHaveOneEffectAndAuditFailureRollsBack() throws Exception {
        var f=skuFixture("stock-race"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        var input=new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"동시 재전송",UUID.randomUUID());
        assertEquals(List.of("ok","ok"),race(()->{ inventory.adjust(owner,product.id(),sku.id(),false,input);return "ok"; }));
        assertEquals(1,inventory.history(owner,product.id(),sku.id(),false,0).items().size());
        var attempts=race(()->{ try { inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(20L,1L,"동시 조정",UUID.randomUUID()));return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException e) { assertEquals(409,e.status());return "conflict"; } });
        assertEquals(1,attempts.stream().filter("ok"::equals).count());
        var failure=new com.example.marketplace.inventory.api.Inventory.Change(30L,2L,"실패",UUID.randomUUID());
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.stock_movement ADD CONSTRAINT p4d_fail CHECK(reason<>'실패') NOT VALID");
        try { assertThrows(DataIntegrityViolationException.class,()->inventory.adjust(owner,product.id(),sku.id(),false,failure)); }
        finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.stock_movement DROP CONSTRAINT p4d_fail"); }
        assertEquals(20,inventory.list(owner,product.id(),false,0).items().getFirst().onHand()); assertEquals(2,inventory.history(owner,product.id(),sku.id(),false,0).items().size());
        assertEquals(3,inventory.adjust(owner,product.id(),sku.id(),false,failure).version());
        assertEquals(1,inventory.adjust(owner,product.id(),sku.id(),false,input).version());
        assertEquals(30,inventory.list(owner,product.id(),false,0).items().getFirst().onHand());
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change(11L,0L,input.reason(),input.requestId()))).status());
        var other=context.getBean(com.example.marketplace.catalog.api.Skus.class).save(owner,product.id(),null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("색","청")),0L,false,0L));
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->inventory.adjust(owner,product.id(),other.id(),false,input)).status());
    }

    @Test
    void inventoryAdministratorPermissionIsRecheckedAfterProductLockWait() throws Exception {
        var f=skuFixture("stock-admin"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        UUID admin=activeMember("stock-admin@example.com"); jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        assertEquals(0,inventory.list(admin,product.id(),true,0).items().getFirst().onHand());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.catalog_product WHERE id=:id FOR UPDATE",Map.of("id",product.id()),UUID.class);
                pending.set(executor.submit(()->{ try { inventory.adjust(admin,product.id(),sku.id(),true,new com.example.marketplace.inventory.api.Inventory.Change(5L,0L,"운영",UUID.randomUUID()));return "ok"; } catch(com.example.marketplace.identity.api.AccessRejected rejected) { return "denied"; } }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%catalog_product%FOR UPDATE%')",Map.of(),Boolean.class));
                    if(waiting) { break; } try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertTrue(waiting);jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            });
            assertEquals("denied",pending.get().get(5,TimeUnit.SECONDS));assertEquals(0,inventory.list(owner,product.id(),false,0).items().getFirst().onHand());
        }
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')",Map.of("id",admin));
        var saved=inventory.adjust(admin,product.id(),sku.id(),true,new com.example.marketplace.inventory.api.Inventory.Change(5L,0L,"운영 실사",UUID.randomUUID()));assertEquals(5,saved.onHand());assertEquals(admin,inventory.history(admin,product.id(),sku.id(),true,0).items().getFirst().actorId());
    }

    @Test
    void inventoryGlobalRequestKeyCollisionRollsBackOtherOwnersBalance() throws Exception {
        var a=skuFixture("stock-global-a");var b=skuFixture("stock-global-b");
        UUID ownerA=(UUID)a.get("owner"),ownerB=(UUID)b.get("owner");
        var productA=(com.example.marketplace.catalog.api.Products.Product)a.get("product");var productB=(com.example.marketplace.catalog.api.Products.Product)b.get("product");
        var skus=context.getBean(com.example.marketplace.catalog.api.Skus.class);var skuA=skus.list(ownerA,productA.id(),0).items().getFirst();var skuB=skus.list(ownerB,productB.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);var input=new com.example.marketplace.inventory.api.Inventory.Change(10L,0L,"global race",UUID.randomUUID());
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.p4d_gate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.reason='global race' THEN PERFORM pg_advisory_xact_lock(20260404); END IF; RETURN NEW; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER p4d_gate BEFORE INSERT ON marketplace.stock_movement FOR EACH ROW EXECUTE FUNCTION marketplace.p4d_gate()");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(20260404)");
                for(int n=0;n<2;n++) { final int index=n; futures.add(executor.submit(()->{ try { inventory.adjust(index==0?ownerA:ownerB,index==0?productA.id():productB.id(),index==0?skuA.id():skuB.id(),false,input);return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException e) { assertEquals(409,e.status()); return "conflict"; } })); }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO marketplace.stock_movement%'",Map.of(),Integer.class);
                    if(waiting==2) { break; } try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertEquals(2,waiting,"Both independent transactions must reach insert before release");
            });
            var results=List.of(futures.get(0).get(5,TimeUnit.SECONDS),futures.get(1).get(5,TimeUnit.SECONDS));assertEquals(1,results.stream().filter("ok"::equals).count());
            assertEquals(10,inventory.list(ownerA,productA.id(),false,0).items().getFirst().onHand()+inventory.list(ownerB,productB.id(),false,0).items().getFirst().onHand());
            assertEquals(1,inventory.history(ownerA,productA.id(),skuA.id(),false,0).items().size()+inventory.history(ownerB,productB.id(),skuB.id(),false,0).items().size());
        } finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER p4d_gate ON marketplace.stock_movement");jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.p4d_gate()"); }
    }

    @Test
    void inventoryWritesRecheckSuspensionAndArchiveAfterActualLockWait() throws Exception {
        for(boolean member:List.of(true,false)) {
            String suffix=member?"stock-wait-member":"stock-wait-product";
            var f=skuFixture(suffix); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
            var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
            var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-"+suffix+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
            String token=csrfToken(client); String table=member?"identity_user":"catalog_product"; UUID lock=member?owner:product.id();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);
                    future.set(executor.submit(()->accountRequest(client,"PUT","/api/seller/products/"+product.id()+"/inventory/"+sku.id(),Map.of("onHand",5,"version",0,"reason","잠금 검사","requestId",UUID.randomUUID().toString()),token)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+(member?"%FOR NO KEY UPDATE%":"%FOR UPDATE%")),Boolean.class));
                        if(waiting) { break; }
                        try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertTrue(waiting,"Inventory request must wait on "+table);
                    jdbc.update("UPDATE marketplace."+table+" SET state=:state WHERE id=:id",Map.of("id",lock,"state",member?"SUSPENDED":"ARCHIVED"));
                });
                var response=future.get().get(5,TimeUnit.SECONDS); assertEquals(409,response.statusCode(),response.body());
                assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.stock_movement WHERE sku_id=:id",Map.of("id",sku.id()),Integer.class));
            }
        }
    }
}
