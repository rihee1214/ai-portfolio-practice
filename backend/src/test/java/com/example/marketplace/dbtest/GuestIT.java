package com.example.marketplace.dbtest;


import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class GuestIT extends PostgresContractFixture {
    @Test void guestRepurchaseRequiresBothCredentials() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);
        String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"EXISTING",null));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink("wrong",next,"EXISTING",session));
        assertEquals("EXISTING",a.selectLink(f.browser,next,"EXISTING",session).mode());
        assertTrue(a.linkStatus(f.browser,next,session).ready());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,null,"E-NO-"+f.browser));
        assertEquals(buyer,a.attachOrder(f.browser,next,session,"E-YES-"+f.browser));
        assertEquals(2,a.orders(session).size());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,session,"E-DUP-"+f.browser));
    }

    @Test void guestNewPurchaseNeverMergesByPhone() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);
        UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",session);a.selectLink(f.browser,next,"NEW",session);
        assertFalse(a.linkStatus(f.browser,next,session).ready());
        a.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        UUID created=a.attachOrder(f.browser,next,session,"E-NEW-"+f.browser);assertFalse(buyer.equals(created));assertEquals(1,a.orders(session).size());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.order(session,"E-NEW-"+f.browser));
    }

    @Test void guestRepurchaseCannotOverwriteCredentials() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);recovery(f).initialCode(buyer);
        String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);var before=jdbc.queryForMap("SELECT * FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer));UUID next=guestRepeat(f);
        a.selectLink(f.browser,next,"EXISTING",session);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword("other password","other password")));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.createBuyer(f.browser,next));
        a.attachOrder(f.browser,next,session,"E-KEEP-"+f.browser);assertEquals(before,jdbc.queryForMap("SELECT * FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer)));
        var other=new GuestFixture();var otherAccess=guestAccess(other);UUID different=guestVerified(other);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->otherAccess.selectLink(other.browser,different,"EXISTING",session));
        assertEquals("NEW",otherAccess.linkStatus(other.browser,different,null).mode());
    }

    @Test void guestLinkPreparationIsBoundToCurrentAttempt() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;guestBuyer(f,a,old);String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);
        a.selectLink(f.browser,next,"EXISTING",session);a.logout(session);
        assertFalse(a.linkStatus(f.browser,next,session).ready());assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,session,"E-BAD-"+f.browser));
        String replacement=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);
        assertFalse(a.linkStatus(f.browser,next,replacement).ready());a.selectLink(f.browser,next,"EXISTING",replacement);
        recovery(f).manage(replacement,GUEST_PASSWORD,null,f.source);assertTrue(a.linkStatus(f.browser,next,replacement).ready());
        recovery(f).manage(replacement,GUEST_PASSWORD,new com.example.marketplace.guest.api.GuestPassword("changed password","changed password"),f.source);
        assertFalse(a.linkStatus(f.browser,next,replacement).ready());
        f.service.create(f.browser);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"NEW",null));
    }

    @Test void guestRepurchaseConsumptionIsAtomic() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String session=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",session);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->a.attachOrder(f.browser,next,session,old));
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());assertEquals(1,a.orders(session).size());
        assertThat(race(()->{try{a.attachOrder(f.browser,next,session,"E-RACE-"+UUID.randomUUID());return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertEquals(2,a.orders(session).size());assertEquals(buyer,jdbc.queryForObject("SELECT guest_buyer_id FROM marketplace.guest_order_reference WHERE order_number=:o",Map.of("o",old),UUID.class));
    }

    @Test void guestLinkRevisionAndSlowPasswordCannotRestoreOldChoice() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;guestBuyer(f,a,old);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);
        assertEquals(1,a.selectLink(f.browser,next,"EXISTING",token,0).revision());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"NEW",token,0));
        a.selectLink(f.browser,next,"NEW",token,1);
        var real=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var slow=new com.example.marketplace.identity.api.PasswordHashes(){public boolean matches(String p,String h){return real.matches(p,h);}public String encode(String p){if(!p.equals("non-account-dummy-password")){a.selectLink(f.browser,next,"EXISTING",token);a.selectLink(f.browser,next,"NEW",token);}return real.encode(p);}};
        var racing=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,slow,f.clock,true);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->racing.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD)));
        assertFalse(a.linkStatus(f.browser,next,token).ready());assertEquals(4,a.linkStatus(f.browser,next,token).revision());
    }

    @Test void guestRepurchaseRechecksAccessAfterLockWait() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        jdbc.update("UPDATE marketplace.guest_access_session SET expires_at=:e,idle_expires_at=:e WHERE token_hash=:t",Map.of("e",java.sql.Timestamp.from(f.clock.instant().plusSeconds(2)),"t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE id=:b FOR UPDATE",Map.of("b",buyer),UUID.class);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var consume=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,token,"E-EXPIRED-"+f.browser)));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);f.clock.advance(2);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertEquals(401,consume.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE order_number=:o",Map.of("o","E-EXPIRED-"+f.browser),Integer.class));
    }

    @Test void guestRepurchaseRechecksPurchaseAfterLockWait() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        jdbc.update("UPDATE marketplace.guest_attempt SET expires_at=:e WHERE id=:id",Map.of("e",java.sql.Timestamp.from(f.clock.instant().plusSeconds(2)),"id",next));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE id=:b FOR UPDATE",Map.of("b",buyer),UUID.class);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var consume=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,token,"E-EXPIRED-"+f.browser)));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<end){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);f.clock.advance(2);}finally{release.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertEquals(409,consume.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals("EXPIRED",f.service.status(f.browser,f.token).state());assertEquals(null,jdbc.queryForObject("SELECT consumed_at FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",next),java.sql.Timestamp.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE order_number=:o",Map.of("o","E-EXPIRED-"+f.browser),Integer.class));
    }

    @Test void guestRepurchaseRacesPasswordChangeWithoutDeadlock() throws Exception {
        var f=new GuestFixture();var a=guestAccess(f);var r=recovery(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);r.initialCode(buyer);String token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var link=executor.submit(()->{start.await();try{a.attachOrder(f.browser,next,token,"E-R-"+f.browser);return true;}catch(com.example.marketplace.guest.api.GuestRejected e){return false;}});
            var change=executor.submit(()->{start.await();return r.manage(token,GUEST_PASSWORD,new com.example.marketplace.guest.api.GuestPassword("new valid password","new valid password"),f.source);});start.countDown();boolean linked=link.get(10,TimeUnit.SECONDS);assertEquals(43,change.get(10,TimeUnit.SECONDS).length());
            assertEquals(linked?2:1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
        }
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.orders(token));
    }

    @Test void guestLinkHttpCsrfAndDisabledBoundaries() throws Exception {
        var client=authBrowser();String csrf=csrfToken(client);var bad=Map.of("attemptId",UUID.randomUUID().toString(),"mode","EXISTING","revision",0);
        assertEquals(403,accountRequest(client,"POST","/api/guest/checkout/link",bad,null).statusCode());
        var rejected=accountRequest(client,"POST","/api/guest/checkout/link",bad,csrf);assertEquals(409,rejected.statusCode());assertEquals("no-store",rejected.headers().firstValue("Cache-Control").orElseThrow());
        var f=new GuestFixture();var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,new com.example.marketplace.identity.infrastructure.SharedPasswordHashes(),f.clock,false);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.selectLink(f.browser,UUID.randomUUID(),"NEW",null)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.linkStatus(f.browser,UUID.randomUUID(),null)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.attachOrder(f.browser,UUID.randomUUID(),null,"NO")).status());
    }

    @Test void guestNewLinkFailureRollsBackBuyerAndConsumption() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;guestBuyer(f,a,old);UUID next=guestRepeat(f);a.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        int count=jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->a.attachOrder(f.browser,next,null,old));
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class));assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        a.attachOrder(f.browser,next,null,"E-NEW-"+f.browser);assertEquals(count+1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,null,"E-NEW2-"+f.browser));assertEquals(count+1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer",Map.of(),Integer.class));
    }

    @Test void guestLinkRejectsOtherBuyerAndRecoveryProofs() {
        var f=new GuestFixture();var a=guestAccess(f);String old="E-"+f.browser;UUID buyer=guestBuyer(f,a,old);String code=recovery(f).initialCode(buyer),token=a.login(old,f.phone,GUEST_PASSWORD,f.source,null);UUID next=guestRepeat(f);a.selectLink(f.browser,next,"EXISTING",token);
        UUID other=UUID.randomUUID();jdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) SELECT :b,buyer_name,verified_phone,password_hash,created_at FROM marketplace.guest_buyer WHERE id=:old",Map.of("b",other,"old",buyer));String otherOrder="E-OTHER-"+f.browser;a.registerOrder(other,otherOrder);
        String otherToken=a.login(otherOrder,f.phone,GUEST_PASSWORD,f.source,token);
        assertFalse(a.linkStatus(f.browser,next,otherToken).ready());assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.attachOrder(f.browser,next,otherToken,"E-NO-"+f.browser));
        var r=recovery(f);UUID recoveryId=r.start(f.browser,old,f.phone,code,f.source).attemptId();r.exchange(f.browser,recoveryId,"123456");String grant=r.verify(f.browser,recoveryId,"valid-proof");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,recoveryId,"EXISTING",otherToken));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.selectLink(f.browser,next,"EXISTING",grant));
        a.selectLink(f.browser,next,"EXISTING",otherToken);assertEquals(other,a.attachOrder(f.browser,next,otherToken,"E-OTHER-NEW-"+f.browser));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
    }

    @Test void guestRecoveryInitialLimitAndCredentialRacesAreSerialized() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        var code=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThat(race(()->{try{code.set(r.initialCode(buyer));return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        for(int i=0;i<4;i++){assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,"wrong",UUID.randomUUID().toString()));}
        assertThat(race(()->{try{r.start(UUID.randomUUID().toString(),order,f.phone,code.get(),UUID.randomUUID().toString());return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return ""+e.status();}})).containsExactlyInAnyOrder("ok","429");
        var second=new GuestFixture();var a=guestAccess(second);var recovery=recovery(second);String o="D-"+second.browser;UUID b=guestBuyer(second,a,o);String c=recovery.initialCode(b),grant=resetGrant(second,recovery,o,c),session=a.login(o,second.phone,GUEST_PASSWORD,second.source,null);
        var password=new com.example.marketplace.guest.api.GuestPassword("racing password D","racing password D");var counter=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(race(()->{try{if(counter.getAndIncrement()==0){recovery.reset(second.browser,grant,password);}else{recovery.manage(session,GUEST_PASSWORD,password,second.source);}return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertEquals(2L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",b),Long.class));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->a.orders(session));
    }

    @Test void guestRecoveryCrossBuyerLoginAndVersionChangesCannotRestoreRevokedState() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer),grant=resetGrant(f,r,order,code),old=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var other=new GuestFixture();String otherOrder="D-"+other.browser;guestBuyer(other,guestAccess(other),otherOrder);
        var password=new com.example.marketplace.guest.api.GuestPassword("cross buyer password","cross buyer password");var counter=new java.util.concurrent.atomic.AtomicInteger();var rotated=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThat(race(()->{if(counter.getAndIncrement()==0){r.reset(f.browser,grant,password);}else{rotated.set(access.login(otherOrder,other.phone,GUEST_PASSWORD,f.source,old));}return "ok";})).containsOnly("ok");
        assertEquals(List.of(new com.example.marketplace.guest.infrastructure.JdbcGuestAccess.Order(otherOrder)),access.orders(rotated.get()));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(old));
        String current=access.login(order,f.phone,password.value(),f.source,null);var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var mutation=new com.example.marketplace.identity.api.PasswordHashes(){public String encode(String value){return actual.encode(value);}public boolean matches(String value,String hash){boolean result=actual.matches(value,hash);jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:b",Map.of("b",buyer));return result;}};
        var service=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,mutation,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->service.manage(current,password.value(),new com.example.marketplace.guest.api.GuestPassword("must not persist password","must not persist password"),f.source));
        assertTrue(actual.matches(password.value(),jdbc.queryForObject("SELECT password_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class)));
    }

    @Test void guestRecoveryFinalWriteRollbackAndDifferentGrantsHaveOneWinner() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        String first=resetGrant(f,r,order,code);f.clock.advance(60);String second=resetGrant(f,r,order,code);String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        UUID id=jdbc.queryForObject("SELECT id FROM marketplace.guest_recovery_attempt WHERE grant_hash=:g",Map.of("g",com.example.marketplace.guest.api.RecoverySecrets.hash(first)),UUID.class);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_recovery_attempt ADD CONSTRAINT recovery_final_failure CHECK(id <> '"+id+"'::uuid OR state <> 'CONSUMED')");
        var password=new com.example.marketplace.guest.api.GuestPassword("final transaction password","final transaction password");
        assertThrows(DataIntegrityViolationException.class,()->r.reset(f.browser,first,password));
        assertEquals(1,access.orders(session).size());assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
        assertTrue(com.example.marketplace.guest.api.RecoverySecrets.matches(code,jdbc.queryForObject("SELECT recovery_code_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class)));
        assertEquals("READY",jdbc.queryForObject("SELECT state FROM marketplace.guest_recovery_attempt WHERE id=:id",Map.of("id",id),String.class));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_recovery_attempt DROP CONSTRAINT recovery_final_failure");
        var counter=new java.util.concurrent.atomic.AtomicInteger();assertThat(race(()->{try{r.reset(f.browser,counter.getAndIncrement()==0?first:second,password);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source));
    }

    @Test void guestRecoveryRechecksGrantAfterBuyerLockAndHashSideEffects() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer),grant=resetGrant(f,r,order,code);
        var password=new com.example.marketplace.guest.api.GuestPassword("lock password D","lock password D");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE id=:id FOR UPDATE",Map.of("id",buyer),UUID.class);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var reset=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(f.browser,grant,password)));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);f.clock.advance(300);}finally{release.countDown();}holder.get(5,TimeUnit.SECONDS);reset.get(5,TimeUnit.SECONDS);
        }
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var revoke=new com.example.marketplace.identity.api.PasswordHashes(){public String encode(String v){return actual.encode(v);}public boolean matches(String v,String h){boolean match=actual.matches(v,h);access.logout(session);return match;}};
        var during=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,revoke,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->during.manage(session,GUEST_PASSWORD,password,f.source));
        String fresh=access.login(order,f.phone,GUEST_PASSWORD,f.source,null),nextGrant=resetGrant(f,r,order,code);
        var rotate=new com.example.marketplace.identity.api.PasswordHashes(){public String encode(String v){r.manage(fresh,GUEST_PASSWORD,null,f.source);return actual.encode(v);}public boolean matches(String v,String h){return actual.matches(v,h);}};
        var changing=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,rotate,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->changing.reset(f.browser,nextGrant,password));
        assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
    }

    @Test void guestRecoveryLostGrantRestartsWithoutReusingPurchaseProof() {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        UUID purchase=jdbc.queryForObject("SELECT id FROM marketplace.guest_attempt WHERE phone=:p",Map.of("p",f.input.phone()),UUID.class);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,purchase,"123456"));
        String lost=resetGrant(f,r,order,code);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());
        f.clock.advance(59);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());f.clock.advance(1);
        String fresh=resetGrant(f,r,order,code);f.clock.advance(299);
        assertEquals(43,r.reset(f.browser,fresh,new com.example.marketplace.guest.api.GuestPassword("restored password D","restored password D")).length());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(f.browser,lost,new com.example.marketplace.guest.api.GuestPassword("old grant password","old grant password")));
    }

    @Test void guestRecoveryHttpRejectsCsrfAndReturnsUniformPrivateErrors() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        var client=authBrowser();String csrf=csrfToken(client);
        for(String path:List.of("/api/guest/access/password","/api/guest/access/recovery-code","/api/guest/recovery/start","/api/guest/recovery/reset")){
            assertEquals(403,accountRequest(client,"POST",path,Map.of(),null).statusCode());
        }
        for(var input:List.of(Map.of("orderNumber","missing","phone",f.phone,"recoveryCode",code),Map.of("orderNumber",order,"phone","01000000000","recoveryCode",code),Map.of("orderNumber",order,"phone",f.phone,"recoveryCode","wrong"))){
            var response=accountRequest(client,"POST","/api/guest/recovery/start",input,csrf);assertEquals(401,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertEquals("AUTHENTICATION_REQUIRED",authMapper.readTree(response.body()).get("code").asText());assertFalse(response.body().contains(code));
        }
        assertEquals(401,accountRequest(client,"POST","/api/guest/access/recovery-code",Map.of("currentPassword",GUEST_PASSWORD),csrf).statusCode());
        accountRequest(client,"POST","/api/guest/access/login",Map.of("orderNumber",order,"phone",f.phone,"password",GUEST_PASSWORD),csrf);
        var result=accountRequest(client,"POST","/api/guest/access/recovery-code",Map.of("currentPassword",GUEST_PASSWORD),csrf);assertEquals(200,result.statusCode());assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        String rotated=authMapper.readTree(result.body()).get("recoveryCode").asText();assertFalse(rotated.equals(code));var listing=accountRequest(client,"GET","/api/guest/access/references",null,null);assertFalse(listing.body().contains(rotated));
    }

    @Test void guestRecoveryLimitsSurviveFailuresAndExpireAtWindowBoundary() {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        for(int i=0;i<5;i++){assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(UUID.randomUUID().toString(),order,f.phone,"wrong",UUID.randomUUID().toString())).status());}
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());f.clock.advance(899);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());f.clock.advance(1);
        UUID id=r.start(f.browser,order,f.phone,code,f.source).attemptId();
        for(int i=0;i<5;i++){assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,id,"000000"));}
        assertEquals(5,jdbc.queryForObject("SELECT failures FROM marketplace.guest_recovery_attempt WHERE id=:id",Map.of("id",id),Integer.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,id,"123456"));
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source)).status());
        f.clock.advance(60);r.start(f.browser,order,f.phone,code,f.source);f.clock.advance(60);r.start(f.browser,order,f.phone,code,f.source);f.clock.advance(60);
        String alternate=order+"-x";access.registerOrder(buyer,alternate);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,alternate,f.phone,code,f.source)).status());
        String source=UUID.randomUUID().toString();for(int i=0;i<20;i++){assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,UUID.randomUUID().toString(),f.phone,"wrong",source)).status());}
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,"missing",f.phone,"wrong",source)).status());f.clock.advance(899);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,"missing",f.phone,"wrong",source)).status());f.clock.advance(1);
        assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,"missing",f.phone,"wrong",source)).status());
    }

    @Test void guestRecoveryRechecksExternalWorkExpiryVersionAndDisabledMode() {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        f.provider.issueHook=()->f.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.start(f.browser,order,f.phone,code,f.source));f.provider.issueHook=()->{};
        UUID exchanged=r.start(f.browser,order,f.phone,code,f.source).attemptId();f.provider.exchangeHook=()->f.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(f.browser,exchanged,"123456"));f.provider.exchangeHook=()->{};
        UUID verified=r.start(f.browser,order,f.phone,code,f.source).attemptId();r.exchange(f.browser,verified,"123456");f.provider.hook=()->f.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.verify(f.browser,verified,"valid-proof"));f.provider.hook=()->{};f.clock.advance(900);
        String grant=resetGrant(f,r,order,code);var password=new com.example.marketplace.guest.api.GuestPassword("after expiry password","after expiry password");
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();var moving=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String value){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());f.clock.advance(300);return actual.encode(value);}
            public boolean matches(String value,String hash){return actual.matches(value,hash);}
        };
        var late=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,moving,f.provider,f.clock);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->late.reset(f.browser,grant,password));
        assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
        String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        f.clock.advance(1500);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->late.manage(session,GUEST_PASSWORD,password,f.source));
        var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestRecovery(jdbc,transactionManager,actual,null,f.clock);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.initialCode(buyer)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.start(f.browser,order,f.phone,code,f.source)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.manage(session,GUEST_PASSWORD,password,f.source)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.reset(f.browser,grant,password)).status());
    }

    @Test void guestRecoveryLateFailureRollsBackAndSerializesWithLookup() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var r=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=r.initialCode(buyer);
        String grant=resetGrant(f,r,order,code),session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);var password=new com.example.marketplace.guest.api.GuestPassword("transaction password D","transaction password D");
        String tokenHash=com.example.marketplace.guest.api.RecoverySecrets.hash(session);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_access_session ADD CONSTRAINT recovery_late_failure CHECK(token_hash <> '"+tokenHash+"' OR revoked_at IS NULL)");
        assertThrows(DataIntegrityViolationException.class,()->r.reset(f.browser,grant,password));
        assertEquals(1L,jdbc.queryForObject("SELECT password_version FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),Long.class));
        assertTrue(com.example.marketplace.guest.api.RecoverySecrets.matches(code,jdbc.queryForObject("SELECT recovery_code_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class)));
        assertEquals(1,access.orders(session).size());
        // Remove only this test's constraint in this newly isolated DB, then retry the still-unconsumed grant.
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_access_session DROP CONSTRAINT recovery_late_failure");
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var reader=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{access.orders(session);read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));assertTrue(read.await(5,TimeUnit.SECONDS));
            var reset=executor.submit(()->r.reset(f.browser,grant,password));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_buyer%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}assertTrue(waiting);}finally{release.countDown();}
            reader.get(5,TimeUnit.SECONDS);assertEquals(43,reset.get(5,TimeUnit.SECONDS).length());
        }
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(session));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(f.browser,grant,password));
    }

    @Test void guestRecoveryCodesChangePasswordsAndRevokeAllSessions() {
        var f=new GuestFixture();var access=guestAccess(f);var recovery=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        String code=recovery.initialCode(buyer);assertEquals(43,code.length());assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.initialCode(buyer));
        assertFalse(jdbc.queryForObject("SELECT recovery_code_hash FROM marketplace.guest_buyer WHERE id=:b",Map.of("b",buyer),String.class).contains(code));
        String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null),other=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.manage(session,"wrong",null,f.source));
        String rotated=recovery.manage(session,GUEST_PASSWORD,null,f.source);assertFalse(rotated.equals(code));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.start(f.browser,order,f.phone,code,f.source));
        String next="new guest password D";String changed=recovery.manage(session,GUEST_PASSWORD,new com.example.marketplace.guest.api.GuestPassword(next,next),f.source);
        assertFalse(changed.equals(rotated));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(session));assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(other));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE guest_buyer_id=:b AND revoked_at IS NOT NULL",Map.of("b",buyer),Integer.class));
        assertEquals(1,access.orders(access.login(order,f.phone,next,f.source,null)).size());
    }

    @Test void guestRecoveryRequiresEveryFactorAndConsumesGrantOnce() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);var recovery=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=recovery.initialCode(buyer);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.start(f.browser,order,"01000000000",code,f.source));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.start(f.browser,"missing",f.phone,code,f.source));
        UUID id=recovery.start(f.browser,order,f.phone,code,f.source).attemptId();
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.exchange("other",id,"123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.verify(f.browser,id,"valid-proof"));
        recovery.exchange(f.browser,id,"123456");String grant=recovery.verify(f.browser,id,"valid-proof");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.verify(f.browser,id,"valid-proof"));
        String next="reset guest password D";var password=new com.example.marketplace.guest.api.GuestPassword(next,next);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.reset("other",grant,password));
        String session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        assertThat(race(()->{try{recovery.reset(f.browser,grant,password);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected e){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(session));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,null));
        assertEquals(1,access.orders(access.login(order,f.phone,next,f.source,null)).size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_recovery_attempt WHERE grant_hash=:g OR recovery_hash=:g",Map.of("g",grant),Integer.class));
    }

    @Test void guestRecoveryResetRollbackExpiryAndRotationKeepBoundaries() {
        var f=new GuestFixture();var access=guestAccess(f);var recovery=recovery(f);String order="D-"+f.browser;UUID buyer=guestBuyer(f,access,order);String code=recovery.initialCode(buyer);
        String grant=resetGrant(f,recovery,order,code),session=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);var password=new com.example.marketplace.guest.api.GuestPassword("next password D","next password D");
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_buyer ADD CONSTRAINT recovery_rollback CHECK(id <> '"+buyer+"'::uuid OR password_version=1)");
        assertThrows(DataIntegrityViolationException.class,()->recovery.reset(f.browser,grant,password));assertEquals(1,access.orders(session).size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_recovery_attempt WHERE buyer_id=:b AND state='CONSUMED'",Map.of("b",buyer),Integer.class));
        f.clock.advance(300);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->recovery.reset(f.browser,grant,password));
        var second=new GuestFixture();var secondAccess=guestAccess(second);var r=recovery(second);String secondOrder="D-"+second.browser;UUID secondBuyer=guestBuyer(second,secondAccess,secondOrder);String secondCode=r.initialCode(secondBuyer);
        UUID id=r.start(second.browser,secondOrder,second.phone,secondCode,second.source).attemptId();second.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.exchange(second.browser,id,"123456"));
        String pending=resetGrant(second,r,secondOrder,secondCode);String s=secondAccess.login(secondOrder,second.phone,GUEST_PASSWORD,second.source,null);r.manage(s,GUEST_PASSWORD,null,second.source);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->r.reset(second.browser,pending,password));
    }

    @Test void guestSessionRotationFailureRollsBackAndLookupSerializesWithLogout() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        String old=access.login(order,f.phone,GUEST_PASSWORD,f.source,null),hash=com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(old);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_access_session ADD CONSTRAINT guest_c_rotation_failure CHECK (guest_buyer_id <> '"+buyer+"'::uuid OR token_hash='"+hash+"')");
        assertThrows(DataIntegrityViolationException.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,old));
        assertEquals(1,access.orders(old).size());
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",hash),java.sql.Timestamp.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var reader=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                assertEquals(1,access.orders(old).size());read.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));assertTrue(read.await(5,TimeUnit.SECONDS));
            var logout=executor.submit(()->access.logout(old));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_access_session%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}
                assertTrue(waiting,"Logout must wait behind an authorized read transaction");
            }finally{release.countDown();}reader.get(5,TimeUnit.SECONDS);logout.get(5,TimeUnit.SECONDS);
        }
        assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(old)).status());
    }

    @Test void guestPasswordPreparationReplacementsCannotChangeConsumedBuyer() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);UUID id=guestVerified(f);
        var first=new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD);
        access.prepare(f.browser,id,first);access.prepare(f.browser,id,first);
        String replacement="replacement password C";
        access.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword(replacement,replacement));
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var hashing=new CountDownLatch(1);var release=new CountDownLatch(1);var active=new AtomicBoolean(false);
        var delayed=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String value){if(active.get()){hashing.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}return actual.encode(value);}
            public boolean matches(String value,String hash){return actual.matches(value,hash);}
        };
        var racing=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,delayed,f.clock,true);active.set(true);
        UUID buyer;
        try(var executor=Executors.newSingleThreadExecutor()){
            var writer=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->racing.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword("late password C","late password C"))));
            try{assertTrue(hashing.await(5,TimeUnit.SECONDS));buyer=access.createBuyer(f.browser,id);}finally{release.countDown();}
            assertEquals(409,writer.get(5,TimeUnit.SECONDS).status());
        }
        String saved=jdbc.queryForObject("SELECT password_hash FROM marketplace.guest_buyer WHERE id=:id",Map.of("id",buyer),String.class);
        assertTrue(actual.matches(replacement,saved));assertFalse(actual.matches(GUEST_PASSWORD,saved));assertFalse(actual.matches("late password C",saved));
        assertEquals(saved,jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare(f.browser,id,first));
    }

    @Test void guestLookupRechecksVersionAfterHashAndEnforcesLocalAndSecureBoundaries() {
        var f=new GuestFixture();var original=guestAccess(f);String order="C-"+f.browser;UUID buyer=guestBuyer(f,original,order);
        var actual=new com.example.marketplace.identity.infrastructure.SharedPasswordHashes();
        var changing=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String password){return actual.encode(password);}
            public boolean matches(String password,String hash){
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                boolean result=actual.matches(password,hash);jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:id",Map.of("id",buyer));return result;
            }
        };
        var access=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,changing,f.clock,true);
        assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,null)).status());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE guest_buyer_id=:b",Map.of("b",buyer),Integer.class));
        var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,actual,f.clock,false);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.login(order,f.phone,GUEST_PASSWORD,f.source,null)).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.orders("x".repeat(43))).status());
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.prepare(f.browser,UUID.randomUUID(),new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD))).status());
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setRemoteAddr(UUID.randomUUID().toString());
        var controller=new com.example.marketplace.guest.web.GuestAccessController(original,true);
        var response=controller.login(new com.example.marketplace.guest.web.GuestAccessController.Login(order,f.phone,GUEST_PASSWORD),request,null);
        assertTrue(response.getHeaders().getFirst("Set-Cookie").contains("Secure"));
        assertFalse(response.getBody().toString().contains(GUEST_PASSWORD));
        try(var fixtureContext=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            fixtureContext.register(GuestOrderFixture.class,GuestFixtureSecurity.class);fixtureContext.refresh();
            assertTrue(fixtureContext.getBeansOfType(GuestOrderFixture.class).isEmpty());
            assertTrue(fixtureContext.getBeansOfType(GuestFixtureSecurity.class).isEmpty());
        }
    }

    @Test void guestPasswordHashPreparationAndCreationAreBoundAtomicAndNeverMerge() throws Exception {
        var f=new GuestFixture(); var access=guestAccess(f); UUID id=f.issue();
        var password=new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare(f.browser,id,password));
        f.exchange(id); f.verify(id);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare("other",id,password));
        access.prepare(f.browser,id,password);
        String stored=jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class);
        var hasher=new com.example.marketplace.identity.infrastructure.ArgonPasswordHasher();
        assertTrue(stored.startsWith("{argon2id}")); assertFalse(stored.contains(GUEST_PASSWORD)); assertTrue(hasher.matches(GUEST_PASSWORD,stored));
        assertFalse(hasher.matches(GUEST_PASSWORD.strip(),stored)); assertFalse(hasher.matches(GUEST_PASSWORD+"x",stored)); assertFalse(stored.equals(hasher.encode(GUEST_PASSWORD)));
        assertTrue(hasher.matches("😀".repeat(128),hasher.encode("😀".repeat(128))));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{access.createBuyer(f.browser,id);throw new IllegalStateException("fixture rollback");}));
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_buyer WHERE verified_phone=:p",Map.of("p",f.input.phone()),Integer.class));
        assertThat(race(()->{try{access.createBuyer(f.browser,id);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected rejected){return "rejected";}})).containsExactlyInAnyOrder("ok","rejected");
        UUID first=jdbc.queryForObject("SELECT id FROM marketplace.guest_buyer WHERE verified_phone=:p",Map.of("p",f.input.phone()),UUID.class);
        access.registerOrder(first,"C-"+first);
        f.clock.advance(60); f.service.create(f.browser);
        UUID second=guestBuyer(f,access,"C-"+f.browser);
        assertFalse(first.equals(second));
        assertThrows(DataIntegrityViolationException.class,()->access.registerOrder(second,"C-"+first));
        String token=access.login("C-"+first,f.phone,GUEST_PASSWORD,f.source,null);
        assertEquals(List.of(new com.example.marketplace.guest.infrastructure.JdbcGuestAccess.Order("C-"+first)),access.orders(token));
        assertEquals(404,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.order(token,"C-"+f.browser)).status());
    }

    @Test void guestPasswordHashingRechecksExpiryOutsideTransactions() {
        var f=new GuestFixture(); UUID id=guestVerified(f); var move=new AtomicBoolean(false);
        var actual=new com.example.marketplace.identity.infrastructure.ArgonPasswordHasher();
        var hasher=new com.example.marketplace.identity.api.PasswordHashes(){
            public String encode(String password){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());if(move.get()){f.clock.advance(180);}return actual.encode(password);}
            public boolean matches(String password,String hash){assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return actual.matches(password,hash);}
        };
        var access=new com.example.marketplace.guest.infrastructure.JdbcGuestAccess(jdbc,transactionManager,f.service,hasher,f.clock,true); move.set(true);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.prepare(f.browser,id,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD)));
        assertNull(jdbc.queryForObject("SELECT prepared_password_hash FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class));
    }

    @Test void guestLookupSessionsEnforceIdleMaximumVersionRotationAndLogout() {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;UUID buyer=guestBuyer(f,access,order);
        String token=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        assertEquals(43,token.length());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session WHERE token_hash=:t",Map.of("t",token),Integer.class));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders("x".repeat(43)));
        f.clock.advance(1799);assertEquals(1,access.orders(token).size());f.clock.advance(1800);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(token));
        String maximum=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        for(int i=0;i<24;i++){f.clock.advance(1799);assertEquals(1,access.orders(maximum).size());}
        f.clock.advance(23);assertEquals(1,access.orders(maximum).size());f.clock.advance(1);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(maximum));
        String current=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        String rotated=access.login(order,f.phone,GUEST_PASSWORD,f.source,current);assertFalse(current.equals(rotated));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(current));
        jdbc.update("UPDATE marketplace.guest_buyer SET password_version=password_version+1 WHERE id=:id",Map.of("id",buyer));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(rotated));
        String logout=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);access.logout(logout);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(logout));
    }

    @Test void guestLookupLimitsAndCredentialFailuresAreUniformAndDurable() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;guestBuyer(f,access,order);
        for(int i=0;i<4;i++){assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,"wrong",UUID.randomUUID().toString(),null)).status());}
        assertThat(race(()->{try{access.login(order,f.phone,GUEST_PASSWORD,UUID.randomUUID().toString(),null);return "ok";}catch(com.example.marketplace.guest.api.GuestRejected rejected){return ""+rejected.status();}})).containsExactlyInAnyOrder("ok","429");
        f.clock.advance(899);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(order,f.phone,GUEST_PASSWORD,f.source,null)).status());
        f.clock.advance(1);assertEquals(1,access.orders(access.login(order,f.phone,GUEST_PASSWORD,f.source,null)).size());
        String source=UUID.randomUUID().toString();
        for(int i=0;i<20;i++){String missing="missing-"+UUID.randomUUID();assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login(missing,f.phone,GUEST_PASSWORD,source,null)).status());}
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login("missing",f.phone,GUEST_PASSWORD,source,null)).status());
        f.clock.advance(899);assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login("missing",f.phone,GUEST_PASSWORD,source,null)).status());
        f.clock.advance(1);assertEquals(401,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.login("missing",f.phone,GUEST_PASSWORD,source,null)).status());
    }

    @Test void guestLookupLockWaitRechecksExpiryAndRevocation() throws Exception {
        var f=new GuestFixture();var access=guestAccess(f);String order="C-"+f.browser;guestBuyer(f,access,order);String token=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT token_hash FROM marketplace.guest_access_session WHERE token_hash=:t FOR UPDATE",Map.of("t",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(token)),String.class);
                locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));assertTrue(locked.await(5,TimeUnit.SECONDS));
            var waiter=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->access.orders(token)));
            try {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_access_session%'",Map.of(),Boolean.class);if(waiting){break;}Thread.sleep(20);}
                assertTrue(waiting);f.clock.advance(1800);
            }finally{release.countDown();}holder.get(5,TimeUnit.SECONDS);waiter.get(5,TimeUnit.SECONDS);
        }
        String next=access.login(order,f.phone,GUEST_PASSWORD,f.source,null);
        var outcomes=race(()->{access.logout(next);try{access.orders(next);return "unexpected";}catch(com.example.marketplace.guest.api.GuestRejected rejected){return "revoked";}});
        assertEquals(List.of("revoked","revoked"),outcomes);
    }

    @Test void guestLookupHttpChecksCsrfCookieUniformErrorsAndMemberSeparation() throws Exception {
        var f=new GuestFixture();var fixture=guestAccess(f);String order="C-"+f.browser;guestBuyer(f,fixture,order);
        var client=authBrowser();var input=Map.of("orderNumber",order,"phone",f.phone,"password",GUEST_PASSWORD);
        assertEquals(403,accountRequest(client,"POST","/api/guest/access/login",input,null).statusCode());String csrf=csrfToken(client);
        for(var bad:List.of(Map.of("orderNumber","missing","phone",f.phone,"password",GUEST_PASSWORD),Map.of("orderNumber",order,"phone","01000000000","password",GUEST_PASSWORD),Map.of("orderNumber",order,"phone",f.phone,"password","wrong"))){
            var result=accountRequest(client,"POST","/api/guest/access/login",bad,csrf);assertEquals(401,result.statusCode());assertEquals("AUTHENTICATION_REQUIRED",authMapper.readTree(result.body()).get("code").asText());assertFalse(result.body().contains(GUEST_PASSWORD));
        }
        var result=accountRequest(client,"POST","/api/guest/access/login",input,csrf);assertEquals(200,result.statusCode());
        String cookie=result.headers().firstValue("Set-Cookie").orElseThrow();assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));assertFalse(cookie.contains("Secure"));
        assertFalse(result.body().contains("token"));assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(200,accountRequest(client,"GET","/api/guest/access/references",null,null).statusCode());assertEquals(401,accountRequest(client,"GET","/api/account/profile",null,null).statusCode());
        assertEquals(403,accountRequest(client,"POST","/api/guest/access/logout",Map.of(),null).statusCode());
        assertEquals(200,accountRequest(client,"POST","/api/guest/access/logout",Map.of(),csrf).statusCode());assertEquals(401,accountRequest(client,"GET","/api/guest/access/references",null,null).statusCode());
    }

    @Test void guestProviderStagesWindowsAndRestartFailClosed() {
        var issuing=new GuestFixture(); issuing.provider.issueHook=()->issuing.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,issuing::issue);
        assertEquals("EXPIRED",issuing.service.status(issuing.browser,issuing.token).state());
        var exchanging=new GuestFixture(); UUID id=exchanging.issue(); exchanging.provider.exchangeHook=()->exchanging.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->exchanging.exchange(id));
        assertEquals("EXPIRED",exchanging.service.status(exchanging.browser,exchanging.token).state());
        var window=new GuestFixture(); window.issue(); window.clock.advance(60); window.issue(); window.clock.advance(60); window.issue();
        window.clock.advance(779); assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,window::issue).status());
        window.clock.advance(1); UUID reopened=window.issue(); window.exchange(reopened); window.verify(reopened);
        var failed=new GuestFixture(); UUID failing=failed.issue();
        for(int i=0;i<5;i++) { assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->failed.service.exchange(failed.browser,failed.token,failing,failed.phone,"PURCHASE","000000")); }
        failed.clock.advance(899); assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,failed::issue).status());
        failed.clock.advance(1); failed.exchange(failed.issue());
        var restart=new GuestFixture(); UUID previous=restart.issue(); restart.exchange(previous); restart.verify(previous);
        var restarted=new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,transactionManager,restart.clock,restart.provider);
        String nextCredential=restarted.credential(restart.browser);
        assertFalse(nextCredential.equals(restart.token));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->restarted.status(restart.browser,nextCredential));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->restarted.status(restart.browser,restart.token));
        restarted.create(restart.browser);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->restart.service.status(restart.browser,restart.token));
        restart.clock.advance(60);
        UUID fresh=restarted.issue(restart.browser,nextCredential,restart.input,restart.source).attemptId();
        restarted.exchange(restart.browser,nextCredential,fresh,restart.phone,"PURCHASE","123456");
        assertEquals("VERIFIED",restarted.verify(restart.browser,nextCredential,fresh,restart.phone,"PURCHASE","valid-proof").state());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.guest_attempt SET purpose='RECOVERY' WHERE id=:id",Map.of("id",fresh)));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.guest_attempt SET provider='OTHER' WHERE id=:id",Map.of("id",fresh)));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.guest_attempt SET mode='production' WHERE id=:id",Map.of("id",fresh)));
    }

    @Test void guestBindingReuseAndOneTimeConsumption() throws Exception {
        var f=new GuestFixture(); UUID id=f.issue();
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange("other-browser",f.token,id,f.phone,"PURCHASE","123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,"tampered",id,f.phone,"PURCHASE","123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,f.token,id,"01000000000","PURCHASE","123456"));
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,f.token,id,f.phone,"RECOVERY","123456"));
        f.exchange(id); f.verify(id);
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.verify(id));
        var results=race(()-> { try { var value=f.service.consume(f.browser,f.token,id,f.phone,"PURCHASE"); assertEquals("가상 구매자",value.name()); return "ok"; }
            catch(com.example.marketplace.guest.api.GuestRejected rejected) { return "rejected"; } });
        assertThat(results).containsExactlyInAnyOrder("ok","rejected");
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.status(f.browser,f.token));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_checkout WHERE token_hash=:token",Map.of("token",f.token),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_checkout WHERE token_hash=:token AND state='CONSUMED'",Map.of("token",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(f.token)),Integer.class));
    }

    @Test void guestConcurrentVerificationHasOneWinnerAndNoExternalTransaction() throws Exception {
        var f=new GuestFixture(); UUID id=f.issue(); f.exchange(id);
        assertThat(race(()-> { try { f.verify(id); return "ok"; } catch(com.example.marketplace.guest.api.GuestRejected rejected) { return "rejected"; } })).containsExactlyInAnyOrder("ok","rejected");
        String replacement=f.service.create(f.browser);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.consume(f.browser,f.token,id,f.phone,"PURCHASE"));
        assertEquals("OPEN",f.service.status(f.browser,replacement).state());
        assertThat(race(()->f.service.create(f.browser))).containsOnly(replacement);
        assertEquals("OPEN",f.service.status(f.browser,replacement).state());
    }

    @Test void guestDatabaseFailureLateProviderAndLostResponseRecoverWithoutFalseSuccess() {
        var f=new GuestFixture(); UUID id=f.issue(); f.exchange(id);
        // A deterministic database rejection after the provider accepted its proof.
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.guest_attempt ADD CONSTRAINT guest_test_final_write CHECK (id <> '"+id+"'::uuid OR state <> 'VERIFIED')");
        assertThrows(DataIntegrityViolationException.class,()->f.verify(id));
        assertEquals("CONFIRMING",f.service.status(f.browser,f.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.consume(f.browser,f.token,id,f.phone,"PURCHASE"));
        f.clock.advance(60); UUID replacement=f.issue(); f.exchange(replacement); f.verify(replacement);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            f.service.consume(f.browser,f.token,replacement,f.phone,"PURCHASE"); throw new IllegalStateException("fixture rollback");
        }));
        assertEquals("VERIFIED",f.service.status(f.browser,f.token).state());
        assertEquals("가상 구매자",f.service.consume(f.browser,f.token,replacement,f.phone,"PURCHASE").name());
        var late=new GuestFixture(); UUID stale=late.issue(); late.exchange(stale);
        late.provider.hook=()->late.service.create(late.browser);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->late.verify(stale));
        assertEquals("OPEN",late.service.status(late.browser,late.token).state());
        var lost=new GuestFixture(); UUID pending=lost.issue(); lost.exchange(pending);
        assertEquals("EXCHANGED",lost.service.status(lost.browser,lost.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->lost.exchange(pending));
        lost.clock.advance(60); UUID fresh=lost.issue(); lost.exchange(fresh); lost.verify(fresh);
        // The caller lost the successful response: status recovers it; replay still fails.
        assertEquals("VERIFIED",lost.service.status(lost.browser,lost.token).state());
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->lost.verify(fresh));
        String stored=jdbc.queryForObject("SELECT row_to_json(a)::text FROM marketplace.guest_attempt a WHERE id=:id",Map.of("id",fresh),String.class);
        assertFalse(stored.contains("valid-proof")); assertFalse(stored.contains("custom-proof")); assertFalse(stored.contains("private-session"));
        var disabled=new com.example.marketplace.guest.infrastructure.JdbcGuestPurchases(jdbc,transactionManager,lost.clock,null);
        assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->disabled.status(lost.browser,lost.token)).status());
    }

    @Test void guestBusinessExpiryBeforeAndAfterProviderAndSessionExpiry() {
        var before=new GuestFixture(); UUID good=before.issue(); before.exchange(good); before.clock.advance(179); before.verify(good);
        var exact=new GuestFixture(); UUID expired=exact.issue(); exact.exchange(expired); exact.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->exact.verify(expired));
        assertEquals("EXPIRED",exact.service.status(exact.browser,exact.token).state());
        var during=new GuestFixture(); UUID id=during.issue(); during.exchange(id); during.provider.hook=()->during.clock.advance(180);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->during.verify(id));
        assertEquals("OPEN",jdbc.queryForObject("SELECT state FROM marketplace.guest_checkout WHERE current_attempt=:id",Map.of("id",id),String.class));
        during.clock.advance(1620);
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->during.service.status(during.browser,during.token));
    }

    @Test void guestExpiryIsRecheckedAfterDatabaseLockWait() throws Exception {
        var f=new GuestFixture(); UUID id=f.issue(); f.exchange(id);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var holder=executor.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM marketplace.guest_checkout WHERE current_attempt=:id FOR UPDATE",Map.of("id",id),UUID.class);
                locked.countDown(); try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var waiter=executor.submit(()->assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.verify(id)));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%guest_checkout%'",Map.of(),Boolean.class);
                    if(waiting) { break; } Thread.sleep(20);
                }
                assertTrue(waiting,"Must actually wait for database lock before advancing clock"); f.clock.advance(180);
            } finally { release.countDown(); }
            holder.get(5,TimeUnit.SECONDS); waiter.get(5,TimeUnit.SECONDS);
        }
        assertEquals("EXCHANGED",jdbc.queryForObject("SELECT state FROM marketplace.guest_attempt WHERE id=:id",Map.of("id",id),String.class));
    }

    @Test void guestResendFailureAndSourceLimitsPersistAcrossSessions() {
        var f=new GuestFixture(); UUID old=f.issue();
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,f::issue).status());
        f.clock.advance(60); UUID current=f.issue();
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.exchange(old));
        for(int i=0;i<5;i++) { assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.exchange(f.browser,f.token,current,f.phone,"PURCHASE","999999")); }
        assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.exchange(current));
        f.clock.advance(60);
        String replacement=f.service.create(f.browser);
        var normalized=new com.example.marketplace.guest.api.GuestInput("가상",f.input.phone(),"PURCHASE");
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->f.service.issue(f.browser,replacement,normalized,f.source)).status());
        var outage=new GuestFixture(); outage.provider.broken=true;
        for(int i=0;i<3;i++) { assertEquals(503,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,outage::issue).status()); outage.clock.advance(60); }
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,outage::issue).status());
        String source=UUID.randomUUID().toString();
        for(int i=0;i<20;i++) { var next=new GuestFixture(); next.service.issue(next.browser,next.token,next.input,source); }
        var blocked=new GuestFixture();
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->blocked.service.issue(blocked.browser,blocked.token,blocked.input,source)).status());
        blocked.clock.advance(899);
        assertEquals(429,assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->blocked.service.issue(blocked.browser,blocked.token,blocked.input,source)).status());
        blocked.clock.advance(1); assertEquals("SENT",blocked.service.issue(blocked.browser,blocked.token,blocked.input,source).state());
    }

    @Test void guestHttpRequiresCsrfAndBrowserBindingWithoutGrantingAccountAccess() throws Exception {
        var first=authBrowser(); var second=authBrowser();
        assertEquals(403,accountRequest(first,"POST","/api/guest/checkout",Map.of(),null).statusCode());
        String csrf=csrfToken(first);
        var created=accountRequest(first,"POST","/api/guest/checkout",Map.of(),csrf);
        assertEquals(200,created.statusCode()); assertEquals("no-store",created.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(created.body().contains("token"));
        assertEquals(409,accountRequest(second,"GET","/api/guest/checkout",null,null).statusCode());
        assertEquals(400,accountRequest(first,"POST","/api/guest/verifications",Map.of("name","", "phone","01012345678","purpose","PURCHASE"),csrf).statusCode());
        assertEquals(400,accountRequest(first,"POST","/api/guest/verifications",Map.of("name","가상", "phone","01012345678","purpose","RECOVERY"),csrf).statusCode());
        assertEquals(401,accountRequest(first,"GET","/api/account/profile",null,null).statusCode());
        assertEquals(401,accountRequest(first,"POST","/api/guest/lookup",Map.of(),csrf).statusCode());
    }
}
