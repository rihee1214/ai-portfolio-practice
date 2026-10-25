package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.api.AccountOperations;
import com.example.marketplace.identity.api.AccountOperations.AddressInput;
import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.application.PasswordRecoveryService;
import com.example.marketplace.identity.infrastructure.JdbcPasswordRecoveryStore;
import com.example.marketplace.identity.infrastructure.LocalPasswordResetMailbox;
import com.example.marketplace.identity.api.SignupException;
import com.example.marketplace.identity.application.SignupService;
import com.example.marketplace.identity.application.TokenSecrets;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.domain.VerificationMailbox;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.example.marketplace.jdbcfixture.AccountRepository;
import com.example.marketplace.jdbcfixture.BatchService;
import com.example.marketplace.jdbcfixture.DedicatedDatabaseTarget;
import com.example.marketplace.jdbcfixture.TransferRejectedException;
import com.example.marketplace.jdbcfixture.TransferService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"db", "c3-fixture", "local-mailbox", "local-http"})
@ExtendWith(OutputCaptureExtension.class)
@ContextConfiguration(initializers = DedicatedPostgresInitializer.class)
@SpringBootTest(classes = MarketplaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/", "spring.sql.init.mode=never", "spring.flyway.clean-disabled=true",
            "server.address=127.0.0.1", "spring.jmx.enabled=true", "spring.jmx.unique-names=true",
            "management.endpoints.jmx.domain=com.example.marketplace.dbfollowup",
            "spring.flyway.locations=classpath:db/migration,classpath:db/c3fixture"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.context.annotation.Import(PostgresJdbcIT.TestMailboxEndpoint.class)
class PostgresJdbcIT {
    private com.example.marketplace.inventory.api.Reservations reservations() {
        return context.getBean(com.example.marketplace.inventory.api.Reservations.class);
    }
    private UUID stockedSku(String suffix,long quantity) {
        var f=skuFixture(suffix); UUID owner=(UUID)f.get("owner");
        var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        UUID sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst().id();
        context.getBean(com.example.marketplace.inventory.api.Inventory.class).adjust(owner,product.id(),sku,false,
            new com.example.marketplace.inventory.api.Inventory.Change(quantity,0L,"예약 검사 입고",UUID.randomUUID()));
        return sku;
    }
    private com.example.marketplace.inventory.api.Reservations.Request reservationRequest(UUID sku,long quantity) {
        return new com.example.marketplace.inventory.api.Reservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),sku,quantity,
            Instant.now().plusSeconds(900).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    private void stockEquals(UUID sku,long onHand,long reserved,long version) {
        var row=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:sku",Map.of("sku",sku));
        assertEquals(onHand,((Number)row.get("on_hand")).longValue());
        assertEquals(reserved,((Number)row.get("reserved")).longValue());
        assertEquals(version,((Number)row.get("version")).longValue());
    }
    private String reservationAttempt(Runnable action) {
        try { action.run();return "ok"; } catch(com.example.marketplace.inventory.api.InventoryException rejected) { return ""+rejected.status(); }
    }
    @Test
    void reservationCrossSkuReverseReservationKeysAreSerialized() throws Exception { reservationCrossKeys(false); }
    @Test
    void reservationCrossSkuReverseOrderItemKeysAreSerialized() throws Exception { reservationCrossKeys(true); }
    private void reservationCrossKeys(boolean orderItems) throws Exception {
        String suffix=orderItems?"items":"ids";UUID a=stockedSku("cross-"+suffix+"-a",10),b=stockedSku("cross-"+suffix+"-b",10);
        UUID x=UUID.randomUUID(),y=UUID.randomUUID(),checkout=UUID.randomUUID();Instant expiry=Instant.now().plusSeconds(900);
        var left=List.of(new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():x,checkout,orderItems?x:UUID.randomUUID(),a,1,expiry),
            new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():y,checkout,orderItems?y:UUID.randomUUID(),a,1,expiry));
        var right=List.of(new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():y,checkout,orderItems?y:UUID.randomUUID(),b,1,expiry),
            new com.example.marketplace.inventory.api.Reservations.Request(orderItems?UUID.randomUUID():x,checkout,orderItems?x:UUID.randomUUID(),b,1,expiry));
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.p4e_insert_barrier() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock_shared(187041); RETURN NEW; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER p4e_insert_barrier AFTER INSERT ON marketplace.inventory_reservation FOR EACH ROW EXECUTE FUNCTION marketplace.p4e_insert_barrier()");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.query("SELECT pg_advisory_xact_lock(187041)",Map.of(),(r,i)->r.getObject(1));
                futures.add(pool.submit(()->reservationAttempt(()->reservations().reserve(left))));
                futures.add(pool.submit(()->reservationAttempt(()->reservations().reserve(right))));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
                while(System.nanoTime()<deadline) {
                    jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");
                    waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event='advisory'",Map.of(),Integer.class);
                    if(waiting==2) { break; } try { Thread.sleep(20); } catch(InterruptedException ex) { throw new IllegalStateException(ex); }
                }
                assertEquals(2,waiting,"both transactions must reach the key/insert barrier before it opens");
            });
            assertThat(List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok","409");
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation WHERE checkout_id=:id",Map.of("id",checkout),Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation_event WHERE sku_id IN (:a,:b)",Map.of("a",a,"b",b),Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT sum(reserved) FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)",Map.of("a",a,"b",b),Long.class));
            assertEquals(List.of(1L,3L),jdbc.queryForList("SELECT version FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b) ORDER BY version",Map.of("a",a,"b",b),Long.class));
            assertEquals(20,jdbc.queryForObject("SELECT sum(on_hand) FROM marketplace.inventory_balance WHERE sku_id IN (:a,:b)",Map.of("a",a,"b",b),Long.class));
        } finally {
            jdbc.getJdbcTemplate().execute("DROP TRIGGER p4e_insert_barrier ON marketplace.inventory_reservation");
            jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.p4e_insert_barrier()");
        }
    }
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
        var ds=context.getBean(DataSource.class);String schema="p4e_upgrade";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("17").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO p4e_upgrade.identity_user SELECT u.* FROM marketplace.identity_user u JOIN marketplace.seller_store s ON s.owner_id=u.id JOIN marketplace.catalog_product p ON p.store_id=s.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.seller_store SELECT s.* FROM marketplace.seller_store s JOIN marketplace.catalog_product p ON p.store_id=s.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.catalog_category SELECT c.* FROM marketplace.catalog_category c JOIN marketplace.catalog_product p ON p.category_id=c.id JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.catalog_product SELECT p.* FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id WHERE k.id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.catalog_sku SELECT * FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.inventory_balance SELECT * FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku));
        jdbc.update("INSERT INTO p4e_upgrade.stock_movement SELECT * FROM marketplace.stock_movement WHERE sku_id=:id",Map.of("id",sku));
        var movement=jdbc.queryForList("SELECT * FROM p4e_upgrade.stock_movement",Map.of());
        var migration=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();
        assertEquals(1,migration.migrate().migrationsExecuted);assertEquals(0,migration.migrate().migrationsExecuted);
        assertEquals(row,jdbc.queryForMap("SELECT * FROM p4e_upgrade.inventory_balance WHERE sku_id=:id",Map.of("id",sku)));
        assertEquals(movement,jdbc.queryForList("SELECT * FROM p4e_upgrade.stock_movement",Map.of()));
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
    void inventoryHistoryPagesAndUpgradePreserveSkuData() {
        var f=skuFixture("stock-upgrade"); UUID owner=(UUID)f.get("owner"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");
        var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).list(owner,product.id(),0).items().getFirst();
        var inventory=context.getBean(com.example.marketplace.inventory.api.Inventory.class);
        for(int i=0;i<25;i++) { inventory.adjust(owner,product.id(),sku.id(),false,new com.example.marketplace.inventory.api.Inventory.Change((long)i+1,(long)i,"실사 "+i,UUID.randomUUID())); }
        jdbc.update("UPDATE marketplace.stock_movement SET occurred_at=clock_timestamp()+interval '1 day' WHERE sku_id=:id AND version=1",Map.of("id",sku.id()));
        var first=inventory.history(owner,product.id(),sku.id(),false,0);var next=inventory.history(owner,product.id(),sku.id(),false,1);
        assertEquals(20,first.items().size());assertTrue(first.hasMore());assertEquals(25,first.items().getFirst().version());assertEquals(6,first.items().getLast().version());assertEquals(5,next.items().size());assertFalse(next.hasMore());assertEquals(1,next.items().getLast().version());
        var ds=context.getBean(DataSource.class); String schema="p4d_upgrade";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("16").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO p4d_upgrade.identity_user SELECT * FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4d_upgrade.seller_store SELECT * FROM marketplace.seller_store WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4d_upgrade.catalog_category SELECT * FROM marketplace.catalog_category WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("INSERT INTO p4d_upgrade.catalog_product SELECT * FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product.id()));
        jdbc.update("INSERT INTO p4d_upgrade.catalog_sku SELECT * FROM marketplace.catalog_sku WHERE id=:id",Map.of("id",sku.id()));
        var upgrade=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();upgrade.migrate();assertEquals(0,upgrade.migrate().migrationsExecuted);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM p4d_upgrade.inventory_balance WHERE sku_id=:id AND on_hand=0 AND reserved=0 AND version=0",Map.of("id",sku.id()),Integer.class));
        assertEquals(sku.code(),jdbc.queryForObject("SELECT code FROM p4d_upgrade.catalog_sku WHERE id=:id",Map.of("id",sku.id()),String.class));
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
    private byte[] mediaPng() throws Exception {
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out); return out.toByteArray();
    }
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
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%identity_user%FOR UPDATE%')",Map.of(),Boolean.class));
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
    private HttpResponse<String> mediaUpload(HttpClient client,String path,byte[] bytes,String filename,long version,String alt,boolean csrf) throws Exception {
        String boundary="p04c-boundary"; var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"+version+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"alt\"\r\n\r\n"+alt+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","multipart/form-data; boundary="+boundary).PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if(csrf) { request.header("X-CSRF-TOKEN",csrfToken(client)); }
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
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
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+"%FOR UPDATE%"),Boolean.class));
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
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+"%FOR UPDATE%"),Boolean.class));
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
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query)",Map.of("query","%"+table+"%FOR UPDATE%"),Boolean.class));
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
    void skuMigrationBackfillsExistingProductsAndCreationRollsBackAtomically() {
        var f=skuFixture("upgrade"); var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product"); UUID owner=(UUID)f.get("owner");
        var ds=context.getBean(DataSource.class); String schema="p4b_upgrade";
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("14").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO p4b_upgrade.identity_user SELECT * FROM marketplace.identity_user WHERE id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4b_upgrade.seller_store SELECT * FROM marketplace.seller_store WHERE owner_id=:id",Map.of("id",owner));
        jdbc.update("INSERT INTO p4b_upgrade.catalog_category SELECT * FROM marketplace.catalog_category WHERE id=:id",Map.of("id",product.categoryId()));
        jdbc.update("INSERT INTO p4b_upgrade.catalog_product SELECT * FROM marketplace.catalog_product WHERE id=:id",Map.of("id",product.id()));
        var upgrade=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();
        upgrade.migrate(); upgrade.migrate();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM p4b_upgrade.catalog_sku WHERE product_id=:id AND options='[]' AND price=0 AND NOT active AND version=0 AND code='sku-'||id::text",Map.of("id",product.id()),Integer.class));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku ADD CONSTRAINT p4b_fail_creation CHECK(false) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"p4b-rollback","rollback","","DRAFT",0L)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.catalog_product WHERE slug='p4b-rollback'",Map.of(),Integer.class));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.catalog_sku DROP CONSTRAINT p4b_fail_creation"); }
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
    private Map<String,Object> skuFixture(String suffix) {
        UUID owner=activeMember("p4b-"+suffix+"@example.com"), category=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        context.getBean(com.example.marketplace.seller.api.Stores.class).save(owner,null,new com.example.marketplace.seller.api.Stores.Input("p4b-"+suffix,"P04B","intro","address","","","",true,0L));
        jdbc.update("INSERT INTO marketplace.catalog_category(id,name) VALUES(:id,:name)",Map.of("id",category,"name","P04B "+suffix));
        var product=context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(category,"p4b-"+suffix,"SKU 상품","","DRAFT",0L));
        return Map.of("owner",owner,"product",product);
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
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%identity_user%FOR UPDATE%')",Map.of(),Boolean.class));
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
    @Test
    void storeCloseIsAtomicConcurrentAndKeepsCapabilityBoundary() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3c-atomic@example.com"), other=activeMember("p3c-other@example.com");
        for(UUID id:List.of(owner,other)) { jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",id)); }
        var input=new com.example.marketplace.seller.api.Stores.Input("atomic-store","모의","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var change=new com.example.marketplace.seller.api.Stores.Change(0L,"😀".repeat(300));
        assertEquals(404,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(other,store.id(),"close",change)).status());
        assertEquals(404,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.history(other,store.id(),false)).status());
        for(String reason:List.of(" ","가".repeat(301))) {
            assertEquals(400,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.transition(owner,store.id(),"close",new com.example.marketplace.seller.api.Stores.Change(0L,reason))).status());
        }
        assertThrows(IllegalStateException.class,()->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.store_operation_event ADD CONSTRAINT p3c_reject_event CHECK (store_id <> '"+store.id()+"'::uuid) NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class,()->service.transition(owner,store.id(),"close",change));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.store_operation_event DROP CONSTRAINT p3c_reject_event"); }
        assertEquals(store,service.own(owner));
        assertTrue(service.history(owner,store.id(),false).isEmpty());
        var results=race(()->{ try { service.transition(owner,store.id(),"close",change); return "ok"; }
            catch(com.example.marketplace.seller.api.StoreException failure) { assertEquals(409,failure.status()); return "conflict"; } });
        assertEquals(1,results.stream().filter("ok"::equals).count());
        assertEquals("CLOSED",service.own(owner).state());
        assertFalse(service.own(owner).visible());
        assertEquals(1,service.history(owner,store.id(),false).size());
        assertEquals(change.reason(),service.history(owner,store.id(),false).getFirst().reason());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,store.id(),input));
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,null,input));
        assertFalse(service.capabilities(owner).manageCatalog());
        assertFalse(service.capabilities(owner).newSales());
        new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.EXISTING_TRADES));
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->service.requireCapability(owner,com.example.marketplace.seller.api.Stores.Capability.NEW_SALES)));
    }
    @Test
    void storeLifecycleHttpKeepsBuyingAndRestrictsNewSales() throws Exception {
        UUID owner=activeMember("p3c-owner@example.com"), admin=activeMember("p3c-admin@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')",Map.of("id",admin));
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var input=new com.example.marketplace.seller.api.Stores.Input("lifecycle-store","모의 매대","소개","가상 주소","","","",true,0L);
        var store=service.save(owner,null,input);
        var buyer=authBrowser(); var operator=authBrowser();
        assertEquals(200,authRequest(buyer,"/api/auth/login",loginForm("p3c-owner@example.com",SIGNUP_PASSWORD),csrfToken(buyer)).statusCode());
        assertEquals(200,authRequest(operator,"/api/auth/login",loginForm("p3c-admin@example.com",SIGNUP_PASSWORD),csrfToken(operator)).statusCode());
        String path="/api/admin/accounts/stores/"+store.id();
        try {
            assertEquals(403,accountRequest(buyer,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),csrfToken(buyer)).statusCode());
            assertEquals(403,accountRequest(operator,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),null).statusCode());
            assertEquals(200,accountRequest(operator,"POST",path+"/suspend",Map.of("version",0,"reason","모의 정지"),csrfToken(operator)).statusCode());
            assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/lifecycle-store",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/account/profile",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/seller/stores",null,null).statusCode());
            var capabilities=authMapper.readTree(accountRequest(buyer,"GET","/api/seller/capabilities",null,null).body());
            assertFalse(capabilities.get("manageCatalog").asBoolean());
            assertFalse(capabilities.get("newSales").asBoolean());
            assertTrue(capabilities.get("existingTrades").asBoolean());
            assertEquals(200,accountRequest(operator,"POST",path+"/restore",Map.of("version",1,"reason","모의 복구"),csrfToken(operator)).statusCode());
            assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/lifecycle-store",null,null).statusCode());
            assertEquals(200,accountRequest(buyer,"POST","/api/seller/stores/"+store.id()+"/close",Map.of("version",2,"reason","모의 폐점"),csrfToken(buyer)).statusCode());
            assertEquals(409,accountRequest(operator,"POST",path+"/restore",Map.of("version",3,"reason","재개 불가"),csrfToken(operator)).statusCode());
            assertEquals(200,accountRequest(buyer,"GET","/api/account/profile",null,null).statusCode());
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.store_operation_event WHERE store_id=:id",Map.of("id",store.id()),Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'",Map.of("id",admin));
        }
    }
    @Test
    void storeInputLimitsAndInvalidUpdatesLeaveSavedValuesUnchanged() {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3b-limits@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var max=new com.example.marketplace.seller.api.Stores.Input("s".repeat(63),"😀".repeat(80),"소".repeat(1000),"주".repeat(200),"공".repeat(1000),"i".repeat(100),"그".repeat(120),true,0L);
        var saved=service.save(owner,null,max);
        assertEquals(max.name(),saved.name());
        assertEquals(max.imageAlt(),service.published(max.slug()).imageAlt());
        var invalid=List.of(
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name()+"x",max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction()+"x",max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address()+"x",max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice()+"x",max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice(),max.imageKey()+"x",max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),max.name(),max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt()+"x",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug()+"x",max.name(),max.introduction(),max.address(),max.notice(),max.imageKey(),max.imageAlt(),true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug()," ",max.introduction(),max.address(),"","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름"," ",max.address(),"","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개"," ","","","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개","주소","","key","",true,0L),
            new com.example.marketplace.seller.api.Stores.Input(max.slug(),"이름","소개","주소","","","alt",true,0L));
        for(var input:invalid) {
            assertEquals(400,assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,saved.id(),input)).status());
            assertEquals(saved,service.own(owner));
        }
    }
    @Test
    void storeConcurrentCreateAndUpdateAndValidation() throws Exception {
        var service=context.getBean(com.example.marketplace.seller.api.Stores.class);
        UUID owner=activeMember("p3b-race@example.com");
        var input=new com.example.marketplace.seller.api.Stores.Input("race-store","모의","소개","가상 주소","","asset-1","가상 그림",false,0L);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.save(owner,null,input));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",owner));
        var created=race(()->{ try { service.save(owner,null,input); return "ok"; } catch(com.example.marketplace.seller.api.StoreException ex) { assertEquals(409,ex.status()); return "conflict"; } });
        assertEquals(1,created.stream().filter("ok"::equals).count());
        var current=service.own(owner);
        var updated=race(()->{ try { service.save(owner,current.id(),input); return "ok"; } catch(com.example.marketplace.seller.api.StoreException ex) { assertEquals(409,ex.status()); return "conflict"; } });
        assertEquals(1,updated.stream().filter("ok"::equals).count());
        assertEquals(1,service.own(owner).version());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,current.id(),new com.example.marketplace.seller.api.Stores.Input("changed-slug","모의","소개","주소","","","",false,1L)));
        for(String slug:List.of("aa","UPPER","../bad","-bad","bad-")) {
            assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,null,new com.example.marketplace.seller.api.Stores.Input(slug,"모의","소개","주소","","","",false,0L)));
        }
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.save(owner,current.id(),new com.example.marketplace.seller.api.Stores.Input("race-store","모의","소개","주소","","https://external","그림",false,1L)));
        assertEquals(1,service.own(owner).version());
        assertThrows(com.example.marketplace.seller.api.StoreException.class,()->service.published("race-store"));
        jdbc.update("UPDATE marketplace.identity_user SET state='WITHDRAWN' WHERE id=:id",Map.of("id",owner));
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->service.save(owner,current.id(),input));
    }
    @Test
    void storeHttpOwnershipVisibilityAndVersion() throws Exception {
        UUID owner = activeMember("p3b-owner@example.com");
        UUID other = activeMember("p3b-other@example.com");
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", owner));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", other));
        var browser = authBrowser();
        var stranger = authBrowser();
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p3b-owner@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        assertEquals(200,authRequest(stranger,"/api/auth/login",loginForm("p3b-other@example.com",SIGNUP_PASSWORD),csrfToken(stranger)).statusCode());
        var fields = new java.util.HashMap<String,Object>(Map.of("slug","sample-store","name","모의 매대","introduction","가상 소개","address","모의 주소","notice","","imageKey","","imageAlt","","visible",false,"version",0));
        assertEquals(403, accountRequest(browser,"POST","/api/seller/stores", fields,null).statusCode());
        var created = accountRequest(browser,"POST","/api/seller/stores",fields,csrfToken(browser));
        assertEquals(201,created.statusCode());
        var store = new tools.jackson.databind.ObjectMapper().readTree(created.body());
        String path = "/api/seller/stores/" + store.get("id").asText();
        assertEquals(409,accountRequest(browser,"POST","/api/seller/stores",fields,csrfToken(browser)).statusCode());
        assertEquals(409,accountRequest(stranger,"POST","/api/seller/stores",fields,csrfToken(stranger)).statusCode());
        assertEquals(404,accountRequest(stranger,"PUT",path,fields,csrfToken(stranger)).statusCode());
        assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null).statusCode());
        fields.put("visible",true);
        assertEquals(200,accountRequest(browser,"PUT",path,fields,csrfToken(browser)).statusCode());
        assertEquals(409,accountRequest(browser,"PUT",path,fields,csrfToken(browser)).statusCode());
        var published = accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null);
        assertEquals(200,published.statusCode());
        assertFalse(published.body().contains(owner.toString()));
        assertFalse(published.body().contains("version"));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));
        assertEquals(404,accountRequest(HttpClient.newHttpClient(),"GET","/api/stores/sample-store",null,null).statusCode());
    }

    @Test
    void sellerDecisionsRejectInvalidTransitionsAndRollbackRoleAndHistory() {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID operator = activeMember("p3-reviewer@example.com");
        UUID applicant = activeMember("p3-rejected@example.com");
        var input = new com.example.marketplace.seller.api.SellerApplications.Input("가상 매대", "가상 계획");
        var first = service.submit(applicant, input);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> service.decide(operator, first.id(), "APPROVED", "사유"));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", operator));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", operator));
        try {
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "PENDING", "사유"));
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "APPROVED", " "));
            var rejected = service.decide(operator, first.id(), "REJECTED", "계획 보완 필요");
            assertEquals("REJECTED", rejected.status());
            assertEquals("계획 보완 필요", rejected.events().getLast().reason());
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, first.id(), "APPROVED", "사유"));
            var second = service.submit(applicant, input);
            assertEquals(2, service.own(applicant, 0).items().size());
            var self = service.submit(operator, input);
            assertThrows(com.example.marketplace.seller.api.SellerApplicationException.class, () -> service.decide(operator, self.id(), "APPROVED", "자기 심사"));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.seller_application_event ADD CONSTRAINT p3_reject_event CHECK (state <> 'APPROVED') NOT VALID");
            try {
                assertThrows(DataIntegrityViolationException.class, () -> service.decide(operator, second.id(), "APPROVED", "승인"));
            } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.seller_application_event DROP CONSTRAINT p3_reject_event"); }
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM marketplace.seller_application WHERE id=:id", Map.of("id", second.id()), String.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.seller_application_event WHERE application_id=:id", Map.of("id", second.id()), Integer.class));
            assertEquals(List.of("BUYER"), jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id", Map.of("id", applicant), String.class));
            jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", applicant));
            assertThrows(AccountException.class, () -> service.decide(operator, second.id(), "APPROVED", "승인"));
            jdbc.update("UPDATE marketplace.identity_user SET state='WITHDRAWN' WHERE id=:id", Map.of("id", applicant));
            assertThrows(AccountException.class, () -> service.decide(operator, second.id(), "REJECTED", "반려"));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", operator));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", operator));
        }
    }

    @Test
    void sellerConcurrentApplicationsAndDecisionsHaveOneWinner() throws Exception {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID applicant = activeMember("p3-race-buyer@example.com");
        UUID operator = activeMember("p3-race-admin@example.com");
        var input = new com.example.marketplace.seller.api.SellerApplications.Input("경쟁 매대", "모의 판매");
        var submitted = race(() -> {
            try { service.submit(applicant, input); return "ok"; }
            catch (com.example.marketplace.seller.api.SellerApplicationException conflict) { return "blocked"; }
        });
        assertEquals(1, submitted.stream().filter("ok"::equals).count());
        assertEquals(1, submitted.stream().filter("blocked"::equals).count());
        UUID id = service.own(applicant, 0).items().getFirst().id();
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", operator));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", operator));
        try {
            var turn = new java.util.concurrent.atomic.AtomicInteger();
            var decisions = race(() -> {
                try { service.decide(operator, id, turn.getAndIncrement() == 0 ? "APPROVED" : "REJECTED", "경쟁 심사"); return "ok"; }
                catch (com.example.marketplace.seller.api.SellerApplicationException conflict) { return "blocked"; }
            });
            assertEquals(1, decisions.stream().filter("ok"::equals).count());
            assertEquals(1, decisions.stream().filter("blocked"::equals).count());
            var result = service.own(applicant, 0).items().getFirst();
            assertEquals(2, result.events().size());
            assertEquals(result.status(), result.events().getLast().status());
            assertEquals(result.status().equals("APPROVED") ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", applicant), Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", operator));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", operator));
        }
    }

    @Test
    void sellerApplicationOwnershipPaginationAndNarrowSecurityException() throws Exception {
        var service = context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        UUID buyer = activeMember("p3-boundary@example.com");
        UUID other = activeMember("p3-other@example.com");
        var foreign = service.submit(other, new com.example.marketplace.seller.api.SellerApplications.Input("다른 매대", "가상 계획"));
        var browser = authBrowser();
        assertEquals(401, accountRequest(browser, "GET", "/api/seller/applications", null, null).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("p3-boundary@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertTrue(service.own(buyer, 0).items().isEmpty());
        for (String path : List.of("/api/seller/applications/" + foreign.id(), "/api/seller/products", "/api/admin/accounts/seller-applications")) {
            assertEquals(403, accountRequest(browser, "GET", path, null, null).statusCode());
        }
        assertEquals(403, accountRequest(browser, "PUT", "/api/seller/applications", Map.of(), csrfToken(browser)).statusCode());
        assertEquals(400, accountRequest(browser, "POST", "/api/seller/applications", Map.of("displayName", " ", "description", "bad"), csrfToken(browser)).statusCode());
        assertEquals(400, accountRequest(browser, "GET", "/api/seller/applications?page=-1", null, null).statusCode());
        for (int i = 0; i < 21; i++) {
            UUID applicationId = UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.seller_application(id,applicant_id,display_name,description,state) VALUES(:id,:member,'목록 매대','모의 자료','REJECTED')",
                    Map.of("id", applicationId, "member", buyer));
            for (String state : List.of("PENDING", "REJECTED")) {
                jdbc.update("INSERT INTO marketplace.seller_application_event(id,application_id,actor_id,state,reason) VALUES(:id,:application,:member,:state,'모의 목록 이력')",
                        Map.of("id", UUID.randomUUID(), "application", applicationId, "member", buyer, "state", state));
            }
        }
        assertEquals(20, service.own(buyer, 0).items().size());
        assertTrue(service.own(buyer, 0).hasMore());
        assertEquals(1, service.own(buyer, 1).items().size());
        assertFalse(service.own(buyer, 1).hasMore());
        assertEquals(1, service.own(other, 0).items().size());
        assertThrows(IllegalStateException.class, () -> context.getBean(com.example.marketplace.identity.api.SellerMembership.class).lockActiveBuyer(buyer));
    }

    @Test
    void sellerApplicationHttpSubmissionReviewAndBuyerRolePreserved() throws Exception {
        UUID buyerId = activeMember("p3-buyer@example.com");
        UUID adminId = activeMember("p3-admin@example.com");
        var buyer = authBrowser();
        var admin = authBrowser();
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("p3-buyer@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        assertEquals(200, authRequest(admin, "/api/auth/login", loginForm("p3-admin@example.com", SIGNUP_PASSWORD), csrfToken(admin)).statusCode());
        String own = "/api/seller/applications";
        var input = Map.of("displayName", "모의 매대", "description", "가상 상품 판매 계획", "applicantId", adminId.toString(), "status", "APPROVED");
        assertEquals(403, accountRequest(buyer, "POST", own, input, null).statusCode());
        var submitted = accountRequest(buyer, "POST", own, input, csrfToken(buyer));
        assertEquals(201, submitted.statusCode());
        var body = authMapper.readTree(submitted.body());
        String id = body.get("id").asText();
        assertEquals(buyerId.toString(), body.get("applicantId").asText());
        assertEquals("PENDING", body.get("status").asText());
        assertEquals(409, accountRequest(buyer, "POST", own, input, csrfToken(buyer)).statusCode());
        String review = "/api/admin/accounts/seller-applications/" + id + "/decision";
        var approved = Map.of("decision", "APPROVED", "reason", "모의 심사 완료");
        assertEquals(403, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", adminId));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", adminId));
        try {
            assertEquals(403, accountRequest(admin, "POST", review, approved, null).statusCode());
            assertEquals(200, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
            assertEquals(409, accountRequest(admin, "POST", review, approved, csrfToken(admin)).statusCode());
            assertEquals(List.of("BUYER", "SELLER"), authMapper.readTree(authRequest(buyer, "/api/auth/me", null, null).body()).get("roles")
                    .valueStream().map(node -> node.asText()).sorted().toList());
            assertEquals(200, authRequest(buyer, "/api/seller/access", null, null).statusCode());
            var listing = authMapper.readTree(accountRequest(buyer, "GET", own, null, null).body());
            assertEquals(1, listing.get("items").size());
            assertEquals(2, listing.get("items").get(0).get("events").size());
            assertEquals(409, accountRequest(buyer, "POST", own, input, csrfToken(buyer)).statusCode());
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", adminId));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", adminId));
        }
    }

    @Test
    void accountStateLifecycleRevokesSessionsAndPreservesIdentityReference() throws Exception {
        UUID admin = activeMember("d-admin@example.com");
        UUID target = activeMember("d-target@example.com");
        var operator = authBrowser();
        var buyer = authBrowser();
        var second = authBrowser();
        assertEquals(200, authRequest(operator, "/api/auth/login", loginForm("d-admin@example.com", SIGNUP_PASSWORD), csrfToken(operator)).statusCode());
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        assertEquals(200, authRequest(second, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(second)).statusCode());
        String path = "/api/admin/accounts/" + target;
        var reason = Map.of("reason", "테스트 운영 조치");
        assertEquals(403, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", admin));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", admin));
        try {
            assertEquals(403, accountRequest(operator, "POST", path + "/suspend", reason, null).statusCode());
            assertEquals(200, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(401, authRequest(buyer, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(second, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", path + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(200, accountRequest(operator, "POST", path + "/restore", reason, csrfToken(operator)).statusCode());
            assertEquals(401, authRequest(second, "/api/auth/me", null, null).statusCode());
            assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm("d-target@example.com", SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
            accountOperations.saveAddress(target, null, addressInput(true));
            accountOperations.updateProfile(target, "탈퇴자", "01012345678");
            recovery.request("d-target@example.com");
            String resetToken = resetMail("d-target@example.com");
            var withdrawal = Map.of("password", SIGNUP_PASSWORD, "confirmation", "WITHDRAW");
            jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.d_trade_reference (member_id uuid REFERENCES marketplace.identity_user(id), amount integer NOT NULL)");
            jdbc.update("INSERT INTO marketplace.d_trade_reference VALUES(:id,12300)", Map.of("id", target));
            assertEquals(403, accountRequest(buyer, "POST", "/api/account/withdraw", withdrawal, null).statusCode());
            assertEquals(400, accountRequest(buyer, "POST", "/api/account/withdraw", Map.of("password", "wrong", "confirmation", "WITHDRAW"), csrfToken(buyer)).statusCode());
            assertEquals(200, accountRequest(buyer, "POST", "/api/account/withdraw", withdrawal, csrfToken(buyer)).statusCode());
            assertEquals("WITHDRAWN", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(3L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals("", jdbc.queryForObject("SELECT display_name FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            for (String table : List.of("user_credential", "member_address", "email_verification_token", "password_reset_token", "role_assignment")) {
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", target), Integer.class));
            }
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
            assertEquals(12300, jdbc.queryForObject("SELECT amount FROM marketplace.d_trade_reference WHERE member_id=:id", Map.of("id", target), Integer.class));
            assertNull(jdbc.queryForObject("SELECT phone FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertFalse("d-target@example.com".equals(jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class)));
            assertEquals(401, authRequest(buyer, "/api/auth/me", null, null).statusCode());
            assertThrows(SignupException.class, () -> recovery.reset(resetToken, SIGNUP_PASSWORD));
            assertEquals(409, accountRequest(operator, "POST", path + "/restore", reason, csrfToken(operator)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", "/api/admin/accounts/" + admin + "/suspend", reason, csrfToken(operator)).statusCode());
            assertEquals(409, accountRequest(operator, "POST", "/api/account/withdraw", withdrawal, csrfToken(operator)).statusCode());
            UUID replacement = activeMember("d-target@example.com");
            assertFalse(target.equals(replacement));
            assertEquals(target, jdbc.queryForObject("SELECT member_id FROM marketplace.d_trade_reference", Map.of(), UUID.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", admin));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", admin));
        }
    }

    @Test
    void stateChangesRollbackAndSerializeAndCannotBypassServicePermission() throws Exception {
        var service = context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class);
        UUID actor = activeMember("d-service-actor@example.com");
        UUID target = activeMember("d-service-target@example.com");
        UUID pending = signup.register("d-pending@example.com", SIGNUP_PASSWORD);
        assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> service.suspend(actor, target, "reason"));
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", actor));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", actor));
        try {
            assertThrows(AccountException.class, () -> service.suspend(actor, target, " "));
            assertThrows(AccountException.class, () -> service.suspend(actor, pending, "reason"));
            assertThrows(AccountException.class, () -> service.restore(actor, target, "reason"));
            assertThrows(AccountException.class, () -> service.suspend(actor, UUID.randomUUID(), "reason"));
            assertThrows(AccountException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "wrong"));
            var browser = authBrowser();
            assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("d-service-target@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
            recovery.request("d-service-target@example.com");
            accountOperations.saveAddress(target, null, addressInput(true));
            jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.account_state_audit ADD CONSTRAINT d_reject_audit CHECK (reason='impossible') NOT VALID");
            try {
                assertThrows(DataIntegrityViolationException.class, () -> service.suspend(actor, target, "reason"));
                assertThrows(DataIntegrityViolationException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "WITHDRAW"));
            } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.account_state_audit DROP CONSTRAINT d_reject_audit"); }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals(1, accountOperations.addresses(target).size());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", target), Integer.class));
            assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
            accountOperations.updateProfile(target, "보존 이름", "01012345678");
            // Fail after state/session/token/audit/anonymization/address writes, during credential deletion.
            jdbc.getJdbcTemplate().execute("CREATE TABLE marketplace.d_credential_reference (user_id uuid REFERENCES marketplace.user_credential(user_id))");
            jdbc.update("INSERT INTO marketplace.d_credential_reference VALUES(:id)", Map.of("id", target));
            assertThrows(DataIntegrityViolationException.class, () -> service.withdraw(target, SIGNUP_PASSWORD, "WITHDRAW"));
            assertEquals("d-service-target@example.com", accountOperations.profile(target).email());
            assertEquals("보존 이름", accountOperations.profile(target).displayName());
            assertEquals("01012345678", accountOperations.profile(target).phone());
            assertEquals(1, accountOperations.addresses(target).size());
            for (String table : List.of("user_credential", "role_assignment", "password_reset_token", "email_verification_token")) {
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace." + table + " WHERE user_id=:id", Map.of("id", target), Integer.class));
            }
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", target), Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
            assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
            jdbc.update("DELETE FROM marketplace.d_credential_reference WHERE user_id=:id", Map.of("id", target));
            var results = race(() -> {
                try { service.suspend(actor, target, "race"); return "ok"; }
                catch (AccountException exception) { return "blocked"; }
            });
            assertEquals(1, results.stream().filter("ok"::equals).count());
            assertEquals(1, results.stream().filter("blocked"::equals).count());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.account_state_audit WHERE target_id=:id", Map.of("id", target), Integer.class));
        } finally {
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", actor));
            jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", actor));
        }
    }

    @Test
    void initialAdminIsExplicitAtomicAndOnlyOneConcurrentWinner() throws Exception {
        var bootstrap = new com.example.marketplace.identity.infrastructure.InitialAdmin(jdbc, transactionManager);
        String database = jdbc.queryForObject("SELECT current_database()", Map.of(), String.class);
        UUID first = activeMember("bootstrap-first-c@example.com");
        UUID second = activeMember("bootstrap-second-c@example.com");
        UUID pending = signup.register("bootstrap-pending-c@example.com", SIGNUP_PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize(first, database, "wrong"));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(first, "wrong_database", "INITIAL_ADMIN"));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(pending, database, "INITIAL_ADMIN"));
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("bootstrap-first-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", second));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(first, database, "INITIAL_ADMIN"));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", second));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.authorization_audit ADD CONSTRAINT c_reject_audit CHECK (action <> 'INITIAL_ADMIN_GRANTED') NOT VALID");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> bootstrap.initialize(first, database, "INITIAL_ADMIN"));
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.authorization_audit DROP CONSTRAINT c_reject_audit"); }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission", Map.of(), Integer.class));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bootstrap.initialize(first, database, "INITIAL_ADMIN");
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission", Map.of(), Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit", Map.of(), Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", first), Long.class));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        var next = new java.util.concurrent.atomic.AtomicInteger();
        var results = race(() -> {
            UUID candidate = next.getAndIncrement() == 0 ? first : second;
            return runBootstrapCommand(candidate, database, "INITIAL_ADMIN", false) == 0 ? "ok" : "blocked";
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("blocked"::equals).count());
        UUID winner = jdbc.queryForObject("SELECT user_id FROM marketplace.admin_bootstrap WHERE id=1", Map.of(), UUID.class);
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", winner), Integer.class));
        assertEquals(List.of("ADMIN", "BUYER"), jdbc.queryForList("SELECT role FROM marketplace.role_assignment WHERE user_id=:id ORDER BY role", Map.of("id", winner), String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit WHERE source='LOCAL_BOOTSTRAP' AND target_id=:id", Map.of("id", winner), Integer.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(winner, database, "INITIAL_ADMIN"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id", Map.of("id", winner.toString()), Integer.class));
        // Leave completed marker intact: removing ADMIN is not an authorization to bootstrap again.
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", winner));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", winner));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize(second, database, "INITIAL_ADMIN"));
        assertEquals(1, runBootstrapCommand(second, database, "INITIAL_ADMIN", false));
        assertEquals(1, runBootstrapCommand(second, "wrong_database", "INITIAL_ADMIN", false));
        assertEquals(1, runBootstrapCommand(second, database, "wrong", false));
        assertEquals(1, runBootstrapCommand(second, database, "INITIAL_ADMIN", true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.authorization_audit", Map.of(), Integer.class));
    }

    private int runBootstrapCommand(UUID user, String database, String confirmation, boolean forbiddenArgument) throws Exception {
        var classes = java.nio.file.Path.of(MarketplaceApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var build = classes.getParent();
        var jar = build.resolve("marketplace-api-0.0.1-SNAPSHOT.jar");
        var command = new java.util.ArrayList<>(List.of(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.main=com.example.marketplace.identity.infrastructure.InitialAdminCommand", "-cp", jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
        if (forbiddenArgument) { command.add("--spring.flyway.enabled=true"); }
        var output = java.nio.file.Files.createTempFile(build, "bootstrap-", ".txt");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("DB_URL", System.getenv("D2_DB_URL"));
        builder.environment().put("DB_USERNAME", System.getenv("D2_DB_USERNAME"));
        builder.environment().put("DB_PASSWORD", System.getenv("D2_DB_PASSWORD"));
        builder.environment().put("BOOTSTRAP_USER_ID", user.toString());
        builder.environment().put("BOOTSTRAP_EXPECTED_DATABASE", database);
        builder.environment().put("BOOTSTRAP_CONFIRM", confirmation);
        var process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Bootstrap command timed out"); }
        String log = java.nio.file.Files.readString(output);
        assertFalse(log.contains("Tomcat started"));
        assertFalse(log.contains(System.getenv("D2_DB_PASSWORD")));
        if (process.exitValue() == 0) { assertTrue(log.contains("INITIAL_ADMIN_COMPLETED")); }
        return process.exitValue();
    }

    @Test
    void adminPermissionsAreSeparateAndRevocationAppliesToExistingSession() throws Exception {
        UUID id = activeMember("permission-c@example.com");
        var servicePolicy = new com.example.marketplace.identity.infrastructure.JdbcAccessPolicy(jdbc);
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", id));
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("permission-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertEquals(200, authRequest(browser, "/api/admin/access", null, null).statusCode());
        for (String area : List.of("accounts", "content", "support", "settlements", "system")) {
            assertEquals(403, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
            assertThrows(com.example.marketplace.identity.api.AccessRejected.class, () -> servicePolicy.require(id,
                    com.example.marketplace.identity.api.AccessArea.valueOf(area.toUpperCase(java.util.Locale.ROOT))));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,:permission)", Map.of("id", id, "permission", area.toUpperCase(java.util.Locale.ROOT)));
            assertEquals(200, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
            servicePolicy.require(id, com.example.marketplace.identity.api.AccessArea.valueOf(area.toUpperCase(java.util.Locale.ROOT)));
            jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", id));
            assertEquals(403, authRequest(browser, "/api/admin/" + area + "/access", null, null).statusCode());
        }
        assertEquals(403, authRequest(browser, "/api/admin/unknown", null, null).statusCode());
        assertEquals(403, authRequest(browser, "/api/seller/access", null, null).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", id));
        assertEquals(200, authRequest(browser, "/api/seller/access", null, null).statusCode());
        assertEquals(200, authRequest(browser, "/api/account/profile", null, null).statusCode());
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", id));
        assertEquals(403, authRequest(browser, "/api/seller/access", null, null).statusCode());
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='ADMIN'", Map.of("id", id));
    }

    @Test
    void roleSpacesDenyAnonymousAndBuyerEvenWithForgedSignupFields() throws Exception {
        var browser = authBrowser();
        assertEquals(401, authRequest(browser, "/api/seller/access", null, null).statusCode());
        assertEquals(401, authRequest(browser, "/api/admin/access", null, null).statusCode());
        var signupResponse = authJsonPost(browser, "/api/auth/signup", Map.of("email", "forged-c@example.com",
                "password", SIGNUP_PASSWORD, "role", "ADMIN", "permissions", "SYSTEM", "isAdmin", "true"), csrfToken(browser));
        assertEquals(201, signupResponse.statusCode());
        signup.verifyEmail(mail("forged-c@example.com").token());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("forged-c@example.com", SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        for (String path : List.of("/api/seller/access", "/api/admin/access", "/api/admin/accounts/access",
                "/api/admin/content/access", "/api/admin/support/access", "/api/admin/settlements/access", "/api/admin/system/access")) {
            var response = authRequest(browser, path, null, null);
            assertEquals(403, response.statusCode(), path);
            assertEquals("FORBIDDEN", authMapper.readTree(response.body()).get("code").asText());
        }
        assertEquals(List.of("BUYER"), authMapper.readTree(authRequest(browser, "/api/auth/me", null, null).body())
                .get("roles").valueStream().map(node -> node.asText()).toList());
    }

    private String resetMail(String email) {
        return resetMailbox.messages().stream().filter(item -> item.recipient().equals(email))
                .reduce((first, second) -> second).orElseThrow().token();
    }

    private HttpResponse<String> accountRequest(HttpClient browser, String method, String path, Object body, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void accountHttpOwnerCsrfValidationAndPrivacyBoundaries() throws Exception {
        String email = "account-http-b@example.com";
        UUID member = activeMember(email);
        UUID other = activeMember("account-http-other-b@example.com");
        var foreign = accountOperations.saveAddress(other, null, addressInput(false));
        var browser = authBrowser();
        for (String path : List.of("/api/account/profile", "/api/account/addresses")) {
            assertEquals(401, accountRequest(browser, "GET", path, null, null).statusCode());
        }
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        String csrf = csrfToken(browser);
        var profileBody = Map.of("displayName", "사용자", "phone", "010-9876-5432", "email", "forged@example.com");
        assertEquals(403, accountRequest(browser, "PUT", "/api/account/profile", profileBody, null).statusCode());
        assertEquals("", accountOperations.profile(member).displayName());
        var profile = accountRequest(browser, "PUT", "/api/account/profile", profileBody, csrf);
        assertEquals(200, profile.statusCode());
        assertEquals(email, authMapper.readTree(profile.body()).get("email").asText());
        assertTrue(profile.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(400, accountRequest(browser, "PUT", "/api/account/profile", Map.of("displayName", " "), csrf).statusCode());
        assertEquals(403, accountRequest(browser, "POST", "/api/account/addresses", addressInput(false), null).statusCode());
        assertTrue(accountOperations.addresses(member).isEmpty());
        var created = accountRequest(browser, "POST", "/api/account/addresses", addressInput(false), csrf);
        assertEquals(201, created.statusCode());
        String id = authMapper.readTree(created.body()).get("id").asText();
        assertTrue(authMapper.readTree(created.body()).get("isDefault").asBoolean());
        assertEquals(403, accountRequest(browser, "PUT", "/api/account/addresses/" + id, addressInput(true), null).statusCode());
        assertEquals(403, accountRequest(browser, "DELETE", "/api/account/addresses/" + id, null, null).statusCode());
        assertEquals(1, accountOperations.addresses(member).size());
        assertEquals(404, accountRequest(browser, "PUT", "/api/account/addresses/" + foreign.id(), addressInput(true), csrf).statusCode());
        assertEquals(404, accountRequest(browser, "DELETE", "/api/account/addresses/" + foreign.id(), null, csrf).statusCode());
        assertEquals(400, accountRequest(browser, "POST", "/api/account/addresses", Map.of("label", "bad"), csrf).statusCode());
        assertEquals(200, accountRequest(browser, "PUT", "/api/account/addresses/" + id, addressInput(false), csrf).statusCode());
        var listing = accountRequest(browser, "GET", "/api/account/addresses", null, null);
        assertEquals(1, authMapper.readTree(listing.body()).get("addresses").size());
        assertTrue(listing.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(200, accountRequest(browser, "DELETE", "/api/account/addresses/" + id, null, csrf).statusCode());
        assertTrue(accountOperations.addresses(member).isEmpty());
        assertEquals(1, accountOperations.addresses(other).size());
    }

    private AddressInput addressInput(boolean isDefault) {
        return new AddressInput("집", "구매자", "010-1234-5678", "01234", "서울 주소", "101호", isDefault);
    }

    @Test
    void recoveryFailureAfterSessionDeleteRollsBackAllState() throws Exception {
        String email = "recover-atomic-b@example.com";
        UUID member = activeMember(email);
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        recovery.request(email);
        String token = resetMail(email);
        var delegate = new JdbcPasswordRecoveryStore(jdbc);
        var failingStore = new com.example.marketplace.identity.domain.PasswordRecoveryStore() {
            @Override public UUID lockEligibleMember(String value) { return delegate.lockEligibleMember(value); }
            @Override public void replaceToken(UUID id, String hash, Instant created, Instant expires) {
                delegate.replaceToken(id, hash, created, expires);
            }
            @Override public boolean consume(String hash, String passwordHash, java.util.function.Supplier<Instant> currentTime) {
                assertTrue(delegate.consume(hash, passwordHash, currentTime));
                throw new IllegalStateException("injected-after-session-delete");
            }
        };
        var failing = new PasswordRecoveryService(failingStore, passwordHasher, resetMailbox, Clock.systemUTC(), transactionManager);
        assertThrows(IllegalStateException.class, () -> failing.reset(token, "atomic-new-password"));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), Long.class));
        String hash = jdbc.queryForObject("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class);
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, hash));
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        recovery.reset(token, "atomic-new-password");
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
    }

    @Test
    void recoveryConcurrentIssueAndConsumeLeavesOnlyReplacementValid() throws Exception {
        String email = "recover-reissue-b@example.com";
        activeMember(email);
        recovery.request(email);
        String old = resetMail(email);
        var issuer = new AtomicBoolean();
        race(() -> {
            if (issuer.compareAndSet(false, true)) { recovery.request(email); return "issued"; }
            try { recovery.reset(old, "concurrent-new-password"); return "consumed"; }
            catch (SignupException expected) { return expected.getMessage(); }
        });
        String replacement = resetMail(email);
        assertThrows(SignupException.class, () -> recovery.reset(old, SIGNUP_PASSWORD));
        recovery.reset(replacement, SIGNUP_PASSWORD);
    }

    @Test
    void recoveryRechecksExpiryAfterWaitingForMemberLock() throws Exception {
        String email = "recover-lock-expiry-b@example.com";
        UUID member = activeMember(email);
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        recoveryAt(issuedAt, resetMailbox).request(email);
        String token = resetMail(email);
        var current = new java.util.concurrent.atomic.AtomicReference<>(issuedAt.plusSeconds(1799));
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var service = new PasswordRecoveryService(new JdbcPasswordRecoveryStore(jdbc), passwordHasher,
                resetMailbox, moving, transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> reset;
            try (var blocker = source.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, member);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                }
                reset = pool.submit(() -> {
                    try { service.reset(token, "expired-while-waiting"); return "ok"; }
                    catch (SignupException expected) { return expected.getMessage(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database()
                            AND pid<>pg_backend_pid() AND wait_event_type='Lock'
                            AND query LIKE '%marketplace.identity_user%' AND query LIKE '%FOR UPDATE%'
                            """, Map.of(), Boolean.class);
                    if (waiting) { break; }
                    Thread.sleep(10);
                }
                current.set(issuedAt.plusSeconds(1800));
                blocker.commit();
                assertTrue(waiting, "Reset must demonstrably wait for the member lock before clock advances");
            }
            assertEquals("INVALID_RESET_TOKEN", reset.get(10, TimeUnit.SECONDS));
        }
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", member), Long.class));
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, jdbc.queryForObject(
                "SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id", Map.of("id", member), String.class)));
    }

    @Test
    void recoveryLatestOnlyOneUseRevokesSessionsAndChangesPassword(CapturedOutput output) throws Exception {
        String email = "recover-b@example.com";
        UUID member = activeMember(email);
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        recovery.request(email);
        String old = resetMail(email);
        recovery.request(email);
        String current = resetMail(email);
        assertThrows(SignupException.class, () -> recovery.reset(old, "replacement-password"));
        assertThrows(SignupException.class, () -> recovery.reset(mail(email).token(), "replacement-password"));
        assertThrows(SignupException.class, () -> signup.verifyEmail(current));
        assertThrows(SignupException.class, () -> recovery.reset(current, "short"));
        assertEquals(TokenSecrets.hash(current), jdbc.queryForObject(
                "SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id", Map.of("id", member), String.class));
        recovery.reset(current, "replacement-password");
        assertThrows(SignupException.class, () -> recovery.reset(current, "another-password"));
        assertEquals(1L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", member), Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", member.toString()), Integer.class));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(browser)).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, "replacement-password"), csrfToken(browser)).statusCode());
        assertFalse(output.getAll().contains(current));
        assertFalse(output.getAll().contains("replacement-password"));
    }

    @Test
    void recoveryConcurrentConsumptionAndLatestRequestSerialize() throws Exception {
        UUID member = activeMember("recover-race-b@example.com");
        race(() -> { recovery.request("recover-race-b@example.com"); return "ok"; });
        String hash = jdbc.queryForObject("SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), String.class);
        String token = resetMailbox.messages().stream().filter(item -> TokenSecrets.hash(item.token()).equals(hash))
                .findFirst().orElseThrow().token();
        var results = race(() -> {
            try { recovery.reset(token, "new-racing-password"); return "ok"; }
            catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("INVALID_RESET_TOKEN"::equals).count());
    }

    private PasswordRecoveryService recoveryAt(Instant time, VerificationMailbox targetMailbox) {
        return new PasswordRecoveryService(new JdbcPasswordRecoveryStore(jdbc), passwordHasher, targetMailbox,
                Clock.fixed(time, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void recoveryExpiryRollbackAndIneligibleMembersLeaveStateUntouched() {
        UUID member = activeMember("recover-expire-b@example.com");
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        recoveryAt(now, resetMailbox).request("recover-expire-b@example.com");
        String token = resetMail("recover-expire-b@example.com");
        assertThrows(SignupException.class, () -> recoveryAt(now.plusSeconds(1800), resetMailbox).reset(token, SIGNUP_PASSWORD));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), java.sql.Timestamp.class));
        VerificationMailbox failing = (recipient, secret, expiry) -> { throw new SignupException("MAIL_FAILURE"); };
        assertThrows(SignupException.class, () -> recoveryAt(now, failing).request("recover-expire-b@example.com"));
        assertEquals(TokenSecrets.hash(token), jdbc.queryForObject("SELECT token_hash FROM marketplace.password_reset_token WHERE user_id=:id",
                Map.of("id", member), String.class));
        int before = resetMailbox.messages().size();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recoveryAt(now, resetMailbox).request("recover-expire-b@example.com");
            status.setRollbackOnly();
        });
        assertEquals(before, resetMailbox.messages().size());
        recoveryAt(now.plusSeconds(1799), resetMailbox).reset(token, "valid-before-expiry");
        signup.register("recover-pending-b@example.com", SIGNUP_PASSWORD);
        UUID suspended = activeMember("recover-suspended-b@example.com");
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", suspended));
        before = resetMailbox.messages().size();
        for (String email : List.of("recover-pending-b@example.com", "recover-suspended-b@example.com", "absent-b@example.com")) {
            recovery.request(email);
        }
        assertEquals(before, resetMailbox.messages().size());
    }

    @Test
    void recoveryHttpRequiresCsrfAndDoesNotRevealEligibility() throws Exception {
        var browser = authBrowser();
        assertEquals(403, authJsonPost(browser, "/api/auth/password/request", Map.of("email", "unknown-b@example.com"), null).statusCode());
        String csrf = csrfToken(browser);
        activeMember("recover-http-b@example.com");
        signup.register("recover-http-pending-b@example.com", SIGNUP_PASSWORD);
        for (String email : List.of("unknown-b@example.com", "recover-http-b@example.com", "recover-http-pending-b@example.com")) {
            var response = authJsonPost(browser, "/api/auth/password/request", Map.of("email", email), csrf);
            assertEquals(202, response.statusCode());
            assertEquals("{\"status\":\"PASSWORD_RESET_REQUESTED\"}", response.body());
        }
        String token = resetMail("recover-http-b@example.com");
        assertEquals(403, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", SIGNUP_PASSWORD), null).statusCode());
        assertEquals(400, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", "short"), csrf).statusCode());
        assertEquals(200, authJsonPost(browser, "/api/auth/password/reset", Map.of("token", token, "password", SIGNUP_PASSWORD), csrf).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
    }

    @Test
    void profileAndAddressOwnershipDefaultAndRollback() {
        UUID member = activeMember("address-owner-b@example.com");
        UUID other = activeMember("address-other-b@example.com");
        var profile = accountOperations.updateProfile(member, " 구매자😀 ", "010-1234-5678");
        assertEquals("구매자😀", profile.displayName());
        assertEquals("address-owner-b@example.com", profile.email());
        assertThrows(AccountException.class, () -> accountOperations.updateProfile(member, " ", null));
        assertEquals(profile, accountOperations.profile(member));
        var first = accountOperations.saveAddress(member, null, addressInput(false));
        assertTrue(first.isDefault());
        var second = accountOperations.saveAddress(member, null, addressInput(true));
        assertEquals(second.id(), accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).findFirst().orElseThrow().id());
        assertTrue(accountOperations.saveAddress(member, second.id(), addressInput(false)).isDefault());
        assertEquals("ADDRESS_NOT_FOUND", assertThrows(AccountException.class,
                () -> accountOperations.saveAddress(other, first.id(), addressInput(true))).getMessage());
        assertThrows(AccountException.class, () -> accountOperations.deleteAddress(other, first.id()));
        assertTrue(accountOperations.addresses(other).isEmpty());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountOperations.saveAddress(member, first.id(), addressInput(true));
            accountOperations.deleteAddress(member, second.id());
            status.setRollbackOnly();
        });
        assertEquals(2, accountOperations.addresses(member).size());
        assertEquals(second.id(), accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).findFirst().orElseThrow().id());
        accountOperations.deleteAddress(member, second.id());
        assertEquals(first.id(), accountOperations.addresses(member).getFirst().id());
        assertTrue(accountOperations.addresses(member).getFirst().isDefault());
        accountOperations.deleteAddress(member, first.id());
        assertTrue(accountOperations.addresses(member).isEmpty());
    }

    @Test
    void addressConcurrentFirstDefaultAndCapacityPreserveInvariants() throws Exception {
        UUID member = activeMember("address-race-b@example.com");
        race(() -> { accountOperations.saveAddress(member, null, addressInput(true)); return "ok"; });
        assertEquals(2, accountOperations.addresses(member).size());
        assertEquals(1, accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).count());
        for (int i = 2; i < 19; i++) { accountOperations.saveAddress(member, null, addressInput(false)); }
        var result = race(() -> {
            try { accountOperations.saveAddress(member, null, addressInput(true)); return "ok"; }
            catch (AccountException expected) { return expected.getMessage(); }
        });
        assertEquals(1, result.stream().filter("ok"::equals).count());
        assertEquals(1, result.stream().filter("ADDRESS_LIMIT"::equals).count());
        assertEquals(20, accountOperations.addresses(member).size());
        assertEquals(1, accountOperations.addresses(member).stream().filter(AccountOperations.Address::isDefault).count());
    }

    private HttpResponse<String> authJsonPost(HttpClient browser, String path, Map<String, String> body, String csrf)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(body)));
        if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signupAndVerificationHttpRejectCsrfAndBadInputWithoutChanges() throws Exception {
        var browser = authBrowser();
        var body = Map.of("email", "guard-a3@example.com", "password", "guard-a3-password");
        assertEquals(403, authJsonPost(browser, "/api/auth/signup", body, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), Integer.class));
        String csrf = csrfToken(browser);
        assertEquals(400, authJsonPost(browser, "/api/auth/signup",
                Map.of("email", "invalid", "password", "short"), csrf).statusCode());
        assertEquals(201, authJsonPost(browser, "/api/auth/signup", body, csrf).statusCode());
        assertEquals(409, authJsonPost(browser, "/api/auth/signup", body, csrf).statusCode());
        String token = mail("guard-a3@example.com").token();
        var verification = Map.of("token", token);
        assertEquals(403, authJsonPost(browser, "/api/auth/verify-email", verification, null).statusCode());
        assertEquals("PENDING_EMAIL", jdbc.queryForObject(
                "SELECT state FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.email_verification_token WHERE token_hash=:hash AND consumed_at IS NULL",
                Map.of("hash", TokenSecrets.hash(token)), Integer.class));
        assertEquals(200, authJsonPost(browser, "/api/auth/verify-email", verification, csrf).statusCode());
        assertEquals(400, authJsonPost(browser, "/api/auth/verify-email", verification, csrf).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "guard-a3@example.com"), Integer.class));
    }
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("c3-fixture")
    static class TestMailboxEndpoint {
        private final LocalTestMailbox mailbox;
        private final LocalPasswordResetMailbox resetMailbox;
        TestMailboxEndpoint(LocalTestMailbox mailbox, LocalPasswordResetMailbox resetMailbox) {
            this.mailbox = mailbox; this.resetMailbox = resetMailbox;
        }
        @org.springframework.web.bind.annotation.GetMapping("/__test/reset-mail")
        Map<String, String> resetToken(@org.springframework.web.bind.annotation.RequestParam String email,
                jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            return Map.of("token", resetMailbox.messages().stream().filter(item -> item.recipient().equals(email))
                    .reduce((first, second) -> second).orElseThrow().token());
        }
        @org.springframework.web.bind.annotation.GetMapping("/__test/mail")
        Map<String, String> token(@org.springframework.web.bind.annotation.RequestParam String email,
                jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control", "no-store");
            return Map.of("token", mailbox.messages().stream().filter(item -> item.recipient().equals(email))
                    .findFirst().orElseThrow().token());
        }
    }

    private void runAuthBrowsersIfRequested() throws Exception {
        if (!"true".equals(System.getenv("A3_RUN_BROWSER"))) { return; }
        var cMembers = new java.util.ArrayList<UUID>();
        // Keep the first operator page full so browser journeys must follow pagination,
        // regardless of random store UUIDs or the order of other integration tests.
        for (int index = 1; index <= 20; index++) {
            UUID owner = activeMember("browser-page-fixture-" + index + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", owner));
            cMembers.add(owner);
            jdbc.update("INSERT INTO marketplace.seller_store(id,owner_id,slug,name,introduction,address,notice,image_key,image_alt) "
                    + "VALUES(:id,:owner,:slug,'Pagination fixture','Test only','Test address','','','')",
                    Map.of("id", new UUID(0, index), "owner", owner, "slug", "browser-page-fixture-" + index));
        }
        for (String project : List.of("desktop", "mobile")) {
            UUID catalogSeller=activeMember("p4-seller-"+project+"@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",catalogSeller));
            context.getBean(com.example.marketplace.seller.api.Stores.class).save(catalogSeller,null,new com.example.marketplace.seller.api.Stores.Input("p4-browser-"+project,"P04 browser","intro","address","","","",true,0L));
            cMembers.add(catalogSeller);
            UUID seller = activeMember("c-seller-" + project + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')", Map.of("id", seller));
            UUID admin = activeMember("c-admin-" + project + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", admin));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT')", Map.of("id", admin));
            cMembers.add(seller); cMembers.add(admin);
            UUID lifecycleSeller=activeMember("p3c-seller-"+project+"@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'SELLER')",Map.of("id",lifecycleSeller));
            cMembers.add(lifecycleSeller);
            UUID reviewer = activeMember("p3-admin-" + project + "@example.com");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')", Map.of("id", reviewer));
            jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'ACCOUNTS')", Map.of("id", reviewer));
            cMembers.add(reviewer);
        }
        try {
        var root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        var node = root.resolve(".local-tools/node-v24.22.0-win-x64/node.exe");
        var builder = new ProcessBuilder(node.toString(), "node_modules/@playwright/test/cli.js", "test",
                "--config=auth-e2e/playwright.config.ts").directory(root.resolve("frontend").toFile());
        builder.environment().put("A3_API_PORT", Integer.toString(port));
        builder.environment().put("PATH", node.getParent() + java.io.File.pathSeparator + System.getenv("PATH"));
        builder.redirectErrorStream(true).redirectOutput(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"), "browser.txt").toFile());
        var process = builder.start();
        if (!process.waitFor(240, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Auth browser verification timed out");
        }
        assertEquals(0, process.exitValue(), "See isolated evidence browser.txt");
        } finally {
            for (UUID id : cMembers) {
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id", Map.of("id", id));
                jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role IN ('ADMIN','SELLER')", Map.of("id", id));
            }
        }
    }

    @Test
    void authMutationLimitReturns429WithoutChangingAccount() throws Exception {
        var browser = authBrowser();
        String token = csrfToken(browser);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, authRequest(browser, "/api/auth/login",
                    loginForm("limited-a3@example.com", "wrong-password"), token).statusCode());
        }
        var blocked = authRequest(browser, "/api/auth/login", loginForm(" LIMITED-A3@example.com ", "wrong"), token);
        assertEquals(429, blocked.statusCode());
        assertEquals("60", blocked.headers().firstValue("Retry-After").orElseThrow());
        assertEquals("RATE_LIMITED", authMapper.readTree(blocked.body()).get("code").asText());
    }
    @Test
    void signupHttpRequiresCsrfAndReturnsNoSecrets() throws Exception {
        var browser = authBrowser();
        String token = csrfToken(browser);
        var response = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/signup"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"http-a3@example.com\",\"password\":\"a3-test-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        assertFalse(response.body().contains("password"));
        assertFalse(response.body().contains("token"));
        var verify = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/verify-email"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(Map.of("token", mail("http-a3@example.com").token()))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, verify.statusCode());
        runAuthBrowsersIfRequested();
    }

    private final tools.jackson.databind.json.JsonMapper authMapper = tools.jackson.databind.json.JsonMapper.builder().build();

    private HttpClient authBrowser() {
        // Isolate Java contract traffic from Playwright's 127.0.0.1 rate window without relaxing production limits.
        return HttpClient.newBuilder().localAddress(new java.net.InetSocketAddress("127.0.0.2", 0).getAddress()).cookieHandler(new java.net.CookieManager(
                null, java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpResponse<String> authRequest(HttpClient browser, String path, String form, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (form == null) { request.GET(); } else {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
        }
        if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrfToken(HttpClient browser) throws Exception {
        var response = authRequest(browser, "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode());
        return authMapper.readTree(response.body()).get("token").asText();
    }

    private String loginForm(String email, String password) {
        return "email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
    }

    private UUID activeMember(String email) {
        UUID id = signup.register(email, SIGNUP_PASSWORD);
        signup.verifyEmail(mail(email).token());
        return id;
    }

    private String cookieValue(HttpClient browser) {
        return ((java.net.CookieManager) browser.cookieHandler().orElseThrow()).getCookieStore().getCookies()
                .stream().filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
    }

    @Test
    void loginRotatesSessionPersistsInJdbcAndLogoutRevokesCookie(CapturedOutput output) throws Exception {
        UUID id = activeMember("login-a2@example.com");
        var browser = authBrowser();
        String beforeToken = csrfToken(browser);
        String beforeCookie = cookieValue(browser);
        var login = authRequest(browser, "/api/auth/login", loginForm(" LOGIN-A2@example.com ", SIGNUP_PASSWORD), beforeToken);
        assertEquals(200, login.statusCode());
        assertFalse(login.headers().firstValue("Location").isPresent());
        String afterCookie = cookieValue(browser);
        assertFalse(beforeCookie.equals(afterCookie));
        String cookieHeader = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Lax"));
        assertFalse(cookieHeader.contains("Secure"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));

        // Another HTTP client restores authentication from the same JDBC-backed cookie.
        var restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .header("Cookie", "SESSION=" + afterCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, restored.statusCode());
        assertEquals(id.toString(), authMapper.readTree(restored.body()).get("id").asText());
        assertTrue(restored.body().contains("BUYER"));
        assertFalse(restored.body().contains("email"));
        assertFalse(restored.body().contains("password"));
        assertTrue(restored.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var attributes = jdbc.queryForList("""
                SELECT a.attribute_bytes FROM marketplace.spring_session_attributes a
                JOIN marketplace.spring_session s ON s.primary_id=a.session_primary_id WHERE s.principal_name=:id
                """, Map.of("id", id.toString()), byte[].class);
        assertFalse(attributes.isEmpty());
        for (byte[] attribute : attributes) {
            String serialized = new String(attribute, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(serialized.contains("{argon2id}"));
            assertFalse(serialized.contains("A1-private-passphrase"));
        }

        assertEquals(403, authRequest(browser, "/api/auth/logout", "", beforeToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/me", null, null).statusCode());
        String afterToken = csrfToken(browser);
        assertFalse(beforeToken.equals(afterToken));
        assertEquals(200, authRequest(browser, "/api/auth/logout", "", afterToken).statusCode());
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        var replay = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .header("Cookie", "SESSION=" + afterCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, replay.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));
        assertEquals(403, authRequest(browser, "/api/auth/login", loginForm("login-a2@example.com", SIGNUP_PASSWORD),
                afterToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("login-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        assertFalse(output.getAll().contains(SIGNUP_PASSWORD));
        assertFalse(output.getAll().contains(beforeToken));
    }

    @Test
    void invalidCredentialsAndUnverifiedAccountsHaveSameJsonFailure() throws Exception {
        activeMember("wrong-a2@example.com");
        signup.register("pending-a2@example.com", SIGNUP_PASSWORD);
        var browser = authBrowser();
        String token = csrfToken(browser);
        for (String email : List.of("wrong-a2@example.com", "missing-a2@example.com", "pending-a2@example.com", "bad")) {
            String password = email.startsWith("wrong") ? "incorrect-password" : SIGNUP_PASSWORD;
            var response = authRequest(browser, "/api/auth/login", loginForm(email, password), token);
            assertEquals(401, response.statusCode());
            assertEquals("AUTHENTICATION_REQUIRED", authMapper.readTree(response.body()).get("code").asText());
            assertEquals(response.headers().firstValue("X-Request-ID").orElseThrow(),
                    authMapper.readTree(response.body()).get("requestId").asText());
            assertFalse(response.headers().firstValue("Location").isPresent());
            assertFalse(response.body().contains(email));
        }
    }

    @Test
    void csrfCannotBeOmittedOrCopiedBetweenSessions() throws Exception {
        var first = authBrowser();
        var second = authBrowser();
        String token = csrfToken(first);
        csrfToken(second);
        for (String path : List.of("/api/auth/login", "/api/auth/logout", "/api/unknown-mutation")) {
            assertEquals(403, authRequest(first, path, "", null).statusCode());
            assertEquals(403, authRequest(second, path, "", token).statusCode());
        }
        assertFalse(authRequest(first, "/api/auth/logout", null, null).statusCode() == 200);
    }

    @Test
    void currentRolesAndAccountVersionAreCheckedEveryRequest() throws Exception {
        UUID id = activeMember("version-a2@example.com");
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("version-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES (:id, 'SELLER')", Map.of("id", id));
        assertTrue(authRequest(browser, "/api/auth/me", null, null).body().contains("SELLER"));
        jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'", Map.of("id", id));
        assertFalse(authRequest(browser, "/api/auth/me", null, null).body().contains("SELLER"));
        jdbc.update("UPDATE marketplace.identity_user SET auth_version=auth_version+1 WHERE id=:id", Map.of("id", id));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.spring_session WHERE principal_name=:id",
                Map.of("id", id.toString()), Integer.class));
    }

    @Test
    void suspendedAndWithdrawnAccountsLoseExistingSessions() throws Exception {
        for (String state : List.of("SUSPENDED", "WITHDRAWN")) {
            String email = state.toLowerCase(java.util.Locale.ROOT) + "-a2@example.com";
            UUID id = activeMember(email);
            var browser = authBrowser();
            assertEquals(200, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD),
                    csrfToken(browser)).statusCode());
            jdbc.update("UPDATE marketplace.identity_user SET state=:state WHERE id=:id", Map.of("id", id, "state", state));
            assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
            assertEquals(401, authRequest(browser, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD),
                    csrfToken(browser)).statusCode());
        }
    }

    @Test
    void expiredJdbcSessionCannotAuthenticateOrReuseCsrf() throws Exception {
        UUID id = activeMember("expiry-a2@example.com");
        var browser = authBrowser();
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
        String oldToken = csrfToken(browser);
        jdbc.update("""
                UPDATE marketplace.spring_session SET last_access_time=0, expiry_time=0 WHERE principal_name=:id
                """, Map.of("id", id.toString()));
        assertEquals(401, authRequest(browser, "/api/auth/me", null, null).statusCode());
        assertEquals(403, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                oldToken).statusCode());
        assertEquals(200, authRequest(browser, "/api/auth/login", loginForm("expiry-a2@example.com", SIGNUP_PASSWORD),
                csrfToken(browser)).statusCode());
    }

    @Test
    void anonymousAuthenticationContract() throws Exception {
        HttpClient browser = HttpClient.newHttpClient();
        var me = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode());
        assertTrue(me.body().contains("AUTHENTICATION_REQUIRED"));
        var csrf = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrf.statusCode());
        assertTrue(csrf.body().contains("X-CSRF-TOKEN"));
        assertTrue(csrf.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }
    private static final String SIGNUP_PASSWORD = " A1-private-passphrase-가나다 ";
    @Autowired private PasswordRecoveryOperations recovery;
    @Autowired private LocalPasswordResetMailbox resetMailbox;
    @Autowired private AccountOperations accountOperations;
    @Autowired
    private SignupOperations signup;
    @Autowired
    private IdentityStore identityStore;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private LocalTestMailbox mailbox;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @LocalServerPort
    private int port;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private MBeanServer mBeanServer;
    @Autowired
    private DataSource source;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private TransferService transfers;
    @Autowired
    private BatchService batches;
    @Autowired
    private Flyway flyway;

    private LocalTestMailbox.Message mail(String email) {
        return mailbox.messages().stream().filter(item -> item.recipient().equals(email)).reduce((first, last) -> last).orElseThrow();
    }

    private SignupService serviceAt(Instant now, VerificationMailbox targetMailbox) {
        return new SignupService(identityStore, passwordHasher, targetMailbox,
                Clock.fixed(now, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void signupPersistsOnlyHashesAndBuyerThenVerificationActivates(CapturedOutput output) {
        UUID id = signup.register("  New.Buyer@EXAMPLE.COM ", SIGNUP_PASSWORD);
        String hash = jdbc.queryForObject("SELECT password_hash FROM marketplace.user_credential WHERE user_id=:id",
                Map.of("id", id), String.class);
        assertTrue(hash.startsWith("{argon2id}$argon2id$"));
        assertTrue(passwordHasher.matches(SIGNUP_PASSWORD, hash));
        assertFalse(passwordHasher.matches(SIGNUP_PASSWORD.strip(), hash));
        assertEquals("BUYER", jdbc.queryForObject("SELECT role FROM marketplace.role_assignment WHERE user_id=:id",
                Map.of("id", id), String.class));
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), String.class));
        var message = mail("new.buyer@example.com");
        assertEquals(TokenSecrets.hash(message.token()), jdbc.queryForObject(
                "SELECT token_hash FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", id), String.class));
        signup.verifyEmail(message.token());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", id), Long.class));
        assertEquals("INVALID_EMAIL_TOKEN", assertThrows(SignupException.class,
                () -> signup.verifyEmail(message.token())).getMessage());
        assertFalse(output.getAll().contains(SIGNUP_PASSWORD));
        assertFalse(output.getAll().contains(message.token()));
        assertFalse(output.getAll().contains(hash));
        assertFalse(message.toString().contains(message.token()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.flyway_schema_history WHERE version='3' AND success",
                Map.of(), Integer.class));
    }

    @Test
    void normalizedDuplicateIsRejectedWithoutExtraCredentialOrMail() {
        signup.register("duplicate@example.com", SIGNUP_PASSWORD);
        int mailCount = mailbox.messages().size();
        assertEquals("EMAIL_ALREADY_REGISTERED", assertThrows(SignupException.class,
                () -> signup.register(" DUPLICATE@example.COM ", SIGNUP_PASSWORD)).getMessage());
        assertEquals(mailCount, mailbox.messages().size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email=:email",
                Map.of("email", "duplicate@example.com"), Integer.class));
    }

    private List<String> race(Callable<String> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> task = () -> {
                start.await();
                return action.call();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDuplicateHasExactlyOneCommitAndOneMail() throws Exception {
        var results = race(() -> {
            try {
                signup.register("race@example.com", SIGNUP_PASSWORD);
                return "ok";
            } catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("EMAIL_ALREADY_REGISTERED"::equals).count());
        assertEquals(1, mailbox.messages().stream().filter(item -> item.recipient().equals("race@example.com")).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email='race@example.com'",
                Map.of(), Integer.class));
    }

    @Test
    void concurrentTokenConsumptionHasExactlyOneSuccess() throws Exception {
        signup.register("token-race@example.com", SIGNUP_PASSWORD);
        String token = mail("token-race@example.com").token();
        var results = race(() -> {
            try {
                signup.verifyEmail(token);
                return "ok";
            } catch (SignupException expected) { return expected.getMessage(); }
        });
        assertEquals(1, results.stream().filter("ok"::equals).count());
        assertEquals(1, results.stream().filter("INVALID_EMAIL_TOKEN"::equals).count());
    }

    @Test
    void expiryBoundaryIsExclusiveAndExpiredTokenRemainsUnconsumed() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var service = serviceAt(now, mailbox);
        UUID expired = service.register("expiry@example.com", SIGNUP_PASSWORD);
        service.register("before-expiry@example.com", SIGNUP_PASSWORD);
        String token = mail("expiry@example.com").token();
        assertThrows(SignupException.class,
                () -> serviceAt(now.plusSeconds(1800), mailbox).verifyEmail(token));
        serviceAt(now.plusSeconds(1799), mailbox).verifyEmail(mail("before-expiry@example.com").token());
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id",
                Map.of("id", expired), String.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id",
                Map.of("id", expired), java.sql.Timestamp.class));
        assertThrows(SignupException.class, () -> signup.verifyEmail("unknown"));
        assertThrows(SignupException.class, () -> signup.verifyEmail(TokenSecrets.generate()));
    }

    @Test
    void verificationRechecksExpiryAfterTokenLockWait() throws Exception {
        verifyExpiryAfterLockWait("email_verification_token");
    }

    @Test
    void verificationRechecksExpiryAfterMemberLockWait() throws Exception {
        verifyExpiryAfterLockWait("identity_user");
    }

    private void verifyExpiryAfterLockWait(String table) throws Exception {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        String email = "r1-" + table + "@example.com";
        UUID member = serviceAt(issuedAt, mailbox).register(email, SIGNUP_PASSWORD);
        String token = mail(email).token();
        var current = new java.util.concurrent.atomic.AtomicReference<>(issuedAt.plusSeconds(1799));
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var service = new SignupService(identityStore, passwordHasher, mailbox, moving, transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> result;
            try (var blocker = source.getConnection()) {
                blocker.setAutoCommit(false);
                String column = table.equals("identity_user") ? "id" : "user_id";
                try (var statement = blocker.prepareStatement("SELECT " + column + " FROM marketplace." + table + " WHERE " + column + "=? FOR UPDATE")) {
                    statement.setObject(1, member);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
                }
                result = pool.submit(() -> {
                    try { service.verifyEmail(token); return "ok"; }
                    catch (SignupException expected) { return expected.getMessage(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database()
                            AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE :query
                            """, Map.of("query", "%marketplace." + table + "%"), Boolean.class);
                    if (waiting) { break; }
                    Thread.sleep(10);
                }
                current.set(issuedAt.plusSeconds(1800));
                blocker.commit();
                assertTrue(waiting, "Verification must actually wait for " + table + " before clock advances");
            }
            assertEquals("INVALID_EMAIL_TOKEN", result.get(10, TimeUnit.SECONDS));
        }
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), String.class));
        assertNull(jdbc.queryForObject("SELECT email_verified_at FROM marketplace.identity_user WHERE id=:id", Map.of("id", member), java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", member), java.sql.Timestamp.class));
    }

    @Test
    void mailboxPreparationFailureRollsBackAllSignupRows() {
        VerificationMailbox unavailable = (recipient, token, expires) -> { throw new SignupException("MAILBOX_UNAVAILABLE"); };
        var service = serviceAt(Instant.now(), unavailable);
        int before = jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token", Map.of(), Integer.class);
        assertThrows(SignupException.class, () -> service.register("failed-mail@example.com", SIGNUP_PASSWORD));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user WHERE email='failed-mail@example.com'",
                Map.of(), Integer.class));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM marketplace.email_verification_token", Map.of(), Integer.class));
        assertTrue(mailbox.messages().stream().noneMatch(item -> item.recipient().equals("failed-mail@example.com")));
    }

    @Test
    void callerRollbackAlsoRollsBackSignupAndDiscardsUnpublishedMail() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            signup.register("outer-rollback@example.com", SIGNUP_PASSWORD);
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.identity_user WHERE email='outer-rollback@example.com'",
                Map.of(), Integer.class));
        assertTrue(mailbox.messages().stream().noneMatch(item -> item.recipient().equals("outer-rollback@example.com")));
    }

    @Test
    void invalidInputDoesNotWriteOrDeliver() {
        int before = jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class);
        assertThrows(SignupException.class, () -> signup.register("bad address", SIGNUP_PASSWORD));
        assertThrows(SignupException.class, () -> signup.register("short@example.com", "short"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user", Map.of(), Integer.class));
    }

    @Test
    void activationFailureRollsBackTokenConsumption() {
        UUID id = signup.register("suspended@example.com", SIGNUP_PASSWORD);
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id", Map.of("id", id));
        assertThrows(SignupException.class, () -> signup.verifyEmail(mail("suspended@example.com").token()));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id",
                Map.of("id", id), java.sql.Timestamp.class));
        UUID failing = signup.register("activation-write-failure@example.com", SIGNUP_PASSWORD);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.identity_user ADD CONSTRAINT r1_reject_activation CHECK (id <> '"
                + failing + "'::uuid OR state <> 'ACTIVE') NOT VALID");
        try {
            var failure = assertThrows(SignupException.class,
                    () -> signup.verifyEmail(mail("activation-write-failure@example.com").token()));
            assertEquals("VERIFICATION_UNAVAILABLE", failure.getMessage());
        } finally { jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.identity_user DROP CONSTRAINT r1_reject_activation"); }
        assertEquals("PENDING_EMAIL", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), String.class));
        assertNull(jdbc.queryForObject("SELECT email_verified_at FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM marketplace.email_verification_token WHERE user_id=:id", Map.of("id", failing), java.sql.Timestamp.class));
        // A retry with the same token succeeds after the transient write constraint is removed.
        signup.verifyEmail(mail("activation-write-failure@example.com").token());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM marketplace.identity_user WHERE id=:id", Map.of("id", failing), String.class));
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void databaseSettingsValidatorAndActualDataSourceCoexist() {
        assertTrue(context.containsBean("requiredDatabaseSettings"));
        assertEquals(System.getenv("D2_DB_URL"), context.getEnvironment().getProperty("spring.datasource.url"));
        assertEquals(System.getenv("D2_DB_USERNAME"), context.getEnvironment().getProperty("spring.datasource.username"));
        assertEquals("none", context.getEnvironment().getProperty("management.endpoints.access.default"));
        assertEquals("read-only", context.getEnvironment().getProperty("management.endpoint.health.access"));
        assertTrue(context.containsBean("dbHealthContributor"));
    }

    @Test
    void invalidSettingsFailBeforeSingletonsWithRealDatabaseAutoConfigurationEnabled() {
        for (String key : new String[] {"url", "username", "password"}) {
            var singletonCreated = new AtomicBoolean();
            var databaseCreationAttempted = new AtomicBoolean();
            new ApplicationContextRunner().withInitializer(candidate -> {
                candidate.getBeanFactory().addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
                        if (beanName.equals("dataSource") || beanName.equals("flyway")) {
                            databaseCreationAttempted.set(true);
                        }
                        return null;
                    }
                });
                candidate.getEnvironment().setConversionService(new ApplicationConversionService());
                candidate.getEnvironment().getPropertySources().remove("systemEnvironment");
                candidate.getEnvironment().getPropertySources().remove("systemProperties");
                candidate.getEnvironment().setActiveProfiles("db");
            }).withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(MarketplaceApplication.class)
                    .withPropertyValues("spring.config.location=classpath:/",
                            "DB_URL=" + System.getenv("D2_DB_URL"), "DB_USERNAME=marketplace_test",
                            "DB_PASSWORD=unused-synthetic-password", "spring.datasource." + key + "= ")
                    .withBean("singletonProbe", Object.class, () -> {
                        singletonCreated.set(true);
                        return new Object();
                    }).run(candidate -> {
                        assertThat(candidate).hasFailed();
                        assertThat(candidate.getStartupFailure())
                                .hasStackTraceContaining("Required database settings are missing, blank or unresolved:")
                                .hasStackTraceContaining("spring.datasource." + key);
                        assertFalse(singletonCreated.get());
                        assertFalse(databaseCreationAttempted.get());
                    });
        }
    }

    @Test
    void realDatabaseHealthPreservesStatusOnlyAndRequestId() throws Exception {
        var response = request("GET", "/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        assertTrue(response.headers().firstValue("X-Request-ID").orElse("")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        var head = request("HEAD", "/actuator/health");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(405, request("POST", "/actuator/health").statusCode());
    }

    @Test
    void databaseProfileDoesNotExposeManagementDetailsOrJmx() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/configprops",
                "/actuator/metrics", "/actuator/beans", "/actuator/health/db",
                "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertEquals(404, request("GET", path).statusCode(), path);
        }
        assertTrue(mBeanServer.queryNames(new ObjectName("com.example.marketplace.dbfollowup:*"), null).isEmpty());
        assertFalse(context.containsBean("heapDumpWebEndpoint"));
    }

    private void seed(long id, String label, String note) {
        jdbc.update("INSERT INTO c3_fixture.account VALUES (:id, :label, :balance, :note)",
                new MapSqlParameterSource().addValue("id", id).addValue("label", label)
                        .addValue("balance", new BigDecimal("100.50")).addValue("note", note));
    }

    /** Bypasses the Spring connection pool and transaction context to observe committed state. */
    private String observe(String sql, Object parameter) throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("D2_DB_URL"),
                System.getenv("D2_DB_USERNAME"), System.getenv("D2_DB_PASSWORD"));
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private void balance(long id, String expected) throws Exception {
        assertEquals(expected, observe("SELECT balance FROM c3_fixture.account WHERE id = ?", id));
    }

    private void entryCount(String reference, String expected) throws Exception {
        assertEquals(expected, observe("SELECT count(*) FROM c3_fixture.transfer_entry WHERE reference = ?", reference));
    }

    @Test
    void bootUsesVerifiedPostgresAndUnprivilegedRole() throws Exception {
        try (var connection = source.getConnection()) {
            DedicatedDatabaseTarget.verifyConnection(connection, System.getenv("D2_DB_NAME"));
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(18, connection.getMetaData().getDatabaseMajorVersion());
        }
    }

    @Test
    void sameProductionV1AndFixtureV2MigrateOnce() {
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM marketplace.flyway_schema_history WHERE version IN ('1','2') AND success",
                Map.of(), Integer.class));
        assertEquals("initialize schema", jdbc.queryForObject(
                "SELECT description FROM marketplace.flyway_schema_history WHERE version = '1'", Map.of(), String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void parametersAndMappingHandleQuotesUnicodeDecimalAndNull() {
        seed(101, "구매자 O'Reilly", null);
        seed(102, "판매자", "설명");
        var account = accounts.findByLabel("구매자 O'Reilly").orElseThrow();
        assertEquals(101, account.id());
        assertEquals("구매자 O'Reilly", account.label());
        assertEquals(new BigDecimal("100.50"), account.balance());
        assertNull(account.note());
        assertEquals("설명", accounts.findByLabel("판매자").orElseThrow().note());
        assertTrue(accounts.findByLabel("missing").isEmpty());
        assertTrue(accounts.findByLabel("' OR 1=1 --").isEmpty());
    }

    @Test
    void successCommitsBothBalancesAndEntry() throws Exception {
        seed(201, "commit-from", null);
        seed(202, "commit-to", null);
        transfers.transfer(201, 202, new BigDecimal("25.25"), "commit");
        balance(201, "75.25");
        balance(202, "125.75");
        entryCount("commit", "1");
    }

    @Test
    void duplicateReferenceRollsBackUpdatesAlreadyMade() throws Exception {
        seed(301, "duplicate-from", null);
        seed(302, "duplicate-to", null);
        transfers.transfer(301, 302, new BigDecimal("10.00"), "duplicate");
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(301, 302, new BigDecimal("20.00"), "duplicate"));
        balance(301, "90.50");
        balance(302, "110.50");
        entryCount("duplicate", "1");
    }

    @Test
    void checkedFailureAfterDebitRollsBack() throws Exception {
        seed(401, "checked-from", null);
        assertThrows(TransferRejectedException.class,
                () -> transfers.transfer(401, 9999, new BigDecimal("30.00"), "checked"));
        balance(401, "100.50");
        entryCount("checked", "0");
    }

    @Test
    void databaseConstraintsPreventNegativeBalanceAndOrphanEntry() throws Exception {
        seed(501, "constraint-from", null);
        seed(502, "constraint-to", null);
        assertThrows(DataIntegrityViolationException.class,
                () -> transfers.transfer(501, 502, new BigDecimal("200.00"), "insufficient"));
        balance(501, "100.50");
        balance(502, "100.50");
        entryCount("insufficient", "0");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO c3_fixture.transfer_entry VALUES ('orphan', 9999, 501, 1)", Map.of()));
        entryCount("orphan", "0");
    }

    @Test
    void caughtRequiredFailureRollsBackEarlierSuccessfulTransfer() throws Exception {
        seed(61, "batch-from", null);
        seed(62, "batch-to", null);
        assertThrows(UnexpectedRollbackException.class, () -> batches.transferThenCatchFailure());
        balance(61, "100.50");
        balance(62, "100.50");
        entryCount("batch-first", "0");
    }
}
