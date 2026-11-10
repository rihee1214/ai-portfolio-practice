package com.example.marketplace.dbtest;


import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaIT extends PostgresContractFixture {
    @Test
    void mediaStoreOwnershipCsrfAndPublicProjectionAreEnforced() throws Exception {
        var f=skuFixture("media-access"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var other=skuFixture("media-other"); UUID outsider=(UUID)other.get("owner"); var media=context.getBean(com.example.marketplace.media.api.Media.class);
        byte[] png=mediaPng(); var asset=media.upload(owner,"products",product.id(),0,0,"상품","x.png",png);
        assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->media.list(outsider,"products",product.id())).status());
        assertEquals(404,assertThrows(com.example.marketplace.catalog.api.CatalogException.class,()->media.upload(outsider,"products",product.id(),1,0,"타인","x.png",png)).status());
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-media-access@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String path="/api/media/products/"+product.id();
        assertEquals(403,mediaUpload(client,path+"/1",png,"x.png",0,"CSRF",false).statusCode());
        assertEquals(400,mediaUpload(client,path+"/5",png,"x.png",0,"범위",true).statusCode());
        assertEquals(400,mediaUpload(client,path+"/1",png,"x.html",0,"형식",true).statusCode());
        assertEquals(400,mediaUpload(client,path+"/1",png,"x.png",0," ",true).statusCode());
        var oversize=mediaUpload(client,path+"/1",new byte[5*1024*1024+1],"x.png",0,"크기",true); assertTrue(oversize.statusCode()==400||oversize.statusCode()==413,oversize.body());
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        products.save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        assertTrue(media.content(null,asset.assetId()).length>0);
        jdbc.update("UPDATE marketplace.catalog_category SET active=false WHERE id=:id",Map.of("id",product.categoryId())); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,asset.assetId()));
        jdbc.update("UPDATE marketplace.catalog_category SET active=true WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product.id())); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,asset.assetId())); assertTrue(media.content(owner,asset.assetId()).length>0);
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=false WHERE id=:id",Map.of("id",product.id()));
        var store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner);
        var storeAsset=media.upload(owner,"stores",store.id(),0,0,"매대","x.png",png); assertEquals(1,media.published("stores",store.slug()).size());
        assertEquals(404,assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.upload(outsider,"stores",store.id(),0,1,"타인","x.png",png)).status());
        jdbc.update("UPDATE marketplace.seller_store SET visible=false,state='SUSPENDED' WHERE id=:id",Map.of("id",store.id()));
        assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,storeAsset.assetId())); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(null,asset.assetId()));
        assertTrue(media.content(owner,storeAsset.assetId()).length>0);
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->media.delete(owner,"stores",store.id(),0,1));
        jdbc.update("UPDATE marketplace.seller_store SET visible=true,state='OPEN' WHERE id=:id",Map.of("id",store.id()));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",owner)); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(owner,asset.assetId()));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner)); assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(owner,storeAsset.assetId()));
    }

    @Test
    void mediaConcurrentCreateReplaceDeleteHasOneWinner() throws Exception {
        var f=skuFixture("media-race"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var media=context.getBean(com.example.marketplace.media.api.Media.class); byte[] png=mediaPng();
        var created=race(()->{ try { media.upload(owner,"products",product.id(),0,0,"동시","x.png",png); return "ok"; } catch(com.example.marketplace.media.api.MediaException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count()); var first=media.list(owner,"products",product.id()).getFirst();
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var changed=race(()->{ try { if(sequence.incrementAndGet()==1) { media.upload(owner,"products",product.id(),0,1,"교체","x.png",png); } else { media.delete(owner,"products",product.id(),0,1); } return "ok"; } catch(com.example.marketplace.media.api.MediaException e) { assertEquals(409,e.status()); return "conflict"; } });
        assertEquals(1,changed.stream().filter("ok"::equals).count()); assertEquals(2,media.list(owner,"products",product.id()).getFirst().version());
        assertThrows(com.example.marketplace.media.api.MediaException.class,()->media.content(owner,first.assetId()));
    }

    @Test
    void mediaRollbackAndCleanupKeepCurrentReferencesAndRetryPartialFailure() throws Exception {
        var f=skuFixture("media-cleanup"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var media=context.getBean(com.example.marketplace.media.api.Media.class); var cleanup=context.getBean(com.example.marketplace.media.infrastructure.JdbcMedia.class);
        var root=java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("marketplace.media.root")); byte[] png=mediaPng();
        var old=media.upload(owner,"products",product.id(),0,0,"첫사진","x.png",png);
        var rolled=new java.util.concurrent.atomic.AtomicReference<UUID>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{ rolled.set(media.upload(owner,"products",product.id(),0,1,"롤백","x.png",png).assetId()); status.setRollbackOnly(); });
        assertFalse(java.nio.file.Files.exists(root.resolve(rolled.get()+".png"))); assertEquals(old.assetId(),media.list(owner,"products",product.id()).getFirst().assetId());
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.media_slot ADD CONSTRAINT p4c_reject_slot CHECK(alt<>'실패') NOT VALID");
        long before; try(var files=java.nio.file.Files.list(root)) { before=files.count(); }
        try { assertThrows(DataIntegrityViolationException.class,()->media.upload(owner,"products",product.id(),0,1,"실패","x.png",png)); }
        finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.media_slot DROP CONSTRAINT p4c_reject_slot"); }
        try(var files=java.nio.file.Files.list(root)) { assertEquals(before,files.count()); }
        var current=media.upload(owner,"products",product.id(),0,1,"현재","x.png",png);
        var aged=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        java.nio.file.Files.setLastModifiedTime(root.resolve(current.assetId()+".png"),aged);
        jdbc.update("UPDATE marketplace.media_asset SET retired_at=clock_timestamp()-interval '2 days' WHERE id=:id",Map.of("id",old.assetId()));
        UUID orphan=UUID.randomUUID(),recent=UUID.randomUUID(); java.nio.file.Files.write(root.resolve(orphan+".png"),png); java.nio.file.Files.setLastModifiedTime(root.resolve(orphan+".png"),aged); java.nio.file.Files.write(root.resolve(recent+".png"),png);
        java.nio.file.Files.writeString(root.resolve("untouched.txt"),"keep"); java.nio.file.Files.setLastModifiedTime(root.resolve("untouched.txt"),aged);
        jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.media_cleanup_blocker(id uuid REFERENCES marketplace.media_asset(id))"); jdbc.update("INSERT INTO marketplace.media_cleanup_blocker VALUES(:id)",Map.of("id",old.assetId()));
        assertThrows(DataIntegrityViolationException.class,cleanup::cleanup); assertFalse(java.nio.file.Files.exists(root.resolve(old.assetId()+".png")));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE id=:id",Map.of("id",old.assetId()),Integer.class));
        jdbc.update("DELETE FROM marketplace.media_cleanup_blocker WHERE id=:id",Map.of("id",old.assetId())); cleanup.cleanup(); cleanup.cleanup();
        assertFalse(java.nio.file.Files.exists(root.resolve(orphan+".png"))); assertTrue(java.nio.file.Files.exists(root.resolve(recent+".png"))); assertTrue(java.nio.file.Files.exists(root.resolve("untouched.txt"))); assertTrue(java.nio.file.Files.exists(root.resolve(current.assetId()+".png")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE id=:id",Map.of("id",old.assetId()),Integer.class));
    }

    @Test
    void mediaOuterTransactionAndAgedInFlightFileDoNotDeadlockOrGetCollected() throws Exception {
        var f=skuFixture("media-outer"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var media=context.getBean(com.example.marketplace.media.api.Media.class); byte[] png=mediaPng();
        var root=java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("marketplace.media.root"));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                context.getBean(com.example.marketplace.catalog.api.Products.class).lockForMedia(owner,product.id());
                pending.set(executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(other->{
                    jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout='3s'");
                    media.upload(owner,"products",product.id(),1,0,"후속","x.png",png);
                })));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%'))",Map.of(),Boolean.class));
                    if(waiting) { break; }
                    try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertTrue(waiting);
                var current=media.upload(owner,"products",product.id(),0,0,"진행중","x.png",png);
                try { java.nio.file.Files.setLastModifiedTime(root.resolve(current.assetId()+".png"),java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2)))); }
                catch(java.io.IOException e) { throw new IllegalStateException(e); }
                // Same REQUIRED transaction still protects the aged, not-yet-committed file.
                context.getBean(com.example.marketplace.media.infrastructure.JdbcMedia.class).cleanup();
                assertTrue(java.nio.file.Files.exists(root.resolve(current.assetId()+".png")));
            });
            pending.get().get(5,TimeUnit.SECONDS);
            assertEquals(2,media.list(owner,"products",product.id()).stream().filter(row->row.assetId()!=null).count());
        }
    }

    @Test
    void mediaReaderWaitingForSingleConnectionDoesNotHoldFileMonitor() throws Exception {
        var f=skuFixture("media-pool"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var asset=context.getBean(com.example.marketplace.media.api.Media.class).upload(owner,"products",product.id(),0,0,"연결풀","x.png",mediaPng());
        var files=context.getBean(com.example.marketplace.media.infrastructure.LocalImages.class);
        try(var pool=new com.zaxxer.hikari.HikariDataSource(); var executor=Executors.newFixedThreadPool(2)) {
            pool.setJdbcUrl(System.getenv("D2_DB_URL")); pool.setUsername(System.getenv("D2_DB_USERNAME")); pool.setPassword(System.getenv("D2_DB_PASSWORD"));
            pool.setMaximumPoolSize(1); pool.setMinimumIdle(1); pool.setConnectionTimeout(2000);
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool);
            var media=new com.example.marketplace.media.infrastructure.JdbcMedia(new NamedParameterJdbcTemplate(pool),context.getBean(com.example.marketplace.catalog.api.Products.class),context.getBean(com.example.marketplace.seller.api.Stores.class),files,new com.example.marketplace.media.infrastructure.ImageNormalizer(),manager);
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<byte[]>>();
            new TransactionTemplate(manager).executeWithoutResult(status->{
                pending.set(executor.submit(()->media.content(owner,asset.assetId())));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0&&System.nanoTime()<deadline) {
                    try { Thread.sleep(10); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertEquals(1,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                try { assertTrue(executor.submit(()->{ synchronized(files) { return true; } }).get(500,TimeUnit.MILLISECONDS)); }
                catch(Exception e) { throw new IllegalStateException("Connection waiter must not hold file monitor",e); }
            });
            assertTrue(pending.get().get(3,TimeUnit.SECONDS).length>0);
        }
    }

    @Test
    void mediaOrphanScanWaitingForSingleConnectionDoesNotHoldFileMonitor() throws Exception {
        var files=context.getBean(com.example.marketplace.media.infrastructure.LocalImages.class);
        var root=java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("marketplace.media.root"));
        var orphan=root.resolve(UUID.randomUUID()+".png"); java.nio.file.Files.write(orphan,mediaPng());
        java.nio.file.Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        try(var pool=new com.zaxxer.hikari.HikariDataSource(); var executor=Executors.newFixedThreadPool(2)) {
            pool.setJdbcUrl(System.getenv("D2_DB_URL")); pool.setUsername(System.getenv("D2_DB_USERNAME")); pool.setPassword(System.getenv("D2_DB_PASSWORD"));
            pool.setMaximumPoolSize(1); pool.setMinimumIdle(1); pool.setConnectionTimeout(2000);
            var second=new CountDownLatch(1); var proceed=new CountDownLatch(1); var calls=new java.util.concurrent.atomic.AtomicInteger();
            var gated=new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    if(calls.incrementAndGet()==2) {
                        second.countDown();
                        try { if(!proceed.await(5,TimeUnit.SECONDS)) { throw new java.sql.SQLException("Test gate timed out"); } }
                        catch(InterruptedException e) { throw new java.sql.SQLException(e); }
                    }
                    return pool.getConnection();
                }
                @Override public java.sql.Connection getConnection(String user,String password) throws java.sql.SQLException { return getConnection(); }
            };
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(gated);
            var media=new com.example.marketplace.media.infrastructure.JdbcMedia(new NamedParameterJdbcTemplate(gated),context.getBean(com.example.marketplace.catalog.api.Products.class),context.getBean(com.example.marketplace.seller.api.Stores.class),files,new com.example.marketplace.media.infrastructure.ImageNormalizer(),manager);
            var pending=executor.submit(media::cleanup);
            assertTrue(second.await(5,TimeUnit.SECONDS));
            try {
                new TransactionTemplate(manager).executeWithoutResult(status->{
                    proceed.countDown(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                    while(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0&&System.nanoTime()<deadline) {
                        try { Thread.sleep(10); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertEquals(1,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                    try { assertTrue(executor.submit(()->{ synchronized(files) { return true; } }).get(500,TimeUnit.MILLISECONDS)); }
                    catch(Exception e) { throw new IllegalStateException("Orphan connection waiter must not hold file monitor",e); }
                });
            } finally { proceed.countDown(); }
            pending.get(3,TimeUnit.SECONDS); assertFalse(java.nio.file.Files.exists(orphan));
        }
    }

    @Test
    void mediaUploadReplaceDeleteAndDirectUrlRespectVisibility() throws Exception {
        var f=skuFixture("media-basic"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-media-basic@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        String path="/api/media/products/"+product.id();
        var listing=accountRequest(client,"GET",path,null,null); assertEquals(200,listing.statusCode(),listing.body()); assertEquals(5,authMapper.readTree(listing.body()).size());
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        var uploaded=mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",0,"상품 사진",true); assertEquals(200,uploaded.statusCode(),uploaded.body());
        var asset=authMapper.readTree(uploaded.body()).get("assetId").asText(); String binary="/api/media/"+asset+"/content";
        assertEquals(200,accountRequest(client,"GET",binary,null,null).statusCode()); assertEquals(404,accountRequest(authBrowser(),"GET",binary,null,null).statusCode());
        context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,product.id(),new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),product.slug(),product.title(),"","ACTIVE",0L));
        var publicRead=accountRequest(authBrowser(),"GET",binary,null,null); assertEquals(200,publicRead.statusCode()); assertEquals("no-store",publicRead.headers().firstValue("Cache-Control").orElseThrow()); assertEquals("image/png",publicRead.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(409,mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",0,"낡은 수정",true).statusCode());
        assertEquals(200,mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",1,"교체 사진",true).statusCode()); assertEquals(404,accountRequest(client,"GET",binary,null,null).statusCode());
        assertEquals(200,accountRequest(client,"DELETE",path+"/0?version=2",null,csrfToken(client)).statusCode());
        var deleted=authMapper.readTree(accountRequest(client,"GET",path,null,null).body()).get(0); assertTrue(deleted.get("assetId").isNull()); assertEquals(3,deleted.get("version").asLong());
        assertEquals(409,mediaUpload(client,path+"/0",out.toByteArray(),"picture.png",0,"재생성 충돌",true).statusCode());
    }

    @Test
    void mediaWritesRecheckSuspensionAndArchiveAfterActualLockWait() throws Exception {
        for(boolean member:List.of(true,false)) {
            String suffix=member?"media-wait-member":"media-wait-product";
            var f=skuFixture(suffix); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
            byte[] png=mediaPng();
            var client=authBrowser(); assertEquals(200,authRequest(client,"/api/auth/login",loginForm("p4b-"+suffix+"@example.com",SIGNUP_PASSWORD),csrfToken(client)).statusCode());
            String table=member?"identity_user":"catalog_product"; UUID lock=member?owner:product.id();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);
                    future.set(executor.submit(()->mediaUpload(client,"/api/media/products/"+product.id()+"/0",png,"x.png",0,"잠금 검사",true)));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+(member?"%FOR NO KEY UPDATE%":"%FOR UPDATE%")),Boolean.class));
                        if(waiting) { break; }
                        try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    }
                    assertTrue(waiting,"Media request must wait on "+table);
                    jdbc.update("UPDATE marketplace."+table+" SET state=:state WHERE id=:id",Map.of("id",lock,"state",member?"SUSPENDED":"ARCHIVED"));
                });
                var response=future.get().get(5,TimeUnit.SECONDS); assertEquals(409,response.statusCode(),response.body());
                assertEquals("CONFLICT",authMapper.readTree(response.body()).get("code").asText());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_slot WHERE product_id=:id",Map.of("id",product.id()),Integer.class));
            }
        }
    }
}
