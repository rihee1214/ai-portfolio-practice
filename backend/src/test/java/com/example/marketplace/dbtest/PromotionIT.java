package com.example.marketplace.dbtest;


import com.example.marketplace.promotion.api.CouponReservations;
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
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionIT extends PostgresContractFixture {
    @Test void couponBatchConcurrentIdenticalInsertsRespectAllUniqueKeys() throws Exception {
        for(int round=0;round<8;round++) {
            var request=reservationRequest("all-unique-"+round);var barrier=new java.util.concurrent.CyclicBarrier(2);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var futures=List.of(0,1).stream().map(index->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return couponReservations().reserve(List.of(request));})).toList();
                var first=futures.get(0).get(15,TimeUnit.SECONDS);assertEquals(first,futures.get(1).get(15,TimeUnit.SECONDS));assertEquals("RESERVED",first.getFirst().state());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_batch WHERE checkout_id=:id",Map.of("id",request.checkoutId()),Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",request.reservationId()),Integer.class));
            }
        }
    }

    @Test void couponBatchInsertPreservesCrossSellerLockOrder() throws Exception {couponBatchInsertLockOrder(true);}

    @Test void couponBatchInsertPreservesSameBuyerLockOrder() throws Exception {couponBatchInsertLockOrder(false);}

    @Test void couponReservationRechecksDistinctBuyerSellerChanges() throws Exception {
        for(String mode:List.of("seller","hidden","store","role","price","promotion","coupon")){var original=reservationRequest("distinct-"+mode);UUID buyer=activeMember("reservation-distinct-"+mode+"@example.com");var c=coupons().claim(buyer,coupons().own(original.memberId(),original.issuedCouponId()).templateId());var line=original.quoteSnapshot().stores().getFirst().lines().getFirst();UUID promotion=null;
            if(mode.equals("promotion")){var p=promotions().save(original.memberId(),null,promotionInput(line.productId(),"DRAFT",0));promotion=promotions().save(original.memberId(),p.id(),promotionInput(line.productId(),"ACTIVE",0)).id();}
            var q=quotes().calculate(buyer,List.of(quoteLine(line.skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));var r=new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),buyer,c.storeId(),c.id(),q,original.expiresAt());UUID lock=java.util.stream.Stream.of(buyer,original.memberId()).min(java.util.Comparator.comparing(UUID::toString)).orElseThrow(),event=promotion;
            try(var pool=Executors.newSingleThreadExecutor()){var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR NO KEY UPDATE",Map.of("id",lock),UUID.class);result.set(pool.submit(()->{try{couponReservations().reserve(List.of(r));return true;}catch(RuntimeException e){return false;}}));awaitCouponLock("identity_user");switch(mode){case "seller"->jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",original.memberId()));case "hidden"->jdbc.update("UPDATE marketplace.seller_store SET visible=false WHERE id=:id",Map.of("id",c.storeId()));case "store"->jdbc.update("UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE id=:id",Map.of("id",c.storeId()));case "role"->jdbc.update("DELETE FROM marketplace.role_assignment WHERE user_id=:id AND role='SELLER'",Map.of("id",original.memberId()));case "price"->jdbc.update("UPDATE marketplace.catalog_sku SET price=20000,version=version+1 WHERE id=:id",Map.of("id",line.skuId()));case "promotion"->jdbc.update("UPDATE marketplace.promotion SET state='STOPPED',version=version+1 WHERE id=:id",Map.of("id",event));case "coupon"->couponReservations().reserve(List.of(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),buyer,c.storeId(),c.id(),q,r.expiresAt())));default->throw new IllegalStateException();}});assertFalse(result.get().get(10,TimeUnit.SECONDS));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",r.reservationId()),Integer.class));assertEquals(mode.equals("coupon")?"RESERVED":"AVAILABLE",coupons().own(buyer,c.id()).state());}
        }
    }

    @Test void couponTransitionsAndRestoreAuditFailuresRollback(){
        for(String action:List.of("COMMITTED","RELEASED","EXPIRED","RESTORED")){var batch=reservationBatch("audit-"+action.toLowerCase());var api=couponReservations();api.reserve(batch);var ids=batch.stream().map(CouponReservations.Request::reservationId).sorted(java.util.Comparator.comparing(UUID::toString)).toList();if(action.equals("RESTORED")){api.commit(batch.getFirst().checkoutId(),ids);}UUID failure=ids.getLast();var request=batch.stream().filter(r->r.reservationId().equals(failure)).findFirst().orElseThrow();var full=new CouponReservations.RestoreRequest(UUID.randomUUID(),request.sellerOrderRef(),failure,new CouponReservations.RefundCompletion(true,true));var before=reservationState();jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event ADD CONSTRAINT p07_d_transition_fail CHECK(reservation_id<>'"+failure+"' OR action<>'"+action+"')");
            try{assertThrows(RuntimeException.class,()->{switch(action){case "COMMITTED"->api.commit(request.checkoutId(),ids);case "RELEASED"->api.release(request.checkoutId(),ids);case "EXPIRED"->reservationClock(Clock.fixed(request.expiresAt(),ZoneOffset.UTC)).expire(request.checkoutId(),ids);case "RESTORED"->api.restore(full);default->throw new IllegalStateException();}});assertEquals(before,reservationState());}finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event DROP CONSTRAINT p07_d_transition_fail");}
        }
    }

    @Test void couponDifferentRestoreCausesCannotAffectNextUse() throws Exception {
        var r=reservationRequest("different-causes");var api=couponReservations();api.reserve(List.of(r));api.commit(r.checkoutId(),List.of(r.reservationId()));long version=coupons().own(r.memberId(),r.issuedCouponId()).version();var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var futures=java.util.stream.IntStream.range(0,2).mapToObj(n->pool.submit(()->{go.await();return api.restore(new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true)));})).toList();go.countDown();assertTrue(futures.get(0).get(10,TimeUnit.SECONDS).restored()^futures.get(1).get(10,TimeUnit.SECONDS).restored());}assertEquals(version+1,coupons().own(r.memberId(),r.issuedCouponId()).version());
        var line=r.quoteSnapshot().stores().getFirst().lines().getFirst();var q=quotes().calculate(r.memberId(),List.of(quoteLine(line.skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(r.storeId(),r.issuedCouponId())));var next=new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.memberId(),r.storeId(),r.issuedCouponId(),q,r.expiresAt());api.reserve(List.of(next));var before=coupons().own(r.memberId(),r.issuedCouponId());var late=api.restore(new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true)));assertFalse(late.restored());assertEquals("ALREADY_RESTORED",late.reason());assertEquals(before,coupons().own(r.memberId(),r.issuedCouponId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE reservation_id=:id AND restored",Map.of("id",r.reservationId()),Integer.class));
    }

    @Test void couponReservationDeadlineBoundariesAndRetry(){
        var r=reservationRequest("deadline-boundaries");Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var clock=new GuestClock();clock.now=now;var api=reservationClock(clock);var before=reservationState();for(Instant end:List.of(now.minusSeconds(1),now,now.plusSeconds(900).plusNanos(1000))){assertThrows(RuntimeException.class,()->api.reserve(List.of(new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),end))));assertEquals(before,reservationState());}
        Instant couponEnd=coupons().own(r.memberId(),r.issuedCouponId()).terms().endsAt();clock.now=couponEnd.minusSeconds(100);assertThrows(RuntimeException.class,()->api.reserve(List.of(new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),couponEnd.plusSeconds(1)))));clock.now=now;var exact=new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),now.plusSeconds(900).plusNanos(789));var saved=api.reserve(List.of(exact));assertEquals(now.plusSeconds(900),saved.getFirst().expiresAt());var normalized=new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),now.plusSeconds(900));assertEquals(saved,api.reserve(List.of(normalized)));clock.now=now.plusSeconds(1000);jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",r.memberId()));assertEquals(saved,api.reserve(List.of(exact)));
    }

    @Test void couponCheckoutCannotAcceptAnotherBatch() throws Exception {
        var a=reservationRequest("checkout-a");var b=reservationRequest("checkout-b");var api=couponReservations();api.reserve(List.of(a));var appended=new CouponReservations.Request(b.reservationId(),a.checkoutId(),b.sellerOrderRef(),b.memberId(),b.storeId(),b.issuedCouponId(),b.quoteSnapshot(),b.expiresAt());var before=reservationState();assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->api.reserve(List.of(appended)));assertEquals(before,reservationState());
        var extra=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var sku=b.quoteSnapshot().stores().getFirst().lines().getFirst().skuId();var q=quotes().calculate(a.memberId(),List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(b.storeId(),extra.id())));var sameMember=new CouponReservations.Request(UUID.randomUUID(),a.checkoutId(),UUID.randomUUID(),a.memberId(),b.storeId(),extra.id(),q,a.expiresAt());var sameBefore=reservationState();assertThrows(RuntimeException.class,()->api.reserve(List.of(sameMember)));assertEquals(sameBefore,reservationState());assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.coupon_reservation SET member_id=:member WHERE id=:id",Map.of("member",b.memberId(),"id",a.reservationId())));assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.coupon_reservation SET request_hash=:hash WHERE id=:id",Map.of("hash","f".repeat(64),"id",a.reservationId())));assertEquals(sameBefore,reservationState());
        var c=reservationRequest("checkout-c");var d=reservationRequest("checkout-d");var sameCheckout=new CouponReservations.Request(d.reservationId(),c.checkoutId(),d.sellerOrderRef(),d.memberId(),d.storeId(),d.issuedCouponId(),d.quoteSnapshot(),d.expiresAt());var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var futures=List.of(c,sameCheckout).stream().map(r->pool.submit(()->{go.await();try{api.reserve(List.of(r));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}})).toList();go.countDown();assertTrue(futures.get(0).get(10,TimeUnit.SECONDS)^futures.get(1).get(10,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE checkout_id=:id",Map.of("id",c.checkoutId()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE id IN (:a,:b) AND state='AVAILABLE'",Map.of("a",c.issuedCouponId(),"b",d.issuedCouponId()),Integer.class));
    }

    @Test void couponBatchRetryHasOneReadSnapshot() throws Exception {
        var batch=reservationBatch("retry-snapshot");var api=couponReservations();api.reserve(batch);var read=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        var probe=new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()){@Override public <T> List<T> query(String sql,Map<String,?> params,org.springframework.jdbc.core.RowMapper<T> mapper){var result=super.query(sql,params,mapper);if(sql.startsWith("SELECT * FROM marketplace.coupon_reservation WHERE")&&once.compareAndSet(false,true)){read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return result;}};
        var snapshot=new com.example.marketplace.promotion.infrastructure.JdbcCouponReservations(probe,quotes(),coupons(),context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.seller.api.Stores.class),authMapper,transactionManager);
        try(var pool=Executors.newSingleThreadExecutor()){var future=pool.submit(()->snapshot.reserve(batch));try{assertTrue(read.await(10,TimeUnit.SECONDS));api.commit(batch.getFirst().checkoutId(),batch.stream().map(CouponReservations.Request::reservationId).toList());}finally{release.countDown();}var result=future.get(10,TimeUnit.SECONDS);assertEquals(List.of("RESERVED","RESERVED"),result.stream().map(CouponReservations.Reservation::state).toList());assertTrue(api.reserve(batch).stream().allMatch(r->r.state().equals("COMMITTED")));}
    }

    @Test void couponFullRestoreRejectsUnusedStates(){
        for(String state:List.of("RESERVED","RELEASED","EXPIRED")){var r=reservationRequest("unused-"+state.toLowerCase());var api=couponReservations();api.reserve(List.of(r));if(state.equals("RELEASED")){api.release(r.checkoutId(),List.of(r.reservationId()));}if(state.equals("EXPIRED")){reservationClock(Clock.fixed(r.expiresAt(),ZoneOffset.UTC)).expire(r.checkoutId(),List.of(r.reservationId()));}var before=reservationState();var full=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true));assertEquals(409,assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->api.restore(full)).status());assertEquals(before,reservationState());if(state.equals("RESERVED")){api.commit(r.checkoutId(),List.of(r.reservationId()));assertTrue(api.restore(full).restored());}}
    }

    @Test void couponReservationCrossSellerAndConcurrentRetries() throws Exception {
        var a=reservationRequest("cross-a");var b=reservationRequest("cross-b");var forA=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var forB=coupons().claim(b.memberId(),coupons().own(a.memberId(),a.issuedCouponId()).templateId());var requests=new java.util.ArrayList<CouponReservations.Request>();
        for(var pair:List.of(Map.entry(a,forA),Map.entry(b,forB))){var owner=pair.getKey();var c=pair.getValue();var sku=(c.storeId().equals(a.storeId())?a:b).quoteSnapshot().stores().getFirst().lines().getFirst().skuId();var q=quotes().calculate(owner.memberId(),List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(c.storeId(),c.id())));requests.add(new CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),owner.memberId(),c.storeId(),c.id(),q,owner.expiresAt()));}
        var api=couponReservations();var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var futures=requests.stream().map(r->pool.submit(()->{go.await();return api.reserve(List.of(r));})).toList();go.countDown();for(var future:futures){assertEquals("RESERVED",future.get(10,TimeUnit.SECONDS).getFirst().state());}}
        var r=reservationRequest("same-retry");var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->{start.await();return api.reserve(List.of(r));});var y=pool.submit(()->{start.await();return api.reserve(List.of(r));});start.countDown();assertEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}api.commit(r.checkoutId(),List.of(r.reservationId()));var restore=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true));var restoreGo=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->{restoreGo.await();return api.restore(restore);});var y=pool.submit(()->{restoreGo.await();return api.restore(restore);});restoreGo.countDown();assertEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE reservation_id=:id",Map.of("id",r.reservationId()),Integer.class));
    }

    @Test void couponCommitReleaseAndExpiryRace() throws Exception {
        var api=couponReservations();var r=reservationRequest("race");api.reserve(List.of(r));var go=new CountDownLatch(1);var ids=List.of(r.reservationId());
        try(var pool=Executors.newFixedThreadPool(2)){var commit=pool.submit(()->{go.await();try{api.commit(r.checkoutId(),ids);return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}});var release=pool.submit(()->{go.await();try{api.release(r.checkoutId(),ids);return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}});go.countDown();assertTrue(commit.get(10,TimeUnit.SECONDS)^release.get(10,TimeUnit.SECONDS));}
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",r.reservationId()),Integer.class));
        var exp=reservationRequest("expiry");var clock=new GuestClock();clock.now=Instant.now();var timed=reservationClock(clock);timed.reserve(List.of(exp));var expIds=List.of(exp.reservationId());
        try(var pool=Executors.newFixedThreadPool(2)){var commit=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();var expiry=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<List<CouponReservations.Reservation>>>();new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.issued_coupon WHERE id=:id FOR UPDATE",Map.of("id",exp.issuedCouponId()),UUID.class);commit.set(pool.submit(()->{try{timed.commit(exp.checkoutId(),expIds);return true;}catch(com.example.marketplace.promotion.api.PromotionRejected e){return false;}}));awaitCouponLock("issued_coupon");clock.now=exp.expiresAt();expiry.set(pool.submit(()->timed.expire(exp.checkoutId(),expIds)));});assertFalse(commit.get().get(10,TimeUnit.SECONDS));var result=expiry.get().get(10,TimeUnit.SECONDS);assertEquals("EXPIRED",result.getFirst().state());assertEquals(result,timed.expire(exp.checkoutId(),expIds));assertEquals("AVAILABLE",coupons().own(exp.memberId(),exp.issuedCouponId()).state());}
    }

    @Test void couponReservationRechecksWaitsAndQuoteContent() throws Exception {
        for(boolean deadline:List.of(false,true)){var r=reservationRequest(deadline?"wait-time":"wait-member");var clock=new GuestClock();clock.now=Instant.now();var api=reservationClock(clock);try(var pool=Executors.newSingleThreadExecutor()){var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR NO KEY UPDATE",Map.of("id",r.memberId()),UUID.class);future.set(pool.submit(()->{try{api.reserve(List.of(r));return true;}catch(RuntimeException e){return false;}}));awaitCouponLock("identity_user");if(deadline){clock.now=r.expiresAt();}else{jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",r.memberId()));}});assertFalse(future.get().get(10,TimeUnit.SECONDS));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",r.reservationId()),Integer.class));assertEquals("AVAILABLE",jdbc.queryForObject("SELECT state FROM marketplace.issued_coupon WHERE id=:id",Map.of("id",r.issuedCouponId()),String.class));}}
        var r=reservationRequest("forged");var q=r.quoteSnapshot();var forged=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),q.stores(),1,q.fingerprint());var bad=new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),forged,r.expiresAt());assertThrows(RuntimeException.class,()->couponReservations().reserve(List.of(bad)));
        var changed=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),q.stores(),1,com.example.marketplace.promotion.domain.QuoteCalculator.fingerprint(forged));assertThrows(RuntimeException.class,()->couponReservations().reserve(List.of(new CouponReservations.Request(r.reservationId(),r.checkoutId(),r.sellerOrderRef(),r.memberId(),r.storeId(),r.issuedCouponId(),changed,r.expiresAt()))));
        jdbc.update("UPDATE marketplace.catalog_sku SET price=20000,version=version+1 WHERE id=:id",Map.of("id",q.stores().getFirst().lines().getFirst().skuId()));assertThrows(RuntimeException.class,()->couponReservations().reserve(List.of(r)));assertEquals("AVAILABLE",coupons().own(r.memberId(),r.issuedCouponId()).state());
    }

    @Test void couponReservationBatchAndAuditRollback(){
        var a=reservationRequest("batch-a");var b=reservationRequest("batch-b");var second=coupons().claim(a.memberId(),coupons().own(b.memberId(),b.issuedCouponId()).templateId());var lines=java.util.stream.Stream.of(a,b).flatMap(r->r.quoteSnapshot().stores().stream()).flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList();var q=quotes().calculate(a.memberId(),lines,List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(a.storeId(),a.issuedCouponId()),new com.example.marketplace.promotion.api.Quotes.CouponSelection(b.storeId(),second.id())));
        var ra=new CouponReservations.Request(a.reservationId(),a.checkoutId(),a.sellerOrderRef(),a.memberId(),a.storeId(),a.issuedCouponId(),q,a.expiresAt());var rb=new CouponReservations.Request(b.reservationId(),a.checkoutId(),b.sellerOrderRef(),a.memberId(),b.storeId(),second.id(),q,a.expiresAt());var batch=List.of(ra,rb);var api=couponReservations();
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event ADD CONSTRAINT p07_d_audit_fail CHECK(reservation_id<>'"+rb.reservationId()+"')");try{assertThrows(RuntimeException.class,()->api.reserve(batch));assertEquals("AVAILABLE",coupons().own(a.memberId(),a.issuedCouponId()).state());assertEquals("AVAILABLE",coupons().own(a.memberId(),second.id()).state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE checkout_id=:id",Map.of("id",a.checkoutId()),Integer.class));}finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_reservation_event DROP CONSTRAINT p07_d_audit_fail");}
        var reserved=api.reserve(batch);assertEquals(reserved,api.reserve(List.of(rb,ra)));assertThrows(RuntimeException.class,()->api.reserve(List.of(ra)));var ids=List.of(ra.reservationId(),rb.reservationId());assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{api.commit(a.checkoutId(),ids);throw new IllegalStateException("after commit rollback");}));assertEquals("RESERVED",coupons().own(a.memberId(),second.id()).state());var released=api.release(a.checkoutId(),ids);assertEquals(released,api.release(a.checkoutId(),ids));assertThrows(RuntimeException.class,()->api.commit(a.checkoutId(),ids));
    }

    @Test void couponRestorationPreservesExpiryAndCause(){
        var r=reservationRequest("restore-expired");var api=couponReservations();api.reserve(List.of(r));api.commit(r.checkoutId(),List.of(r.reservationId()));var unconfirmed=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,false));assertFalse(api.restore(unconfirmed).restored());assertEquals(api.restore(unconfirmed),api.restore(unconfirmed));assertThrows(RuntimeException.class,()->api.restore(new CouponReservations.RestoreRequest(unconfirmed.causeId(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true))));
        var clock=new GuestClock();var ends=coupons().own(r.memberId(),r.issuedCouponId()).terms().endsAt();clock.now=ends;var timed=reservationClock(clock);var full=new CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new CouponReservations.RefundCompletion(true,true));var before=coupons().own(r.memberId(),r.issuedCouponId());assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{timed.restore(full);throw new IllegalStateException("restore caller rollback");}));assertEquals(before,coupons().own(r.memberId(),r.issuedCouponId()));var restored=timed.restore(full);assertTrue(restored.restored());assertFalse(restored.reusable());assertEquals(ends,coupons().own(r.memberId(),r.issuedCouponId()).terms().endsAt());assertFalse(coupons().eligible(r.memberId(),r.issuedCouponId(),r.storeId(),10000,ends).eligible());assertEquals(restored,timed.restore(full));
    }

    @Test void couponReservationIsAtomicAndIdempotent() throws Exception {
        var r=reservationRequest("atomic");var api=couponReservations();var before=coupons().own(r.memberId(),r.issuedCouponId());
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{api.reserve(List.of(r));throw new IllegalStateException("caller rollback");}));assertEquals(before,coupons().own(r.memberId(),r.issuedCouponId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE id=:id",Map.of("id",r.reservationId()),Integer.class));
        var other=new com.example.marketplace.promotion.api.CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.memberId(),r.storeId(),r.issuedCouponId(),r.quoteSnapshot(),r.expiresAt());var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){var futures=List.of(r,other).stream().map(request->pool.submit(()->{go.await();try{return api.reserve(List.of(request)).getFirst();}catch(com.example.marketplace.promotion.api.PromotionRejected e){return null;}})).toList();go.countDown();var results=new java.util.ArrayList<com.example.marketplace.promotion.api.CouponReservations.Reservation>();for(var future:futures){var result=future.get(10,TimeUnit.SECONDS);if(result!=null){results.add(result);}}assertEquals(1,results.size());var winner=results.getFirst().id().equals(r.reservationId())?r:other;assertEquals(results,api.reserve(List.of(winner)));assertEquals("RESERVED",coupons().own(r.memberId(),r.issuedCouponId()).state());
            var changed=new com.example.marketplace.promotion.api.CouponReservations.Request(winner.reservationId(),winner.checkoutId(),UUID.randomUUID(),winner.memberId(),winner.storeId(),winner.issuedCouponId(),winner.quoteSnapshot(),winner.expiresAt());assertThrows(RuntimeException.class,()->api.reserve(List.of(changed)));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE coupon_id=:id",Map.of("id",r.issuedCouponId()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation_event WHERE reservation_id=:id",Map.of("id",winner.reservationId()),Integer.class));
        }
    }

    @Test void couponRestoreCannotAffectNewReservation(){
        var r=reservationRequest("restore");var api=couponReservations();api.reserve(List.of(r));var ids=List.of(r.reservationId());var committed=api.commit(r.checkoutId(),ids);assertEquals(committed,api.commit(r.checkoutId(),ids));assertEquals("USED",coupons().own(r.memberId(),r.issuedCouponId()).state());
        var partial=new com.example.marketplace.promotion.api.CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new com.example.marketplace.promotion.api.CouponReservations.RefundCompletion(false,true));assertFalse(api.restore(partial).restored());assertEquals("USED",coupons().own(r.memberId(),r.issuedCouponId()).state());var full=new com.example.marketplace.promotion.api.CouponReservations.RestoreRequest(UUID.randomUUID(),r.sellerOrderRef(),r.reservationId(),new com.example.marketplace.promotion.api.CouponReservations.RefundCompletion(true,true));var restored=api.restore(full);assertTrue(restored.restored());assertTrue(restored.reusable());assertEquals(restored,api.restore(full));
        var q=quotes().calculate(r.memberId(),r.quoteSnapshot().stores().stream().flatMap(s->s.lines().stream()).map(l->quoteLine(l.skuId(),l.quantity())).toList(),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(r.storeId(),r.issuedCouponId())));var next=new com.example.marketplace.promotion.api.CouponReservations.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.memberId(),r.storeId(),r.issuedCouponId(),q,r.expiresAt());api.reserve(List.of(next));var current=coupons().own(r.memberId(),r.issuedCouponId());assertEquals(restored,api.restore(full));assertThrows(RuntimeException.class,()->api.release(r.checkoutId(),ids));assertEquals(current,coupons().own(r.memberId(),r.issuedCouponId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE reservation_id=:id AND restored",Map.of("id",r.reservationId()),Integer.class));
    }

    @Test void quoteMultipleLinesFingerprintAndHttpContract() throws Exception {
        var a=browseFixture("quote-multi-a",100,10);var b=browseFixture("quote-multi-b",100,10);UUID owner=(UUID)a.get("owner"),member=activeMember("quote-multi@example.com"),product=((com.example.marketplace.catalog.api.Products.Product)a.get("product")).id();var ids=new java.util.ArrayList<UUID>();
        for(int n=0;n<3;n++){var sku=context.getBean(com.example.marketplace.catalog.api.Skus.class).save(owner,product,null,new com.example.marketplace.catalog.api.Skus.Input(List.of(new com.example.marketplace.catalog.api.Skus.Option("종류","선택"+n)),100L,true,0L));ids.add(sku.id());jdbc.update("INSERT INTO marketplace.inventory_balance(sku_id,on_hand) VALUES(:id,10)",Map.of("id",sku.id()));}ids.sort(java.util.Comparator.comparing(UUID::toString));
        var ta=couponTemplate(owner,"배분100",5);var tb=couponTemplate((UUID)b.get("owner"),"둘째100",5);var ca=coupons().claim(member,ta.id());var cb=coupons().claim(member,tb.id());var selections=new java.util.ArrayList<>(List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(ta.storeId(),ca.id()),new com.example.marketplace.promotion.api.Quotes.CouponSelection(tb.storeId(),cb.id())));var lines=new java.util.ArrayList<>(ids.stream().map(id->quoteLine(id,1)).toList());lines.add(quoteLine((UUID)b.get("sku"),1));
        var before=quoteReadOnlyState();var q=quotes().calculate(member,lines,selections);var store=q.stores().stream().filter(s->s.storeId().equals(ta.storeId())).findFirst().orElseThrow();assertEquals(List.of(34L,33L,33L),store.lines().stream().map(l->l.couponDiscount()).toList());assertEquals(3200,store.total());assertEquals(6200,q.total());store.lines().forEach(l->assertEquals(l.net(),l.units().stream().mapToLong(u->u.net()).sum()));java.util.Collections.reverse(lines);java.util.Collections.reverse(selections);var reverse=quotes().calculate(member,lines,selections);assertEquals(q.stores(),reverse.stores());assertEquals(q.fingerprint(),reverse.fingerprint());assertEquals(before,quoteReadOnlyState());
        var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("quote-multi@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());var response=accountRequest(browser,"POST","/api/quotes",Map.of("lines",lines,"coupons",selections),csrfToken(browser));assertEquals(200,response.statusCode());var tree=authMapper.readTree(response.body());assertEquals(java.util.Set.of("policyVersion","evaluatedAt","fingerprint","total","stores"),authMapper.readValue(response.body(),Map.class).keySet());
        for(var s:tree.get("stores")){for(String key:List.of("subtotal","productDiscount","afterPromotion","couponDiscount","shippingBase","shippingDiscount","total")){assertTrue(s.get(key).isString());assertTrue(s.get(key).asText().matches("0|[1-9][0-9]*"));}assertTrue(s.get("coupon").get("version").isIntegralNumber());assertTrue(s.get("coupon").get("templateVersion").isIntegralNumber());for(var l:s.get("lines")){for(String key:List.of("unitPrice","gross","promotionDiscount","couponDiscount","net")){assertTrue(l.get(key).isString());}for(var u:l.get("units")){for(String key:List.of("gross","promotionDiscount","couponDiscount","net")){assertTrue(u.get(key).isString());}}}}
    }

    @Test void quoteRejectsEveryUnavailableProjection() {
        var f=browseFixture("quote-unavailable",100,10);UUID sku=(UUID)f.get("sku"),owner=(UUID)f.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");var values=Map.of("sku",sku,"product",product.id(),"owner",owner);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        for(String sql:List.of("UPDATE marketplace.catalog_sku SET active=false WHERE id=:sku","UPDATE marketplace.catalog_product SET state='STOPPED' WHERE id=:product","UPDATE marketplace.catalog_category SET active=false WHERE id=(SELECT category_id FROM marketplace.catalog_product WHERE id=:product)","UPDATE marketplace.seller_store SET visible=false WHERE owner_id=:owner","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE owner_id=:owner","UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:owner","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'","UPDATE marketplace.inventory_balance SET reserved=on_hand WHERE sku_id=:sku")){
            tx.executeWithoutResult(status->{try{jdbc.update(sql,values);assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->quotes().calculate(null,List.of(quoteLine(sku,1)),List.of()));}finally{status.setRollbackOnly();}});assertEquals(3100,quotes().calculate(null,List.of(quoteLine(sku,1)),List.of()).total());
        }
    }

    @Test void quoteReadSnapshotSurvivesConcurrentPrices() throws Exception {
        var a=browseFixture("quote-consistent-a",30000,10);var b=browseFixture("quote-consistent-b",40000,10);var read=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        var delegate=context.getBean(com.example.marketplace.catalog.api.Browse.class);
        var probe=new com.example.marketplace.catalog.api.Browse(){
            public com.example.marketplace.catalog.api.Products.Page<Item> search(Query q){return delegate.search(q);}public Detail detail(String s){return delegate.detail(s);}public Detail forSku(UUID s){return delegate.forSku(s);}public Detail forProduct(UUID p){return delegate.forProduct(p);}public UUID identify(String s){return delegate.identify(s);}
            public QuoteOffer quoteOffer(UUID id){var result=delegate.quoteOffer(id);if(once.compareAndSet(false,true)){assertEquals("repeatable read",jdbc.getJdbcTemplate().queryForObject("SHOW transaction_isolation",String.class));read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return result;}
        };
        var api=new com.example.marketplace.promotion.infrastructure.JdbcQuotes(probe,promotions(),coupons(),context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),context.getBean(PlatformTransactionManager.class));var lines=List.of(quoteLine((UUID)a.get("sku"),1),quoteLine((UUID)b.get("sku"),1));
        try(var pool=Executors.newSingleThreadExecutor()) {var future=pool.submit(()->api.calculate(null,lines,List.of()));try{assertTrue(read.await(10,TimeUnit.SECONDS));jdbc.update("UPDATE marketplace.catalog_sku SET price=50000,version=version+1 WHERE id IN (:a,:b)",Map.of("a",a.get("sku"),"b",b.get("sku")));}finally{release.countDown();}assertEquals(76000,future.get(10,TimeUnit.SECONDS).total());assertEquals(100000,quotes().calculate(null,lines,List.of()).total());}
    }

    @Test void quoteShippingAndZeroTotalsUsePromotionOrder() throws Exception {
        var f=browseFixture("quote-zero",50000,99);UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var lines=List.of(quoteLine(sku,1));
        var t=coupons().saveTemplate(owner,null,couponInput("전액 쿠폰",50000,5,"DRAFT",0));t=coupons().saveTemplate(owner,t.id(),couponInput("전액 쿠폰",50000,5,"ACTIVE",0));var c=coupons().claim(owner,t.id());var selected=List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id()));assertEquals(0,quotes().calculate(owner,lines,selected).total());
        var p=promotions().save(owner,null,promotionInput(product,"DRAFT",0));promotions().save(owner,p.id(),promotionInput(product,"ACTIVE",0));var q=quotes().calculate(owner,lines,selected);assertEquals(45000,q.stores().getFirst().afterPromotion());assertEquals(3000,q.total());
        var starts=Instant.now().minusSeconds(60);var ends=starts.plusSeconds(3600);var ids=new java.util.ArrayList<UUID>();
        for(int n=0;n<2;n++){var input=new com.example.marketplace.promotion.api.Promotions.Input("무료 배송", "SHIPPING",List.of(),starts,ends,"FREE",0L,null,"DRAFT",0L);var draft=promotions().save(owner,null,input);var active=promotions().save(owner,draft.id(),new com.example.marketplace.promotion.api.Promotions.Input(input.title(),input.scope(),input.productIds(),starts,ends,"FREE",0L,null,"ACTIVE",0L));ids.add(active.id());}
        var zero=quotes().calculate(owner,lines,selected);assertEquals(0,zero.total());assertEquals(3000,zero.stores().getFirst().shippingDiscount());assertEquals(ids.stream().min(java.util.Comparator.comparing(UUID::toString)).orElseThrow(),zero.stores().getFirst().shippingPromotion().id());assertEquals(0,zero.stores().getFirst().lines().getFirst().units().getFirst().net());
        var browser=authBrowser();var response=accountRequest(browser,"POST","/api/quotes",Map.of("lines",lines,"coupons",List.of()),csrfToken(browser));assertEquals(200,response.statusCode());var web=authMapper.readTree(response.body()).get("stores").get(0);assertEquals(zero.stores().getFirst().shippingPromotion().id().toString(),web.get("shippingPromotion").get("id").asText());assertEquals(1,web.get("shippingPromotion").get("version").asLong());assertEquals(p.id().toString(),web.get("lines").get(0).get("promotion").get("id").asText());assertEquals("MAX_DISCOUNT_ID_TIE",web.get("lines").get(0).get("promotion").get("selectionReason").asText());
    }

    @Test void quotesUseCurrentAuthorizedInputs() throws Exception {
        var a=browseFixture("quote-a",30000,10);var b=browseFixture("quote-b",40000,10);UUID owner=(UUID)a.get("owner"),skuA=(UUID)a.get("sku"),skuB=(UUID)b.get("sku"),member=activeMember("quote-member@example.com"),other=activeMember("quote-other@example.com");var api=quotes();var lines=List.of(quoteLine(skuA,1),quoteLine(skuB,1));
        var guest=api.calculate(null,lines,List.of());assertEquals(76000,guest.total());assertEquals(2,guest.stores().size());assertEquals(guest.stores(),api.calculate(member,lines,List.of()).stores());
        var d=coupons().saveTemplate(owner,null,couponInput("견적 쿠폰",3000,10,"DRAFT",0));var t=coupons().saveTemplate(owner,d.id(),couponInput("견적 쿠폰",3000,10,"ACTIVE",0));var coupon=coupons().claim(member,t.id());var selected=List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),coupon.id()));
        long events=jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event",Map.of(),Long.class),reservations=jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation",Map.of(),Long.class);var q=api.calculate(member,lines,selected);assertEquals(73000,q.total());assertEquals(3000,q.stores().stream().mapToLong(x->x.couponDiscount()).sum());assertEquals(coupon,coupons().own(member,coupon.id()));assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event",Map.of(),Long.class));assertEquals(reservations,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_reservation",Map.of(),Long.class));
        assertThrows(RuntimeException.class,()->api.calculate(null,lines,selected));assertThrows(RuntimeException.class,()->api.calculate(other,lines,selected));assertThrows(RuntimeException.class,()->api.calculate(member,lines,List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(UUID.randomUUID(),coupon.id()))));
        assertThrows(RuntimeException.class,()->api.calculate(member,lines,List.of(selected.getFirst(),selected.getFirst())));assertThrows(RuntimeException.class,()->api.calculate(null,List.of(quoteLine(skuA,1),quoteLine(skuA,2)),List.of()));
        for(int quantity:new int[]{0,11,100}){assertThrows(RuntimeException.class,()->api.calculate(null,List.of(quoteLine(skuA,quantity)),List.of()));}
        assertThrows(RuntimeException.class,()->api.calculate(null,java.util.stream.IntStream.range(0,21).mapToObj(n->quoteLine(UUID.randomUUID(),1)).toList(),List.of()));
        var browser=authBrowser();var body=Map.of("lines",List.of(Map.of("skuId",skuA,"quantity",1,"price",1),Map.of("skuId",skuB,"quantity",1)),"coupons",List.of(),"total","1");assertEquals(403,accountRequest(browser,"POST","/api/quotes",body,null).statusCode());var response=accountRequest(browser,"POST","/api/quotes",body,csrfToken(browser));assertEquals(200,response.statusCode());var tree=authMapper.readTree(response.body());assertTrue(tree.get("total").isString());assertEquals("76000",tree.get("total").asText());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(tree.has("memberId"));
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",((com.example.marketplace.catalog.api.Products.Product)a.get("product")).id()));assertThrows(RuntimeException.class,()->api.calculate(null,lines,List.of()));
    }

    @Test void quoteSnapshotsRemainStable() {
        var f=browseFixture("quote-snapshot",10001,99);UUID owner=(UUID)f.get("owner"),sku=(UUID)f.get("sku"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var p=promotions().save(owner,null,promotionInput(product,"DRAFT",0));promotions().save(owner,p.id(),promotionInput(product,"ACTIVE",0));
        var t=couponTemplate(owner,"스냅샷 쿠폰",10);var c=coupons().claim(owner,t.id());var selection=List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(t.storeId(),c.id()));var lines=List.of(quoteLine(sku,3));var q=quotes().calculate(owner,lines,selection);var store=q.stores().getFirst();var line=store.lines().getFirst();
        assertEquals(30003,line.gross());assertEquals(3000,line.promotionDiscount());assertEquals(100,line.couponDiscount());assertEquals(26903,line.net());assertEquals(29903,q.total());assertEquals(34,line.units().getFirst().couponDiscount());assertEquals(line.net(),line.units().stream().mapToLong(u->u.net()).sum());
        assertEquals(q.fingerprint(),quotes().calculate(owner,lines,selection).fingerprint());assertThrows(UnsupportedOperationException.class,()->q.stores().clear());assertThrows(UnsupportedOperationException.class,()->line.units().clear());long refund=line.units().get(0).net()+line.units().get(1).net();
        jdbc.update("UPDATE marketplace.catalog_sku SET price=20000,version=version+1 WHERE id=:id",Map.of("id",sku));promotions().save(owner,p.id(),promotionInput(product,"STOPPED",1));var next=quotes().calculate(owner,lines,selection);assertEquals(59900,next.total());assertFalse(q.fingerprint().equals(next.fingerprint()));assertEquals(29903,q.total());assertEquals(refund,line.units().get(0).net()+line.units().get(1).net());
        var altered=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt().plusSeconds(100),q.stores(),q.total(),q.fingerprint());assertEquals(q.fingerprint(),com.example.marketplace.promotion.domain.QuoteCalculator.fingerprint(altered));
    }

    @Test void couponCrossClaimsAndManagementTransactions() throws Exception {
        UUID a=(UUID)browseFixture("coupon-cross-a",100,1).get("owner"),b=(UUID)browseFixture("coupon-cross-b",100,1).get("owner");var api=coupons();var ta=couponTemplate(a,"같은 이름",5);var tb=couponTemplate(b,"같은 이름",5);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{go.await();return api.claim(a,tb.id());});var y=pool.submit(()->{go.await();return api.claim(b,ta.id());});go.countDown();assertEquals(tb.storeId(),x.get(10,TimeUnit.SECONDS).storeId());assertEquals(ta.storeId(),y.get(10,TimeUnit.SECONDS).storeId());
        }
        var original=api.template(a,ta.id());var issued=api.claim(a,ta.id());long events=jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event WHERE template_id=:id",Map.of("id",ta.id()),Long.class);original=api.template(a,ta.id());
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event ADD CONSTRAINT p07_template_fail CHECK(template_id<>'"+ta.id()+"' OR action<>'TEMPLATE' OR version<2)");
        try{assertThrows(RuntimeException.class,()->api.saveTemplate(a,ta.id(),couponInput("바뀌면 안 됨",50,10,"STOPPED",1)));assertEquals(original,api.template(a,ta.id()));assertEquals(issued,api.own(a,issued.id()));assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event WHERE template_id=:id",Map.of("id",ta.id()),Long.class));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event DROP CONSTRAINT p07_template_fail");}
        var ready=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> save=()->{ready.await();try{api.saveTemplate(a,ta.id(),couponInput("한 승자",80,5,"ACTIVE",1));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}};var x=pool.submit(save);var y=pool.submit(save);ready.countDown();assertEquals(1,(x.get(10,TimeUnit.SECONDS)?1:0)+(y.get(10,TimeUnit.SECONDS)?1:0));}
        var race=couponTemplate(a,"하향 경쟁",2);api.claim(a,race.id());var raceStart=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var x=pool.submit(()->{raceStart.await();try{api.claim(b,race.id());return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}});
            var y=pool.submit(()->{raceStart.await();try{api.saveTemplate(a,race.id(),couponInput("하향 경쟁",100,1,"ACTIVE",1));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}});raceStart.countDown();assertEquals(1,(x.get(10,TimeUnit.SECONDS)?1:0)+(y.get(10,TimeUnit.SECONDS)?1:0));
        }
        var result=api.template(a,race.id());assertTrue(result.issuedCount()<=result.totalLimit());
    }

    @Test void couponAvailabilityRetryAndPagingBoundaries() {
        UUID owner=(UUID)browseFixture("coupon-visibility",100,1).get("owner"),member=activeMember("coupon-visibility-member@example.com"),other=activeMember("coupon-visibility-other@example.com");var api=coupons();var t=couponTemplate(owner,"보유",100);var c=api.claim(member,t.id());
        String[] off={"UPDATE marketplace.seller_store SET visible=false WHERE id=:store","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE id=:store","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'"};
        String[] on={"UPDATE marketplace.seller_store SET visible=true WHERE id=:store","UPDATE marketplace.seller_store SET state='OPEN',visible=true WHERE id=:store","INSERT INTO marketplace.role_assignment VALUES(:owner,'SELLER')"};var p=Map.of("store",t.storeId(),"owner",owner);
        for(int n=0;n<off.length;n++){assertTrue(couponAvailable(other,t.id()));jdbc.update(off[n],p);assertThrows(RuntimeException.class,()->api.claim(other,t.id()));assertFalse(couponAvailable(other,t.id()));assertFalse(api.eligible(member,c.id(),t.storeId(),100,Instant.now()).eligible());assertEquals(c,api.claim(member,t.id()));assertTrue(api.owned(member,0).items().contains(c));jdbc.update(on[n],p);}
        jdbc.update("UPDATE marketplace.coupon_template SET issue_end=:end WHERE id=:id",Map.of("id",t.id(),"end",java.sql.Timestamp.from(Instant.now().minusSeconds(1))));assertEquals(c,api.claim(member,t.id()));assertThrows(RuntimeException.class,()->api.claim(other,t.id()));
        jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",member));assertThrows(RuntimeException.class,()->api.claim(member,t.id()));jdbc.update("UPDATE marketplace.identity_user SET state='ACTIVE' WHERE id=:id",Map.of("id",member));
        for(int n=0;n<21;n++){var next=couponTemplate(owner,"동일 쿠폰",100);api.claim(member,next.id());}
        var first=api.owned(member,0);var second=api.owned(member,1);assertEquals(20,first.items().size());assertTrue(first.hasMore());assertEquals(2,second.items().size());assertFalse(second.hasMore());var ids=new java.util.HashSet<UUID>();first.items().forEach(x->assertTrue(ids.add(x.id())));second.items().forEach(x->assertTrue(ids.add(x.id())));assertEquals(0,api.owned(other,0).items().size());
        assertTrue(api.available(other,0).hasMore());assertEquals(20,api.available(other,0).items().size());assertTrue(api.available(member,0).items().stream().noneMatch(x->x.storeId().equals(t.storeId())));
    }

    @Test void couponHttpAmountsAndStoreIdentity() throws Exception {
        UUID seller=(UUID)browseFixture("coupon-http",100,1).get("owner"),member=activeMember("coupon-http-member@example.com");var api=coupons();Instant start=Instant.now().minusSeconds(60),end=start.plusSeconds(7200);
        var input=new com.example.marketplace.promotion.api.Coupons.TemplateInput("같은 이름","RATE",10000L,null,0L,start,end,start,end,50L,"DRAFT",0L);
        var draft=api.saveTemplate(seller,null,input);var active=api.saveTemplate(seller,draft.id(),new com.example.marketplace.promotion.api.Coupons.TemplateInput("같은 이름","RATE",10000L,null,0L,start,end,start,end,50L,"ACTIVE",0L));var c=api.claim(member,active.id());
        var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("coupon-http-member@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        for(long amount:new long[]{9007199254740991L,9007199254740992L,9007199254740993L,-1}) {
            var response=accountRequest(browser,"GET","/api/account/coupons/"+c.id()+"/eligibility?storeId="+active.storeId()+"&subtotal="+amount,null,null);assertEquals(amount>=0&&amount<=9007199254740991L?200:400,response.statusCode(),"subtotal="+amount);
            if(response.statusCode()==200){assertEquals(amount,authMapper.readTree(response.body()).get("discount").asLong());}
        }
        assertEquals(Long.MAX_VALUE,api.eligible(member,c.id(),active.storeId(),Long.MAX_VALUE,Instant.now()).discount());
        var response=accountRequest(browser,"GET","/api/account/coupons/"+c.id(),null,null);var tree=authMapper.readTree(response.body());assertEquals(context.getBean(com.example.marketplace.seller.api.Stores.class).own(seller).slug(),tree.get("storeSlug").asText());
        assertEquals(java.util.Set.of("id","templateId","storeId","title","terms","templateVersion","state","version","storeSlug","storeName"),authMapper.readValue(response.body(),Map.class).keySet());
        assertEquals(java.util.Set.of("kind","value","cap","minimum","startsAt","endsAt"),authMapper.readValue(tree.get("terms").toString(),Map.class).keySet());
        var admin=authBrowser();assertEquals(200,authRequest(admin,"/api/auth/login",loginForm("p4b-browse-coupon-http@example.com",SIGNUP_PASSWORD),csrfToken(admin)).statusCode());String csrf=csrfToken(admin);
        var base=new java.util.HashMap<String,Object>();base.put("title","경계");base.put("kind","FIXED");base.put("value",1L);base.put("cap",1L);base.put("minimum",0L);base.put("totalLimit",1L);base.put("issueStart",start.toString());base.put("issueEnd",end.toString());base.put("startsAt",start.toString());base.put("endsAt",end.toString());base.put("state","DRAFT");base.put("version",0L);
        for(String field:List.of("value","cap","minimum","totalLimit")){for(long number:new long[]{9007199254740991L,9007199254740992L,9007199254740993L,-1}){var values=new java.util.HashMap<>(base);values.put(field,number);var saved=accountRequest(admin,"POST","/api/seller/coupons",values,csrf);assertEquals(number>=0&&number<=9007199254740991L?201:400,saved.statusCode(),field+"="+number);}}
        var seller2=(UUID)browseFixture("coupon-http-other",100,1).get("owner");var duplicate=couponTemplate(seller2,"같은 이름",50);var second=api.claim(member,duplicate.id());assertFalse(c.storeSlug().equals(second.storeSlug()));assertEquals("같은 이름",second.title());

    }

    @Test void couponIssuanceIsBoundedAndIdempotent() throws Exception {
        UUID seller=(UUID)browseFixture("coupon-limit",100,1).get("owner");var api=coupons();var t=couponTemplate(seller,"한도",1);
        UUID a=activeMember("coupon-limit-a@example.com"),b=activeMember("coupon-limit-b@example.com");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{start.await();try{return api.claim(a,t.id());}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return null;}});
            var two=pool.submit(()->{start.await();try{return api.claim(b,t.id());}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return null;}});
            start.countDown();var x=one.get(10,TimeUnit.SECONDS);var y=two.get(10,TimeUnit.SECONDS);assertTrue((x==null)!=(y==null));UUID winner=x==null?b:a;
            assertEquals(x==null?y:x,api.claim(winner,t.id()));assertEquals(1,api.template(seller,t.id()).issuedCount());
        }
        var same=couponTemplate(seller,"재시도",10);var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){Callable<com.example.marketplace.promotion.api.Coupons.Issued> request=()->{ready.await();return api.claim(a,same.id());};var x=pool.submit(request);var y=pool.submit(request);ready.countDown();assertEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}
        assertEquals(1,api.template(seller,same.id()).issuedCount());
        var rollback=couponTemplate(seller,"감사 실패",10);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event ADD CONSTRAINT p07_b_fail CHECK(template_id<>'"+rollback.id()+"' OR action<>'CLAIM')");
        try{assertThrows(RuntimeException.class,()->api.claim(a,rollback.id()));assertEquals(0,api.template(seller,rollback.id()).issuedCount());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE template_id=:id",Map.of("id",rollback.id()),Integer.class));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.coupon_event DROP CONSTRAINT p07_b_fail");}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_event WHERE template_id=:id AND action='CLAIM'",Map.of("id",same.id()),Integer.class));
    }

    @Test void couponOwnershipAndTemplateSnapshot() throws Exception {
        UUID seller=(UUID)browseFixture("coupon-snapshot",100,1).get("owner");var api=coupons();var t=couponTemplate(seller,"원래 조건",10);UUID buyer=activeMember("coupon-snapshot-buyer@example.com"),other=activeMember("coupon-snapshot-other@example.com");
        var issued=api.claim(buyer,t.id());assertThrows(RuntimeException.class,()->api.own(other,issued.id()));assertThrows(RuntimeException.class,()->api.template(other,t.id()));
        assertTrue(api.eligible(buyer,issued.id(),t.storeId(),100,Instant.now()).eligible());assertEquals(100,api.eligible(buyer,issued.id(),t.storeId(),100,Instant.now()).discount());assertFalse(api.eligible(buyer,issued.id(),t.storeId(),99,Instant.now()).eligible());assertFalse(api.eligible(buyer,issued.id(),UUID.randomUUID(),100,Instant.now()).eligible());assertFalse(api.eligible(buyer,issued.id(),t.storeId(),100,issued.terms().endsAt()).eligible());
        api.saveTemplate(seller,t.id(),couponInput("바뀐 조건",50,10,"ACTIVE",1));assertEquals(issued,api.own(buyer,issued.id()));var next=api.claim(other,t.id());assertEquals(50,next.terms().value());assertEquals(2,next.templateVersion());
        assertThrows(RuntimeException.class,()->api.saveTemplate(seller,t.id(),couponInput("낮은 한도",50,1,"ACTIVE",2)));
        api.saveTemplate(seller,t.id(),couponInput("발급 중지",50,10,"STOPPED",2));assertEquals(issued,api.claim(buyer,t.id()));assertTrue(api.eligible(buyer,issued.id(),t.storeId(),100,Instant.now()).eligible());assertThrows(RuntimeException.class,()->api.claim(seller,t.id()));
        var ownSeller=couponTemplate(seller,"판매자도 구매자",2);assertEquals(ownSeller.id(),api.claim(seller,ownSeller.id()).templateId());
        var guest=authBrowser();assertEquals(401,accountRequest(guest,"GET","/api/account/coupons",null,null).statusCode());
        var member=authBrowser();assertEquals(200,authRequest(member,"/api/auth/login",loginForm("coupon-snapshot-buyer@example.com",SIGNUP_PASSWORD),csrfToken(member)).statusCode());
        assertEquals(403,accountRequest(member,"POST","/api/account/coupons/claim",Map.of("templateId",ownSeller.id()),null).statusCode());
        assertEquals(404,accountRequest(member,"GET","/api/account/coupons/"+next.id(),null,null).statusCode());
        var response=accountRequest(member,"GET","/api/account/coupons/"+issued.id(),null,null);assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(response.body().contains("memberId"));
    }

    @Test void couponIssuanceRechecksAuthority() throws Exception {
        for(String change:List.of("buyer","seller","time")) {
            UUID seller=(UUID)browseFixture("coupon-wait-"+change,100,1).get("owner"),buyer=activeMember("coupon-wait-"+change+"@example.com");var api=coupons();var t=couponTemplate(seller,"대기",5);
            UUID lock=change.equals("buyer")?buyer:seller;var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",lock),UUID.class);locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}
                    if(change.equals("time")){System.out.println("P07_CLOCK java="+Instant.now()+" database="+jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.time.OffsetDateTime.class));jdbc.update("UPDATE marketplace.coupon_template SET issue_end=:end WHERE id=:id",Map.of("id",t.id(),"end",java.sql.Timestamp.from(Instant.now().minusSeconds(1))));}else{jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",lock));}return true;}));
                assertTrue(locked.await(5,TimeUnit.SECONDS));var writer=pool.submit(()->assertThrows(RuntimeException.class,()->api.claim(buyer,t.id()),"wait scenario="+change));
                try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%')",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
                holder.get(5,TimeUnit.SECONDS);writer.get(5,TimeUnit.SECONDS);assertEquals(0,jdbc.queryForObject("SELECT issued_count FROM marketplace.coupon_template WHERE id=:id",Map.of("id",t.id()),Long.class));
            }
        }
    }

    @Test void promotionArchivedTargetsCanBeRetiredWithoutBypassingOwnership() throws Exception {
        var f=browseFixture("promo-retire",100,1);UUID owner=(UUID)f.get("owner");var p=(com.example.marketplace.catalog.api.Products.Product)f.get("product");var api=promotions();
        var d=api.save(owner,null,promotionInput(p.id(),"DRAFT",0));api.save(owner,d.id(),promotionInput(p.id(),"ACTIVE",0));
        jdbc.update("UPDATE marketplace.catalog_product SET state='ARCHIVED' WHERE id=:id",Map.of("id",p.id()));
        var stopped=api.save(owner,d.id(),promotionInput(p.id(),"STOPPED",1));assertEquals(2,stopped.version());assertEquals(List.of(p.id()),stopped.productIds());
        assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(p.id(),"ARCHIVED",1)));
        assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(UUID.randomUUID(),"ARCHIVED",2)));
        var other=browseFixture("promo-retire-other",100,1);assertThrows(RuntimeException.class,()->api.save((UUID)other.get("owner"),d.id(),promotionInput(p.id(),"ARCHIVED",2)));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event ADD CONSTRAINT p07_retire_fail CHECK(promotion_id<>'"+d.id()+"' OR version<3)");
        try{assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(p.id(),"ARCHIVED",2)));assertEquals(stopped,api.own(owner,d.id()));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event DROP CONSTRAINT p07_retire_fail");}
        assertEquals("ARCHIVED",api.save(owner,d.id(),promotionInput(p.id(),"ARCHIVED",2)).state());assertThrows(RuntimeException.class,()->api.published(d.id()));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM marketplace.promotion_event WHERE promotion_id=:id",Map.of("id",d.id()),Integer.class));
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var f2=browseFixture("promo-retire-race",100,1);UUID owner2=(UUID)f2.get("owner");var p2=products.own(owner2,((com.example.marketplace.catalog.api.Products.Product)f2.get("product")).id());
        var p3=products.save(owner2,null,new com.example.marketplace.catalog.api.Products.Input(p2.categoryId(),"promo-retire-mixed","다른 대상","","DRAFT",0L));
        var ids=List.of(p2.id(),p3.id());Instant start=Instant.now().minusSeconds(60),end=start.plusSeconds(3600);
        var mixed=api.save(owner2,null,new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",ids,start,end,"FIXED",10L,null,"DRAFT",0L));
        api.save(owner2,mixed.id(),new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",ids,start,end,"FIXED",10L,null,"ACTIVE",0L));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                products.save(owner2,p2.id(),new com.example.marketplace.catalog.api.Products.Input(p2.categoryId(),p2.slug(),p2.title(),p2.description(),"ARCHIVED",p2.version()));
                locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}return true;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var writer=pool.submit(()->api.save(owner2,mixed.id(),new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",ids,start,end,"FIXED",10L,null,"STOPPED",1L)));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%')",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);var retired=writer.get(5,TimeUnit.SECONDS);assertEquals("STOPPED",retired.state());assertEquals(2,retired.version());assertEquals(new java.util.HashSet<>(ids),new java.util.HashSet<>(retired.productIds()));
        }
    }

    @Test void promotionPublicationMatrixAndNumericContract() throws Exception {
        var f=browseFixture("promo-matrix",100,1);UUID owner=(UUID)f.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)f.get("product");var api=promotions();
        var store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner);var d=api.save(owner,null,promotionInput(product.id(),"DRAFT",0));var live=api.save(owner,d.id(),promotionInput(product.id(),"ACTIVE",0));
        String[] off={"UPDATE marketplace.seller_store SET visible=false WHERE id=:store","UPDATE marketplace.seller_store SET state='SUSPENDED',visible=false WHERE id=:store","UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:owner","DELETE FROM marketplace.role_assignment WHERE user_id=:owner AND role='SELLER'","UPDATE marketplace.catalog_category SET active=false WHERE id=:category","UPDATE marketplace.catalog_product SET state='STOPPED' WHERE id=:product"};
        String[] on={"UPDATE marketplace.seller_store SET visible=true WHERE id=:store","UPDATE marketplace.seller_store SET state='OPEN',visible=true WHERE id=:store","UPDATE marketplace.identity_user SET state='ACTIVE' WHERE id=:owner","INSERT INTO marketplace.role_assignment VALUES(:owner,'SELLER')","UPDATE marketplace.catalog_category SET active=true WHERE id=:category","UPDATE marketplace.catalog_product SET state='ACTIVE' WHERE id=:product"};
        var params=Map.of("store",store.id(),"owner",owner,"category",product.categoryId(),"product",product.id());
        for(int i=0;i<off.length;i++){jdbc.update(off[i],params);assertTrue(api.candidates(store.id(),List.of(product.id()),Instant.now()).isEmpty(),off[i]);jdbc.update(on[i],params);assertEquals(live.id(),api.published(live.id()).id());}
        var second=context.getBean(com.example.marketplace.catalog.api.Products.class).save(owner,null,new com.example.marketplace.catalog.api.Products.Input(product.categoryId(),"promo-matrix-second","숨김 대상","","DRAFT",0L));
        var multi=api.save(owner,live.id(),new com.example.marketplace.promotion.api.Promotions.Input("복합","PRODUCT",List.of(product.id(),second.id()),live.startsAt(),live.endsAt(),"FIXED",100L,null,"ACTIVE",1L));assertEquals(List.of(product.id()),api.published(multi.id()).productIds());
        var shipping=new com.example.marketplace.promotion.api.Promotions.Input("배송","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FREE",0L,null,"DRAFT",0L);
        var ship=api.save(owner,null,shipping);api.save(owner,ship.id(),new com.example.marketplace.promotion.api.Promotions.Input("배송","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FREE",0L,null,"ACTIVE",0L));assertEquals("SHIPPING",api.published(ship.id()).scope());assertTrue(api.candidates(store.id(),List.of(),live.endsAt()).isEmpty());
        for(Long cap:List.of(0L,1000L)){assertThrows(com.example.marketplace.promotion.api.PromotionRejected.class,()->api.save(owner,null,new com.example.marketplace.promotion.api.Promotions.Input("배송","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FREE",0L,cap,"DRAFT",0L)));}
        var browser=authBrowser();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm("p4b-browse-promo-matrix@example.com",SIGNUP_PASSWORD),csrfToken(browser)).statusCode());String csrf=csrfToken(browser);
        for(long[] pair:new long[][]{{9007199254740991L,1L},{9007199254740992L,1L},{9007199254740993L,1L},{1L,9007199254740991L},{1L,9007199254740992L},{1L,9007199254740993L}}) {
            long amount=pair[0],cap=pair[1];boolean allowed=amount<=9007199254740991L&&cap<=9007199254740991L;
            var input=new com.example.marketplace.promotion.api.Promotions.Input("경계","SHIPPING",List.of(),live.startsAt(),live.endsAt(),"FIXED",amount,cap,"DRAFT",0L);
            var response=accountRequest(browser,"POST","/api/seller/promotions",input,csrf);assertEquals(allowed?201:400,response.statusCode());
            if(response.statusCode()==201){var tree=authMapper.readTree(response.body());assertEquals(amount,tree.get("value").asLong());assertEquals(cap,tree.get("cap").asLong());}
        }
        var publicResult=accountRequest(authBrowser(),"GET","/api/events/"+live.id(),null,null);assertEquals("no-store",publicResult.headers().firstValue("Cache-Control").orElseThrow());
        var tree=authMapper.readTree(publicResult.body());assertEquals(java.util.Set.of("id","storeId","title","scope","productIds","startsAt","endsAt","kind","value","cap","state","version","storeSlug","storeName","targets"),authMapper.readValue(publicResult.body(),Map.class).keySet());assertFalse(publicResult.body().contains("actorId"));assertEquals(store.slug(),tree.get("storeSlug").asText());assertEquals(1,tree.get("targets").size());assertEquals(java.util.Set.of("id","slug","title"),authMapper.readValue(tree.get("targets").get(0).toString(),Map.class).keySet());
    }

    @Test void promotionOwnershipVersionAndAudit() throws Exception {
        var f=browseFixture("promotion-own",10001,10);UUID owner=(UUID)f.get("owner"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();
        var other=browseFixture("promotion-other",100,1);UUID foreign=(UUID)other.get("owner");var api=promotions();
        assertThrows(RuntimeException.class,()->api.save(foreign,null,promotionInput(product,"DRAFT",0)));
        var draft=api.save(owner,null,promotionInput(product,"DRAFT",0));
        assertThrows(RuntimeException.class,()->api.own(foreign,draft.id()));
        var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> change=()->{start.await();try{api.save(owner,draft.id(),promotionInput(product,"ACTIVE",0));return true;}catch(com.example.marketplace.promotion.api.PromotionRejected ex){assertEquals(409,ex.status());return false;}};
            var a=pool.submit(change);var b=pool.submit(change);start.countDown();assertEquals(1,(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0));
        }
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.promotion_event WHERE promotion_id=:id",Map.of("id",draft.id()),Integer.class));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event ADD CONSTRAINT p07_a_fail CHECK(promotion_id<>'"+draft.id()+"' OR version<2)");
        try{var changed=new com.example.marketplace.promotion.api.Promotions.Input("changed","SHIPPING",List.of(),Instant.now(),Instant.now().plusSeconds(50),"FIXED",2L,null,"STOPPED",1L);
        assertThrows(RuntimeException.class,()->api.save(owner,draft.id(),changed));assertEquals(List.of(product),api.own(owner,draft.id()).productIds());assertEquals("행사",api.own(owner,draft.id()).title());assertEquals(1000,api.own(owner,draft.id()).value());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.promotion_event WHERE promotion_id=:id",Map.of("id",draft.id()),Integer.class));assertEquals("ACTIVE",api.own(owner,draft.id()).state());assertEquals(1,api.own(owner,draft.id()).version());}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.promotion_event DROP CONSTRAINT p07_a_fail");}
        api.save(owner,draft.id(),promotionInput(product,"ARCHIVED",1));assertThrows(RuntimeException.class,()->api.save(owner,draft.id(),promotionInput(product,"ACTIVE",2)));
    }

    @Test void promotionPublicationAndTime() throws Exception {
        var f=browseFixture("promotion-public",10001,10);UUID owner=(UUID)f.get("owner"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var api=promotions();
        var draft=api.save(owner,null,promotionInput(product,"DRAFT",0));var live=api.save(owner,draft.id(),promotionInput(product,"ACTIVE",0));
        UUID store=context.getBean(com.example.marketplace.seller.api.Stores.class).own(owner).id();
        assertTrue(api.candidates(store,List.of(product),live.startsAt()).stream().anyMatch(x->x.id().equals(live.id())));
        assertTrue(api.candidates(store,List.of(product),live.endsAt()).isEmpty());
        var anonymous=authBrowser();assertEquals(200,accountRequest(anonymous,"GET","/api/events/"+live.id(),null,null).statusCode());
        var member=authBrowser();assertEquals(200,authRequest(member,"/api/auth/login",loginForm("p4b-browse-promotion-public@example.com",SIGNUP_PASSWORD),csrfToken(member)).statusCode());
        assertEquals(accountRequest(anonymous,"GET","/api/events/"+live.id(),null,null).body(),accountRequest(member,"GET","/api/events/"+live.id(),null,null).body());
        assertEquals(403,accountRequest(member,"POST","/api/seller/promotions","{}",null).statusCode());
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",Map.of("id",product));
        assertTrue(api.candidates(store,List.of(product),Instant.now()).isEmpty());assertEquals(404,accountRequest(anonymous,"GET","/api/events/"+live.id(),null,null).statusCode());
    }

    @Test void promotionAccessAfterLockWait() throws Exception {
        var f=browseFixture("promotion-lock",100,1);UUID owner=(UUID)f.get("owner"),product=((com.example.marketplace.catalog.api.Products.Product)f.get("product")).id();var api=promotions();
        var d=api.save(owner,null,promotionInput(product,"DRAFT",0));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",owner),UUID.class);locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",owner));return true;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var writer=pool.submit(()->assertThrows(RuntimeException.class,()->api.save(owner,d.id(),promotionInput(product,"ACTIVE",0))));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%identity_user%FOR UPDATE%' OR query LIKE '%identity_user%FOR NO KEY UPDATE%')",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);writer.get(5,TimeUnit.SECONDS);assertEquals("DRAFT",jdbc.queryForObject("SELECT state FROM marketplace.promotion WHERE id=:id",Map.of("id",d.id()),String.class));
        }
    }
}
