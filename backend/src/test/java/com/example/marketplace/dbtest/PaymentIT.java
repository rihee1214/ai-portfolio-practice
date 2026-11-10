package com.example.marketplace.dbtest;


import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentIT extends PostgresContractFixture {
    @Test void sandboxConcurrentConfirmationsAndReusedProviderKeyHaveOneEffect(CapturedOutput output) throws Exception {
        var f=sandboxFixture("concurrent",false);String key="fixture-e-concurrent-"+UUID.randomUUID(),otherKey="fixture-e-concurrent-other-"+UUID.randomUUID();tossFixture(f,key,"DONE");tossFixture(f,otherKey,"DONE");String csrf=csrfToken(f.browser());var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) { var results=List.of(key,otherKey).stream().map(k->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,k),csrf).statusCode();})).toList();var statuses=List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));assertThat(statuses).containsExactlyInAnyOrder(200,400); }
        paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,tossCalls(key,"POST")+tossCalls(otherKey,"POST"));String bound=tossCalls(key,"POST")==1?key:otherKey;var other=sandboxFixture("key-reuse",false);int before=tossCalls(bound,"POST");assertEquals(400,accountRequest(other.browser(),"POST","/api/payments/"+other.order().orderNumber()+"/confirm",tossConfirm(other,bound),csrfToken(other.browser())).statusCode());paymentStates(other,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",other.order().id()),Integer.class));assertEquals(before,tossCalls(bound,"POST"));assertFalse(output.getAll().contains(bound));
    }

    @Test void sandboxConfirmationWaitRechecksCurrentSession() throws Exception {
        var f=sandboxFixture("session-wait",false);String key="fixture-e-session-"+UUID.randomUUID();tossFixture(f,key,"DONE");String csrf=csrfToken(f.browser());
        try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newSingleThreadExecutor()) { connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,f.authority().member());sql.executeQuery().close(); }var response=pool.submit(()->accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,key),csrf));awaitCouponLock("identity_user");jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS).statusCode()); }
        assertEquals(0,tossCalls(key,"POST"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }

    @Test void sandboxConfirmationBindsStoredOrderAndCurrentAuthority() throws Exception {
        var f=sandboxFixture("server-binding",false);String path="/api/payments/"+f.order().orderNumber();
        assertEquals(200,accountRequest(f.browser(),"POST",path+"/sandbox",Map.of(),csrfToken(f.browser())).statusCode());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
        assertEquals(403,accountRequest(f.browser(),"POST",path+"/sandbox",Map.of(),null).statusCode());String key="fixture-e-bind-"+UUID.randomUUID();tossFixture(f,key,"DONE");var input=tossConfirm(f,key);
        assertEquals(403,accountRequest(f.browser(),"POST",path+"/confirm",input,null).statusCode());var other=paymentFixture("sandbox-other",false);assertEquals(401,accountRequest(other.browser(),"POST",path+"/confirm",input,csrfToken(other.browser())).statusCode());
        for(var forged:List.of(Map.of("paymentKey",key,"orderId",UUID.randomUUID().toString(),"amount",input.get("amount")),Map.of("paymentKey",key,"orderId",input.get("orderId"),"amount","1"),Map.of("paymentKey",key,"orderId",input.get("orderId"),"amount",input.get("amount"),"mode","DEMO"))) { assertEquals(400,accountRequest(f.browser(),"POST",path+"/confirm",forged,csrfToken(f.browser())).statusCode()); }
        assertEquals(0,tossCalls(key,"POST"));assertEquals(200,accountRequest(f.browser(),"POST",path+"/confirm",input,csrfToken(f.browser())).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(200,accountRequest(f.browser(),"POST",path+"/confirm",input,csrfToken(f.browser())).statusCode());assertEquals(1,tossCalls(key,"POST"));
        assertEquals(400,accountRequest(f.browser(),"POST",path+"/confirm",tossConfirm(f,"another-key"),csrfToken(f.browser())).statusCode());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        var demo=paymentFixture("sandbox-demo-reject",false);assertEquals(409,accountRequest(demo.browser(),"POST","/api/payments/"+demo.order().orderNumber()+"/confirm",tossConfirm(demo,key),csrfToken(demo.browser())).statusCode());
    }

    @Test void paymentWebhookRequiresProviderEvidence() throws Exception {
        var f=sandboxFixture("webhook",false);String key="fixture-e-webhook-"+UUID.randomUUID();var data=tossFixture(f,key,"IN_PROGRESS");data.put("lose",true);String path="/api/payments/"+f.order().orderNumber();assertEquals(200,accountRequest(f.browser(),"POST",path+"/confirm",tossConfirm(f,key),csrfToken(f.browser())).statusCode());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
        data.put("status","DONE");String body=authMapper.writeValueAsString(Map.of("eventType","PAYMENT_STATUS_CHANGED","createdAt","2020-01-01T00:00:00","data",data));String time=Instant.now().toString(),sig=tossSignature(body,time);var before=paymentParticipantState();int priorReceipts=jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt",Map.of(),Integer.class);
        assertEquals(403,webhook("/api/payments/webhooks/toss",body,null,null).statusCode());assertEquals(403,webhook("/api/payments/webhooks/toss",body,time,"v1:Zm9yZ2Vk").statusCode());assertEquals(403,webhook("/api/payments/webhooks/toss",body+" ",time,sig).statusCode());String old=Instant.now().minusSeconds(301).toString();assertEquals(403,webhook("/api/payments/webhooks/toss",body,old,tossSignature(body,old)).statusCode());assertEquals(before,paymentParticipantState());assertEquals(0,tossCalls(key,"GET"));assertEquals(priorReceipts,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt",Map.of(),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        assertEquals(503,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->paymentWith(Clock.systemUTC(),stage->{ }).webhook(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),time,sig,"fixture-disabled")).status());assertEquals(before,paymentParticipantState());assertEquals(0,tossCalls(key,"GET"));
        for(String invalid:List.of(body.replace("\"totalAmount\":"+f.order().quote().total(),"\"totalAmount\":184467440737095516160000"),body.replace(f.order().id().toString(),UUID.randomUUID().toString()),body.replace(key,"fixture-unbound-key"),body.replace("fixture-mid","foreign-mid"))) { assertEquals(409,webhook("/api/payments/webhooks/toss",invalid,time,tossSignature(invalid,time)).statusCode());assertEquals(0,tossCalls(key,"GET"));assertEquals(before,paymentParticipantState()); }
        for(String wrongPath:List.of("/api/payments/webhooks/other","/api/payments/webhooks/toss/extra","/api/payments/webhooks/toss/")) { assertEquals(403,webhook(wrongPath,body,time,sig).statusCode()); }
        data.put("totalAmount",1L);assertEquals(409,webhook("/api/payments/webhooks/toss",body,time,sig).statusCode());assertEquals(before,paymentParticipantState());data.put("totalAmount",f.order().quote().total());
        assertEquals(200,webhook("/api/payments/webhooks/toss",body,time,"v1:invalid,"+sig).statusCode());assertEquals(200,webhook("/api/payments/webhooks/toss",body,time,sig).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_webhook_receipt WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        String reverse=body.replace("DONE","ABORTED");assertEquals(409,webhook("/api/payments/webhooks/toss",reverse,time,tossSignature(reverse,time)).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,tossCalls(key,"POST"));
    }

    @Test void sandboxLostResponseAndLateSuccessUseSameAttempt(CapturedOutput output) throws Exception {
        var f=sandboxFixture("lost",false);String key="fixture-e-lost-"+UUID.randomUUID();var data=tossFixture(f,key,"DONE");data.put("lose",true);assertEquals(200,accountRequest(f.browser(),"POST","/api/payments/"+f.order().orderNumber()+"/confirm",tossConfirm(f,key),csrfToken(f.browser())).statusCode());assertEquals("UNKNOWN",paymentAttempt(f).get("state"));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,tossCalls(key,"POST"));assertEquals(1,tossCalls(key,"GET"));
        var late=sandboxFixture("late",false);String lateKey="fixture-e-late-"+UUID.randomUUID();var lateData=tossFixture(late,lateKey,"IN_PROGRESS");accountRequest(late.browser(),"POST","/api/payments/"+late.order().orderNumber()+"/confirm",tossConfirm(late,lateKey),csrfToken(late.browser()));var attempt=paymentAttempt(late);lateData.put("status","DONE");var timed=paymentWith(Clock.fixed(late.order().expiresAt(),ZoneOffset.UTC),stage->{ });timed.query(late.order().orderNumber(),0,0,false);assertEquals("PENDING",paymentAttempt(late).get("compensation"));paymentQuery(late,true);paymentQuery(late,true);paymentStates(late,"CANCELED","RELEASED","RELEASED");assertEquals(attempt.get("id"),paymentAttempt(late).get("id"));assertEquals(1,tossCalls(lateKey,"POST:cancel"));assertEquals(1,tossCalls(lateKey,"POST"));assertFalse(output.getAll().contains(key));assertFalse(output.getAll().contains(lateKey));assertFalse(output.getAll().contains("test_sk_fixture-integration-only"));
        var zero=sandboxFixture("zero",true);int operations=TOSS_CALLS.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();assertEquals(200,paymentPost(zero.browser(),zero.order().orderNumber(),"{}",csrfToken(zero.browser())).statusCode());paymentStates(zero,"PAID","COMMITTED","COMMITTED");assertEquals(operations,TOSS_CALLS.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",paymentAttempt(zero).get("id")),Integer.class));
    }

    @Test void paymentZeroInitialDeadlineNeverSchedulesCompensation() throws Exception {
        var f=paymentFixture("zero-initial-deadline",true);var clock=new JobClock();clock.now=Instant.now();var before=paymentParticipantState();var api=paymentWith(clock,stage->{if(stage.equals("PREPARE")) { clock.now=f.order().expiresAt(); }});
        assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.approve(f.order().orderNumber(),f.authority()));assertEquals(before,paymentParticipantState());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }

    @Test void paymentApprovalExpiryContendOnTheSameOrderLock() throws Exception {
        for(boolean approveFirst:List.of(false,true)) {
            var f=paymentFixture("order-lock-"+approveFirst,false);mockPayment(f,"QUERY_FAILURE","SUCCESS");var due=paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ });
            try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newFixedThreadPool(2)) {
                connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,f.order().id());sql.executeQuery().close(); }
                Callable<String> approval=()->{try { return payments().approve(f.order().orderNumber(),f.authority()).paymentState(); }catch(com.example.marketplace.order.api.OrderRejected denied) { return "DENIED"; }};
                Callable<String> expiry=()->{due.expire(f.order().orderNumber());return "EXPIRED";};var first=pool.submit(approveFirst?approval:expiry);
                awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%purchase_order%FOR UPDATE%'",Integer.class)>=1,"first order waiter missing");var second=pool.submit(approveFirst?expiry:approval);
                awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%purchase_order%FOR UPDATE%'",Integer.class)>=2,"both order paths must actually contend");connection.commit();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            }
            if(approveFirst) { paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("UNKNOWN",paymentAttempt(f).get("state"));assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class)); }
            else { paymentStates(f,"EXPIRED","RELEASED","RELEASED");assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class)); }
        }
    }

    @Test void paymentRejectsMislinkedOrIncompleteReservationHandoffs() throws Exception {
        for(String mode:List.of("missing","coupon","deadline","member")) {
            var f=paymentFixture("handoff-"+mode,false);var ids=orders().reservations(f.order().orderNumber());
            if(mode.equals("missing")) { jdbc.update("DELETE FROM marketplace.order_reservation WHERE reservation_id=:id",Map.of("id",ids.inventory().getFirst())); }
            if(mode.equals("deadline")) { jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=expires_at-interval '1 second' WHERE id=:id",Map.of("id",ids.inventory().getFirst())); }
            if(mode.equals("coupon")) { UUID old=f.order().quote().stores().getFirst().coupon().id();var stored=coupons().own(f.authority().member(),old);var other=couponTemplate(f.authority().member(),"다른 쿠폰",3);var issued=coupons().claim(f.authority().member(),other.id());UUID rid=jdbc.queryForObject("SELECT id FROM marketplace.coupon_reservation WHERE coupon_id=:id",Map.of("id",stored.id()),UUID.class);jdbc.update("UPDATE marketplace.coupon_reservation SET coupon_id=:coupon WHERE id=:id",Map.of("coupon",issued.id(),"id",rid)); }
            if(mode.equals("member")) { UUID other=activeMember("payment-handoff-other@example.com");var intact=paymentParticipantState();assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.coupon_reservation SET member_id=:member WHERE id=:id",Map.of("member",other,"id",ids.coupons().getFirst())));assertEquals(intact,paymentParticipantState());continue; }
            var before=paymentParticipantState();assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->payments().approve(f.order().orderNumber(),f.authority()));assertEquals(before,paymentParticipantState());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
        }
    }

    @Test void paymentReviewRequiresAuthorizedReconcile() throws Exception {
        var f=paymentFixture("ops",false);mockPayment(f,"QUERY_FAILURE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());for(int i=0;i<5;i++) { paymentQuery(f,false); }
        var before=paymentParticipantState();assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->payments().reconcile(f.order().orderNumber(),f.authority(),"provider checked"));assertEquals(before,paymentParticipantState());
        jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",f.authority().member()));assertThrows(com.example.marketplace.identity.api.AccessRejected.class,()->payments().reconcile(f.order().orderNumber(),f.authority(),"provider checked"));
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'SYSTEM')",Map.of("id",f.authority().member()));assertTrue(payments().reviewQueue(f.authority()).stream().anyMatch(v->v.orderNumber().equals(f.order().orderNumber())));
        payments().reconcile(f.order().orderNumber(),f.authority(),"provider checked");var a=paymentAttempt(f);assertEquals(1,a.get("round"));assertEquals(0,a.get("query_count"));assertEquals(false,a.get("review_required"));
        assertEquals("provider checked",jdbc.queryForObject("SELECT reason FROM marketplace.payment_event WHERE attempt_id=:id AND action='RECONCILE_REQUESTED' AND actor=:actor",Map.of("id",a.get("id"),"actor",f.authority().member()),String.class));
        jdbc.update("UPDATE marketplace.payment_mock SET approval='SUCCESS',state='SUCCEEDED',approval_effects=1 WHERE attempt_id=:id",Map.of("id",a.get("id")));paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"demo-coupon","demo-inventory","zero","sandbox","confirm"})
    void paymentPreparationRechecksExpiryAfterReservationWait(String shape) throws Exception {
        var f=shape.equals("sandbox")||shape.equals("confirm")?sandboxFixture("final-"+shape,false):paymentFixture("final-"+shape,shape.equals("zero"));
        var clock=new JobClock();clock.now=Instant.now();long expiry=clock.millis()+60000;
        jdbc.update("UPDATE marketplace.spring_session SET expiry_time=:expiry WHERE session_id=:s",Map.of("expiry",expiry,"s",f.authority().browser()));
        var sessions=new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock);
        var api=paymentAuthorityFixture(sessions,context.getBean(com.example.marketplace.guest.api.GuestOrders.class));
        String key="fixture-final-"+UUID.randomUUID();if(shape.equals("confirm")) { tossFixture(f,key,"DONE"); }
        boolean coupon=shape.equals("demo-coupon");String table=coupon?"issued_coupon":"inventory_balance",column=coupon?"id":"sku_id";
        UUID locked=coupon?f.order().quote().stores().getFirst().coupon().id():f.order().sellers().getFirst().items().getFirst().skuId();
        var before=paymentParticipantState();String providerBefore=paymentProviderState();
        try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT "+column+" FROM marketplace."+table+" WHERE "+column+"=? FOR UPDATE")) { sql.setObject(1,locked);sql.executeQuery().close(); }
            var response=pool.submit(()->{try { if(shape.equals("sandbox")) { api.sandbox(f.order().orderNumber(),f.authority()); }else if(shape.equals("confirm")) { api.confirm(f.order().orderNumber(),f.authority(),new com.example.marketplace.payment.infrastructure.PaymentCoordinator.Confirmation(key,f.order().id().toString(),Long.toString(f.order().quote().total()))); }else { api.approve(f.order().orderNumber(),f.authority()); }return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }});
            awaitCouponLock(table);clock.now=Instant.ofEpochMilli(expiry);assertTrue(clock.instant().isBefore(f.order().expiresAt()));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS),shape);
        }
        assertEquals(before,paymentParticipantState());assertEquals(providerBefore,paymentProviderState());assertEquals(0,tossCalls(key,"POST"));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"status","approve"})
    void paymentExistingGuestRechecksMaximumAfterOrderWait(String shape) throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var product=browseFixture("final-guest-"+shape,10000,10);var lines=List.of(quoteLine((UUID)product.get("sku"),1));var quote=quotes().calculate(null,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("ignored",f.phone,"수령인",f.phone,"01234","주소","");
        UUID first=guestVerified(f);access.prepare(f.browser,first,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var original=creationWith(access,stage->{ }).create(f.browser,null,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("first",lines,List.of(),quote.fingerprint(),contact,first,0));
        String token=access.login(original.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);
        Instant maximum=jdbc.queryForObject("SELECT expires_at FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)),java.sql.Timestamp.class).toInstant();
        // Genuine activity keeps idle authority valid. Create the new purchase one second before the immutable maximum.
        while(f.clock.instant().isBefore(maximum.minusSeconds(61))) {
            f.clock.advance(Math.min(1500,Duration.between(f.clock.instant(),maximum.minusSeconds(61)).getSeconds()));
            new TransactionTemplate(transactionManager).executeWithoutResult(t->assertEquals(original.guestBuyerId(),access.requireCurrent(token)));
        }
        UUID next=guestRepeat(f);var link=access.selectLink(f.browser,next,"EXISTING",token,0);
        var order=creationWith(access,stage->{ }).create(f.browser,null,token,new com.example.marketplace.order.infrastructure.OrderCreationService.Input("linked",lines,List.of(),quote.fingerprint(),contact,next,link.revision()));
        long now=System.currentTimeMillis();jdbc.update("INSERT INTO marketplace.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time) VALUES(:id,:s,:now,:now,3600,:expiry)",Map.of("id",UUID.randomUUID().toString(),"s",f.browser,"now",now,"expiry",now+3600000));
        var sessions=context.getBean(com.example.marketplace.identity.api.PurchaseSessions.class);var api=paymentAuthorityFixture(sessions,access);var authority=new com.example.marketplace.payment.api.Payments.Authority(f.browser,null,null,token);
        assertEquals(maximum.minusSeconds(1),f.clock.instant());
        assertEquals(maximum,jdbc.queryForObject("SELECT idle_expires_at FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)),java.sql.Timestamp.class).toInstant());
        var before=paymentParticipantState();String providerBefore=paymentProviderState();
        try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { sql.setObject(1,order.id());sql.executeQuery().close(); }
            var response=pool.submit(()->{try { if(shape.equals("status")) { api.get(order.orderNumber(),authority); }else { api.approve(order.orderNumber(),authority); }return 200; }catch(com.example.marketplace.order.api.OrderRejected denied) { return denied.status(); }catch(com.example.marketplace.guest.api.GuestRejected denied) { return denied.status(); }});
            awaitCouponLock("purchase_order");f.clock.now=maximum;assertTrue(Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(t->sessions.lockCurrent(f.browser,null,null))));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS),shape);
        }
        assertEquals(before,paymentParticipantState());assertEquals(providerBefore,paymentProviderState());assertEquals("PENDING_PAYMENT",orders().find(order.orderNumber()).state());
    }

    @Test void paymentHttpWaitRechecksSessionAndWorkerSurvivesSuspension() throws Exception {
        var f=paymentFixture("http-wait",false);String csrf=csrfToken(f.browser());
        try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);try(var sql=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { sql.setObject(1,f.authority().member());sql.executeQuery().close(); }
            var response=pool.submit(()->paymentPost(f.browser(),f.order().orderNumber(),"{}",csrf));awaitCouponLock("identity_user");jdbc.update("DELETE FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",f.authority().browser()));connection.commit();assertEquals(401,response.get(10,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
        var suspended=paymentFixture("suspended-after-provider",false);mockPayment(suspended,"LOST_RESPONSE","SUCCESS");payments().approve(suspended.order().orderNumber(),suspended.authority());jdbc.update("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=:id",Map.of("id",suspended.authority().member()));paymentQuery(suspended,false);paymentStates(suspended,"PAID","COMMITTED","COMMITTED");
    }

    @Test void paymentHttpRejectsOwnershipAmountModeAndMissingCsrf() throws Exception {
        var f=paymentFixture("http-denials",false);String token=csrfToken(f.browser());assertEquals(403,paymentPost(f.browser(),f.order().orderNumber(),"{}",null).statusCode());
        for(String body:List.of("{\"amount\":\"0\"}","{\"mode\":\"SANDBOX\"}","{\"reservationIds\":[]}","{\"result\":\"SUCCESS\"}")) { assertEquals(400,paymentPost(f.browser(),f.order().orderNumber(),body,token).statusCode()); }
        var other=paymentFixture("http-other",false);assertEquals(401,paymentPost(other.browser(),f.order().orderNumber(),"{}",csrfToken(other.browser())).statusCode());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));
        assertEquals(200,paymentPost(f.browser(),f.order().orderNumber(),"{}",token).statusCode());paymentStates(f,"PAID","COMMITTED","COMMITTED");assertEquals(200,paymentPost(f.browser(),f.order().orderNumber(),"{}",token).statusCode());assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
    }

    @Test void purchaseTotalsAndReservationsConverge() throws Exception {
        var f=paymentFixture("g-totals",false);var original=f.order();paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");
        assertEquals(2,original.quote().stores().size());assertEquals(55800,original.quote().total());
        var balance=new java.util.HashMap<UUID,Integer>();for(var store:original.quote().stores()) { for(var line:store.lines()) { balance.put(line.skuId(),jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",line.skuId()),Integer.class)); } }
        assertEquals(200,paymentPost(f.browser(),original.orderNumber(),"{}",csrfToken(f.browser())).statusCode());
        assertEquals(200,paymentPost(f.browser(),original.orderNumber(),"{}",csrfToken(f.browser())).statusCode());
        payments().apply(original.orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);paymentStates(f,"PAID","COMMITTED","COMMITTED");
        var detail=authMapper.readTree(accountRequest(f.browser(),"GET","/api/orders/"+original.orderNumber(),null,null).body());long parent=0;
        for(var store:detail.get("stores")) { long gross=0,promotion=0,coupon=0,net=0;for(var item:store.get("items")) { long ug=0,up=0,uc=0,un=0;assertEquals(item.get("quantity").asInt(),item.get("units").size());for(var unit:item.get("units")) { ug+=unit.get("gross").asLong();up+=unit.get("promotionDiscount").asLong();uc+=unit.get("couponDiscount").asLong();un+=unit.get("net").asLong();assertEquals(unit.get("gross").asLong()-unit.get("promotionDiscount").asLong()-unit.get("couponDiscount").asLong(),unit.get("net").asLong()); }assertEquals(ug,item.get("gross").asLong());assertEquals(up,item.get("promotionDiscount").asLong());assertEquals(uc,item.get("couponDiscount").asLong());assertEquals(un,item.get("net").asLong());gross+=ug;promotion+=up;coupon+=uc;net+=un;
                UUID sku=UUID.fromString(item.get("skuId").asText());assertEquals(balance.get(sku)-item.get("quantity").asInt(),jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Integer.class));
            }assertEquals(gross,store.get("subtotal").asLong());assertEquals(promotion,store.get("productDiscount").asLong());assertEquals(coupon,store.get("couponDiscount").asLong());assertEquals(net+store.get("shippingBase").asLong()-store.get("shippingDiscount").asLong(),store.get("total").asLong());parent+=store.get("total").asLong(); }
        assertEquals(55800,parent);assertEquals(parent,detail.get("total").asLong());assertEquals(parent,jdbc.queryForObject("SELECT amount FROM marketplace.purchase_order WHERE id=:id",Map.of("id",original.id()),Long.class));
        var attempt=paymentAttempt(f);assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT approval_effects FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",attempt.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",attempt.get("id")),Integer.class));
        shipmentJob(settlementJob(f),Instant.now());var rows=shipments().buyer(original.orderNumber(),shipmentAuthority(f));assertEquals(2,rows.size());for(var seller:original.sellers()) { var row=rows.stream().filter(r->r.sellerOrderId().equals(seller.id())).findFirst().orElseThrow();assertEquals("PREPARING",row.state());assertEquals(seller.items().stream().map(i->new com.example.marketplace.fulfillment.api.Shipments.Item(i.id(),i.quantity())).toList(),row.items());assertEquals(1,row.events().size()); }
        shipments().prepare(original.orderNumber());assertEquals(rows,shipments().buyer(original.orderNumber(),shipmentAuthority(f)));
    }

    @Test void zeroPaymentCommitsAllOnce() throws Exception {
        var f=paymentFixture("zero",true);assertEquals("PAID",payments().approve(f.order().orderNumber(),f.authority()).orderState());payments().approve(f.order().orderNumber(),f.authority());payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);
        paymentStates(f,"PAID","COMMITTED","COMMITTED");var a=paymentAttempt(f);assertEquals("SUCCEEDED",a.get("state"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='SUCCEEDED'",Map.of("id",a.get("id")),Integer.class));
    }

    @Test void paymentApprovalAndExpiryHaveOneOutcome() throws Exception {
        var f=paymentFixture("race",false);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var api=paymentWith(Clock.systemUTC(),stage->{if(stage.equals("PREPARED")) { entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS)); }});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approval=pool.submit(()->api.approve(f.order().orderNumber(),f.authority()));assertTrue(entered.await(10,TimeUnit.SECONDS));
            var due=paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ });var expiry=pool.submit(()->due.expire(f.order().orderNumber()));expiry.get(10,TimeUnit.SECONDS);paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("PROCESSING",paymentAttempt(f).get("state"));
            payments().approve(f.order().orderNumber(),f.authority());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_attempt WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class));release.countDown();assertEquals("PAID",approval.get(10,TimeUnit.SECONDS).orderState());paymentStates(f,"PAID","COMMITTED","COMMITTED");
        }finally { release.countDown(); }
        var expired=paymentFixture("expiry-first",false);paymentWith(Clock.fixed(expired.order().expiresAt(),ZoneOffset.UTC),stage->{ }).expire(expired.order().orderNumber());paymentStates(expired,"EXPIRED","RELEASED","RELEASED");assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->payments().approve(expired.order().orderNumber(),expired.authority()));
    }

    @Test void paymentUnknownAndLateSuccessConverge() throws Exception {
        var f=paymentFixture("lost",false);mockPayment(f,"LOST_RESPONSE","SUCCESS");assertEquals("UNKNOWN",payments().approve(f.order().orderNumber(),f.authority()).paymentState());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.FAILED);paymentStates(f,"PAID","COMMITTED","COMMITTED");
        for(String cancel:List.of("SUCCESS","LOST_RESPONSE","REJECT","QUERY_FAILURE")) {
            var late=paymentFixture("late-"+cancel,false);mockPayment(late,"DELAYED_SUCCESS",cancel);payments().approve(late.order().orderNumber(),late.authority());var clock=new JobClock();clock.now=late.order().expiresAt();var timed=paymentWith(clock,stage->{ });
            // Provider success after the actual order deadline: no reservation deadline is extended or bypassed.
            var a=paymentAttempt(late);var provider=context.getBean(com.example.marketplace.payment.api.PaymentProvider.class);provider.query(new com.example.marketplace.payment.api.PaymentProvider.Request((UUID)a.get("id"),late.order().id(),late.order().quote().total(),"DEMO"));
            timed.apply(late.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);assertEquals("PENDING",paymentAttempt(late).get("compensation"));paymentStates(late,"PENDING_PAYMENT","RESERVED","RESERVED");
            paymentQuery(late,true);if(cancel.equals("LOST_RESPONSE")) { paymentQuery(late,true); }
            if(cancel.equals("SUCCESS")||cancel.equals("LOST_RESPONSE")) { paymentStates(late,"CANCELED","RELEASED","RELEASED");assertEquals("CANCELED",paymentAttempt(late).get("compensation")); }
            else if(cancel.equals("REJECT")) { assertEquals(true,paymentAttempt(late).get("review_required"));paymentStates(late,"PENDING_PAYMENT","RESERVED","RESERVED"); }
            else { for(int i=1;i<5;i++) { paymentQuery(late,true); }assertEquals(true,paymentAttempt(late).get("review_required"));paymentStates(late,"PENDING_PAYMENT","RESERVED","RESERVED"); }
            timed.apply(late.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_event WHERE attempt_id=:id AND action='COMPENSATION_PENDING'",Map.of("id",a.get("id")),Integer.class));
        }
        var released=paymentFixture("released-late",false);mockPayment(released,"REJECT","SUCCESS");payments().approve(released.order().orderNumber(),released.authority());paymentStates(released,"CANCELED","RELEASED","RELEASED");var a=paymentAttempt(released);jdbc.update("UPDATE marketplace.payment_mock SET state='SUCCEEDED',approval_effects=1 WHERE attempt_id=:id",Map.of("id",a.get("id")));payments().apply(released.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);paymentQuery(released,true);paymentStates(released,"CANCELED","RELEASED","RELEASED");assertEquals(1,jdbc.queryForObject("SELECT cancel_effects FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",a.get("id")),Integer.class));
    }

    @Test void paymentResultRollbackAndDuplicateEvents() throws Exception {
        for(boolean checked:List.of(false,true)) { for(String point:List.of("COUPON","INVENTORY","ORDER","AUDIT","OUTBOX")) {
            var f=paymentFixture("rollback-"+point+checked,false);mockPayment(f,"LOST_RESPONSE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());var before=paymentParticipantState();var api=paymentWith(Clock.systemUTC(),stage->{if(stage.equals(point)) { if(checked) { throw new java.io.IOException("checked"); }throw new IllegalStateException("runtime"); }});
            assertThrows(IllegalStateException.class,()->api.apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED));assertEquals(before,paymentParticipantState(),point+checked);paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");paymentQuery(f,false);paymentStates(f,"PAID","COMMITTED","COMMITTED");payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.UNKNOWN);paymentStates(f,"PAID","COMMITTED","COMMITTED");
        } }
        var f=paymentFixture("boundary-after-coupon",false);mockPayment(f,"LOST_RESPONSE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());UUID stock=orders().reservations(f.order().orderNumber()).inventory().getLast();
        // The real inventory clock sees an expired row; coupon commit occurred first inside the result transaction.
        jdbc.update("UPDATE marketplace.inventory_reservation SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id",Map.of("id",stock));payments().apply(f.order().orderNumber(),com.example.marketplace.payment.api.PaymentProvider.Result.SUCCEEDED);paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals("PENDING",paymentAttempt(f).get("compensation"));paymentQuery(f,true);paymentStates(f,"CANCELED","RELEASED","RELEASED");
    }

    @Test void paymentUnknownHoldsReservationsAndExhaustedJobsAreVisible() throws Exception {
        for(String mode:List.of("queries","time","delivery")) {
            var f=paymentFixture("review-"+mode,false);mockPayment(f,"QUERY_FAILURE","SUCCESS");payments().approve(f.order().orderNumber(),f.authority());
            if(mode.equals("queries")) { for(int i=0;i<5;i++) { paymentQuery(f,false); } }
            else if(mode.equals("time")) { var a=paymentAttempt(f);paymentWith(Clock.fixed(((java.sql.Timestamp)a.get("round_started_at")).toInstant().plusSeconds(900),ZoneOffset.UTC),stage->{ }).get(f.order().orderNumber(),f.authority()); }
            else { var a=paymentAttempt(f);UUID id=(UUID)a.get("next_job");var clock=new JobClock();var actualJobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(source,id),transactionManager,clock);var original=actualJobs.find(id).orElseThrow();clock.now=original.nextRunAt();for(int attempt=1;attempt<=5;attempt++){var claimed=actualJobs.claim(java.util.Set.of("PAYMENT_QUERY")).orElseThrow();assertEquals(id,claimed.id());assertEquals(attempt,claimed.attempts());if(attempt<5){assertTrue(actualJobs.fail(id,claimed.ownerToken(),false,"ACTUAL_TEST_DELIVERY_FAILURE"));clock.now=actualJobs.find(id).orElseThrow().nextRunAt();}else{clock.now=claimed.leaseUntil();}}assertTrue(actualJobs.claim(java.util.Set.of("PAYMENT_QUERY")).isEmpty());assertEquals("FAILED",actualJobs.find(id).orElseThrow().status());assertEquals("LEASE_EXPIRED",actualJobs.find(id).orElseThrow().lastError());assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM marketplace.job_claim_fact WHERE job_id=:id",Map.of("id",id),Integer.class));payments().get(f.order().orderNumber(),f.authority()); }
            assertEquals(true,paymentAttempt(f).get("review_required"));payments().approve(f.order().orderNumber(),f.authority());paymentWith(Clock.fixed(f.order().expiresAt(),ZoneOffset.UTC),stage->{ }).expire(f.order().orderNumber());paymentStates(f,"PENDING_PAYMENT","RESERVED","RESERVED");assertEquals(1,jdbc.queryForObject("SELECT approval_calls FROM marketplace.payment_mock WHERE attempt_id=:id",Map.of("id",paymentAttempt(f).get("id")),Integer.class));
        }
    }

    @Test void paymentHttpOwnershipAndTampering() throws Exception {
        var d=orderDraft("d-http");var saved=creation().create(d.purchaseSessionId().toString(),d.memberId(),null,creationInput(d));var browser=authBrowser();
        String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        var result=browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/payments/"+saved.orderNumber())).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode());assertTrue(result.body().contains("PENDING_PAYMENT"));
    }
}
