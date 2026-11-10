package com.example.marketplace.dbtest;


import com.example.marketplace.identity.infrastructure.LocalPasswordResetMailbox;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIT extends PostgresContractFixture {
    @Test void orderCreationLockOrderAndAuthorityRaces() throws Exception {
        var d=orderDraft("c-lock");var input=creationInput(d);
        try(var connection=context.getBean(DataSource.class).getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) { statement.setObject(1,d.memberId());statement.executeQuery().close(); }
            var future=pool.submit(()->{try { creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input);return true; }catch(RuntimeException rejected) { return false; }});
            try { awaitCouponLock("identity_user");try(var statement=connection.prepareStatement("UPDATE marketplace.identity_user SET state='SUSPENDED' WHERE id=?")) { statement.setObject(1,d.memberId());statement.executeUpdate(); }connection.commit();assertFalse(future.get(10,TimeUnit.SECONDS)); }
            finally { connection.rollback(); }
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",d.memberId()),Integer.class));
    }

    @Test void orderRetryReturnsSameResult() throws Exception {
        var d=orderDraft("c-retry");var input=creationInput(d);var line=d.quote().stores().getFirst().lines().getFirst();
        jdbc.update("UPDATE marketplace.inventory_balance SET on_hand=1 WHERE sku_id=:id",Map.of("id",line.skuId()));
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.of(0,1).stream().map(index->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input);})).toList();
            var first=tasks.get(0).get(15,TimeUnit.SECONDS);assertEquals(first,tasks.get(1).get(15,TimeUnit.SECONDS));
            assertEquals(first,creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input));
            assertEquals("PENDING_PAYMENT",first.state());assertEquals(1,first.sellers().size());assertEquals(1,orders().reservations(first.orderNumber()).inventory().size());assertEquals(1,orders().reservations(first.orderNumber()).coupons().size());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_reservation WHERE order_id=:id",Map.of("id",first.id()),Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",line.skuId()),Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_event WHERE order_id=:id",Map.of("id",first.id()),Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",first.id()),Integer.class));
            assertEquals(first.expiresAt(),jdbc.queryForObject("SELECT scheduled_at FROM marketplace.job WHERE cause_key=:id",Map.of("id",first.id().toString()),java.sql.Timestamp.class).toInstant());
            var changed=new com.example.marketplace.order.infrastructure.OrderCreationService.Input(input.requestKey(),input.lines(),input.coupons(),"c".repeat(64),input.contact(),null,0);
            assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->creation().create(d.purchaseSessionId().toString(),d.memberId(),null,changed)).status());
            UUID other=activeMember("c-retry-other@example.com");
            assertEquals(401,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->creation().create(d.purchaseSessionId().toString(),other,null,input)).status());
        }
    }

    @Test void orderCreationRollsBackAllParticipants() {
        var d=orderDraft("c-rollback");var memberInput=creationInput(d);
        var f=new GuestFixture();var access=guestAccess(f);UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var guestQuote=quotes().calculate(null,memberInput.lines(),List.of());
        var guestInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("guest-rollback",memberInput.lines(),List.of(),guestQuote.fingerprint(),d.contact(),attempt,0);
        for(boolean guest:List.of(false,true)) { for(boolean checked:List.of(false,true)) { for(String point:List.of("GUEST","ORDER","COUPON","INVENTORY","REFERENCE","AUDIT","OUTBOX")) {
            var api=creationWith(access,stage->{if(stage.equals(point)) { if(checked) { throw new java.io.IOException("checked injection"); }throw new IllegalStateException("runtime injection"); }});
            var before=orderParticipantState();
            var failure=assertThrows(IllegalStateException.class,()->api.create(guest?f.browser:d.purchaseSessionId().toString(),guest?null:d.memberId(),null,guest?guestInput:memberInput));
            if(checked) { assertTrue(failure.getCause() instanceof java.io.IOException); }
            assertEquals(before,orderParticipantState(),"all rows restored after "+point+" guest="+guest+" checked="+checked);
        } } }
    }

    @Test void guestOrderConsumesVerificationAtomically() {
        var f=new GuestFixture();var access=guestAccess(f);var api=creationWith(access,stage->{ });UUID firstAttempt=guestVerified(f);access.prepare(f.browser,firstAttempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var fixture=browseFixture("c-guest",10000,10);var lines=List.of(quoteLine((UUID)fixture.get("sku"),1));
        var contact=new com.example.marketplace.order.api.Orders.Contact("ignored","01012345678","수령인","01012345678","01234","주소","상세");
        var q=quotes().calculate(null,lines,List.of());
        var input=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("first",lines,List.of(),q.fingerprint(),contact,firstAttempt,0);
        var first=api.create(f.browser,null,null,input);assertEquals(first,api.create(f.browser,null,null,input));assertEquals("가상 구매자",first.contact().buyerName());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_order_reference WHERE order_number=:n AND guest_buyer_id=:id",Map.of("n",first.orderNumber(),"id",first.guestBuyerId()),Integer.class));
        assertEquals(first,api.create(f.browser,null,null,input));
        UUID next=guestRepeat(f);access.prepare(f.browser,next,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var secondInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("second",lines,List.of(),q.fingerprint(),contact,next,0);
        var second=api.create(f.browser,null,null,secondInput);assertThat(second.guestBuyerId()).isNotEqualTo(first.guestBuyerId());
        assertEquals(first,api.create(f.browser,null,null,input));
        String token=access.login(first.orderNumber(),f.phone,GUEST_PASSWORD,f.source,null);UUID third=guestRepeat(f);var link=access.selectLink(f.browser,third,"EXISTING",token,0);
        var thirdInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("third",lines,List.of(),q.fingerprint(),contact,third,link.revision());
        var before=orderParticipantState();assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->api.create(f.browser,null,null,thirdInput));assertEquals(before,orderParticipantState());
        var linked=api.create(f.browser,null,token,thirdInput);assertEquals(first.guestBuyerId(),linked.guestBuyerId());assertEquals(linked,api.create(f.browser,null,token,thirdInput));
        access.logout(token);assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->api.create(f.browser,null,token,thirdInput));
        f.clock.advance(901);UUID fourth=guestRepeat(f);access.prepare(f.browser,fourth,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
        var stale=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("stale",lines,List.of(),q.fingerprint(),contact,fourth,0);
        access.selectLink(f.browser,fourth,"NEW",null,0);before=orderParticipantState();assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->api.create(f.browser,null,null,stale));assertEquals(before,orderParticipantState());
    }

    @Test void orderHttpCsrfAndCurrentSessionOwnership() throws Exception {
        var d=orderDraft("c-http");var browser=authBrowser();
        String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
        var input=creationInput(d);String body=authMapper.writeValueAsString(input);
        var denied=browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/orders")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,denied.statusCode());
        String csrf=csrfToken(browser);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/orders")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var success=browser.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,success.statusCode());assertTrue(success.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(success.body(),browser.send(request,HttpResponse.BodyHandlers.ofString()).body());assertFalse(success.body().contains("buyerPhone"));assertEquals("PENDING_PAYMENT",authMapper.readTree(success.body()).get("state").asText());
    }

    @Test void orderCartReconciliationPreservesChangedAndAddedItems() {
        UUID buyer=activeMember("c-cleanup@example.com");var a=browseFixture("c-cart-a",100,10);var b=browseFixture("c-cart-b",100,10);var c=browseFixture("c-cart-c",100,10);
        var view=carts().set(buyer,(UUID)a.get("sku"),1,0);view=carts().set(buyer,(UUID)b.get("sku"),2,view.version());
        var submitted=view.items().stream().map(l->new com.example.marketplace.cart.api.Carts.Submitted(l.skuId(),l.quantity(),l.revision())).toList();
        view=carts().set(buyer,(UUID)b.get("sku"),3,view.version());view=carts().set(buyer,(UUID)b.get("sku"),2,view.version());view=carts().set(buyer,(UUID)c.get("sku"),1,view.version());
        UUID order=UUID.randomUUID();var cleaned=org.junit.jupiter.api.Assertions.assertDoesNotThrow(()->carts().reconcile(buyer,order,submitted));
        assertThat(cleaned.items().stream().map(l->l.skuId()).toList()).containsExactlyInAnyOrder((UUID)b.get("sku"),(UUID)c.get("sku"));
        var added=carts().set(buyer,(UUID)a.get("sku"),1,cleaned.version());assertEquals(added,carts().reconcile(buyer,order,submitted));
        assertThrows(com.example.marketplace.cart.api.CartException.class,()->carts().reconcile(buyer,order,List.of()));
    }

    @Test void orderAuthorityChangesAfterActualRowWait() throws Exception {
        for(String mode:List.of("seller","store","product","category","price","coupon","expiry")) {
            var d=orderDraft("c-authority-"+mode);var input=creationInput(d);var store=d.quote().stores().getFirst();var line=store.lines().getFirst();
            String table=switch(mode) {case "seller"->"identity_user";case "store"->"seller_store";case "product"->"catalog_product";case "category"->"catalog_category";case "price"->"catalog_sku";default->"issued_coupon";};
            UUID id=switch(mode) {case "seller"->d.memberId();case "store"->store.storeId();case "product"->line.productId();case "category"->jdbc.queryForObject("SELECT category_id FROM marketplace.catalog_product WHERE id=:id",Map.of("id",line.productId()),UUID.class);case "price"->line.skuId();default->store.coupon().id();};
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",id),UUID.class);
                    pending.set(pool.submit(()->{try {creation().create(d.purchaseSessionId().toString(),d.memberId(),null,input);return true;}catch(RuntimeException rejected){return false;}}));
                    awaitCouponLock(table);
                    String change=switch(mode) {case "seller"->"state='SUSPENDED'";case "store"->"visible=false";case "product"->"is_hidden_by_admin=true";case "category"->"active=false";case "price"->"price=price+1,version=version+1";case "coupon"->"state='USED',version=version+1";default->"ends_at=clock_timestamp()-interval '1 second'";};
                    jdbc.update("UPDATE marketplace."+table+" SET "+change+" WHERE id=:id",Map.of("id",id));
                });
                assertFalse(pending.get().get(15,TimeUnit.SECONDS),mode);
            }
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",d.memberId()),Integer.class),mode);
            assertEquals(0,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",line.skuId()),Integer.class),mode);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_reservation WHERE coupon_id=:id",Map.of("id",store.coupon().id()),Integer.class),mode);
        }
    }

    @Test void orderAdministratorAuditLocksDoNotCycle() throws Exception {
        for(boolean category:List.of(true,false)) {
            var a=browseFixture("c-admin-a-"+category,10000,10);var b=browseFixture("c-admin-b-"+category,10000,10);
            var left=((UUID)a.get("owner")).toString().compareTo(((UUID)b.get("owner")).toString())<0?a:b;var right=left==a?b:a;
            UUID actor=(UUID)left.get("owner"),seller=(UUID)right.get("owner");var product=(com.example.marketplace.catalog.api.Products.Product)right.get("product");
            jdbc.update("INSERT INTO marketplace.role_assignment VALUES(:id,'ADMIN')",Map.of("id",actor));jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:id,'CONTENT'),(:id,'ACCOUNTS')",Map.of("id",actor));
            var input=orderInput(actor,List.of(quoteLine((UUID)right.get("sku"),1)),List.of(),"admin");String browser=UUID.randomUUID().toString();
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    String table=category?"catalog_category":"identity_user";UUID locked=category?product.categoryId():seller;
                    jdbc.queryForObject("SELECT id FROM marketplace."+table+" WHERE id=:id FOR UPDATE",Map.of("id",locked),UUID.class);
                    pending.set(pool.submit(()->{try {creation().create(browser,actor,null,input);return true;}catch(RuntimeException rejected){return false;}}));
                    awaitCouponLock(table);
                    // Real administrator command writes an actor FK while C owns actor's purchase lock.
                    if(category) {var row=jdbc.queryForMap("SELECT name,version FROM marketplace.catalog_category WHERE id=:id",Map.of("id",locked));context.getBean(com.example.marketplace.catalog.api.Categories.class).save(actor,locked,new com.example.marketplace.catalog.api.Categories.Input((String)row.get("name"),false,((Number)row.get("version")).longValue()));}
                    else {context.getBean(com.example.marketplace.identity.api.AccountStateOperations.class).suspend(actor,seller,"order audit race");}
                });
                assertFalse(pending.get().get(15,TimeUnit.SECONDS));
            }
            String audit=category?"category_event":"account_state_audit";assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace."+audit+" WHERE actor_id=:id",Map.of("id",actor),Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",actor),Integer.class));
        }
    }

    @Test void orderCrossSellerAndSameBuyerBatchesSerializeBeforeForeignKeys() throws Exception {
        for(boolean cross:List.of(true,false)) {
            var a=reservationRequest("c-cross-a-"+cross);var b=reservationRequest("c-cross-b-"+cross);
            UUID first=cross?a.memberId():activeMember("c-samebuyer@example.com"),second=cross?b.memberId():first;
            var buyers=List.of(first,second);var sources=List.of(b,a);var inputs=new java.util.ArrayList<com.example.marketplace.order.infrastructure.OrderCreationService.Input>();
            for(int i=0;i<2;i++) {var source=sources.get(i);var coupon=coupons().claim(buyers.get(i),coupons().own(source.memberId(),source.issuedCouponId()).templateId());inputs.add(orderInput(buyers.get(i),List.of(quoteLine(source.quoteSnapshot().stores().getFirst().lines().getFirst().skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(coupon.storeId(),coupon.id())),"cross-"+i));}
            UUID locked=cross?java.util.stream.Stream.of(first,second).min(java.util.Comparator.comparing(UUID::toString)).orElseThrow():first;
            try(var pool=Executors.newFixedThreadPool(2);var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")){statement.setObject(1,locked);statement.executeQuery().close();}
                var tasks=List.of(0,1).stream().map(i->pool.submit(()->creation().create(UUID.randomUUID().toString(),buyers.get(i),null,inputs.get(i)))).toList();
                try {awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%identity_user%FOR NO KEY UPDATE%'",Integer.class)>=2,"both purchases actually wait before FK insertion");connection.commit();for(var task:tasks){var saved=task.get(15,TimeUnit.SECONDS);assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_reservation WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_event WHERE order_id=:id",Map.of("id",saved.id()),Integer.class));}}
                finally {connection.rollback();}
            }
        }
    }

    @Test void orderLastStockHasOneWinnerAndNoPartialCoupon() throws Exception {
        var seller=browseFixture("c-last-stock",10000,1);UUID owner=(UUID)seller.get("owner"),sku=(UUID)seller.get("sku");var template=couponTemplate(owner,"last stock",5);
        var buyers=List.of(activeMember("c-stock-first@example.com"),activeMember("c-stock-second@example.com"));var inputs=new java.util.ArrayList<com.example.marketplace.order.infrastructure.OrderCreationService.Input>();var issued=new java.util.ArrayList<UUID>();
        for(UUID buyer:buyers){var coupon=coupons().claim(buyer,template.id());issued.add(coupon.id());inputs.add(orderInput(buyer,List.of(quoteLine(sku,1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(coupon.storeId(),coupon.id())),"last"));}
        var barrier=new java.util.concurrent.CyclicBarrier(2);int winners=0;
        try(var pool=Executors.newFixedThreadPool(2)){var tasks=List.of(0,1).stream().map(i->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);try{return creation().create(UUID.randomUUID().toString(),buyers.get(i),null,inputs.get(i));}catch(com.example.marketplace.promotion.api.PromotionRejected rejected){assertEquals(409,rejected.status());return null;}})).toList();for(var task:tasks){if(task.get(15,TimeUnit.SECONDS)!=null){winners++;}}}
        assertEquals(1,winners);assertEquals(1,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE id IN (:ids) AND state='RESERVED'",Map.of("ids",issued),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.issued_coupon WHERE id IN (:ids) AND state='AVAILABLE'",Map.of("ids",issued),Integer.class));
    }

    @Test void guestOrderRejectsStaleQuoteAndCurrentCredentialChanges(CapturedOutput output) throws Exception {
        for(String mode:List.of("price","expired","replaced","revoked","coupons","checkout-wait")) {
            var f=new GuestFixture();var access=guestAccess(f);UUID attempt=guestVerified(f);access.prepare(f.browser,attempt,new com.example.marketplace.guest.api.GuestPassword(GUEST_PASSWORD,GUEST_PASSWORD));
            var fixture=browseFixture("c-guest-reject-"+mode,10000,10);UUID sku=(UUID)fixture.get("sku");var lines=List.of(quoteLine(sku,1));var q=quotes().calculate(null,lines,List.of());var contact=new com.example.marketplace.order.api.Orders.Contact("ignored","01012345678","수령인","01012345678","01234","주소","");
            var selected=mode.equals("coupons")?List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(UUID.randomUUID(),UUID.randomUUID())):List.<com.example.marketplace.promotion.api.Quotes.CouponSelection>of();
            var input=new com.example.marketplace.order.infrastructure.OrderCreationService.Input("reject",lines,selected,q.fingerprint(),contact,attempt,0);var api=creationWith(access,stage->{ });
            switch(mode) {
                case "price"->jdbc.update("UPDATE marketplace.catalog_sku SET price=price+1000,version=version+1 WHERE id=:id",Map.of("id",sku));
                case "expired"->f.clock.advance(181);
                case "replaced"->{f.clock.advance(60);f.service.issue(f.browser,f.token,new com.example.marketplace.guest.api.GuestInput("changed","01099998888","PURCHASE"),f.source);}
                case "revoked"->jdbc.update("UPDATE marketplace.guest_checkout SET state='REVOKED' WHERE current_attempt=:id",Map.of("id",attempt));
                default->{ }
            }
            var before=orderParticipantState();
            if(mode.equals("checkout-wait")) {
                try(var pool=Executors.newSingleThreadExecutor()) {
                    var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
                    new TransactionTemplate(transactionManager).executeWithoutResult(s->{jdbc.queryForObject("SELECT id FROM marketplace.guest_checkout WHERE current_attempt=:id FOR UPDATE",Map.of("id",attempt),UUID.class);pending.set(pool.submit(()->{try{api.create(f.browser,null,null,input);return true;}catch(RuntimeException denied){return false;}}));awaitCouponLock("guest_checkout");f.clock.advance(181);});assertFalse(pending.get().get(10,TimeUnit.SECONDS));
                }
            } else {
                var rejected=assertThrows(RuntimeException.class,()->api.create(f.browser,null,null,input));
                if(mode.equals("price")) {var changed=(com.example.marketplace.order.api.OrderRejected)rejected;assertEquals(409,changed.status());assertThat(changed.quote()).isNotNull();assertThat(changed.quote().fingerprint()).isNotEqualTo(q.fingerprint());}
            }
            assertEquals(before,orderParticipantState(),mode);
            assertFalse(String.join("",before.values()).contains(GUEST_PASSWORD));assertFalse(output.getAll().contains(GUEST_PASSWORD));assertFalse(output.getAll().contains("valid-proof"));
        }
    }

    @Test void purchaseAccountLockAllowsAuditForeignKeysButExcludesMutations() {
        UUID member=activeMember("c-lock-strength@example.com");var membership=context.getBean(com.example.marketplace.identity.api.SellerMembership.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            membership.lockActiveBuyer(member);
            try(var other=context.getBean(DataSource.class).getConnection()) {
                other.setAutoCommit(false);
                try(var statement=other.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR KEY SHARE NOWAIT")){statement.setObject(1,member);assertTrue(statement.executeQuery().next());}other.rollback();
                try(var statement=other.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE NOWAIT")){statement.setObject(1,member);assertEquals("55P03",assertThrows(java.sql.SQLException.class,statement::executeQuery).getSQLState());}other.rollback();
                try(var statement=other.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR NO KEY UPDATE NOWAIT")){statement.setObject(1,member);assertEquals("55P03",assertThrows(java.sql.SQLException.class,statement::executeQuery).getSQLState());}other.rollback();
            }catch(java.sql.SQLException e){throw new IllegalStateException(e);}
        });
    }

    @Test void orderManualStockAdjustmentSerializesAndRechecksAvailability() throws Exception {
        var fixture=browseFixture("c-manual-stock",10000,1);UUID owner=(UUID)fixture.get("owner"),sku=(UUID)fixture.get("sku");var product=(com.example.marketplace.catalog.api.Products.Product)fixture.get("product");var input=orderInput(owner,List.of(quoteLine(sku,1)),List.of(),"manual");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                context.getBean(com.example.marketplace.identity.api.SellerMembership.class).lockActiveBuyer(owner);
                pending.set(pool.submit(()->{try{creation().create(UUID.randomUUID().toString(),owner,null,input);return true;}catch(RuntimeException denied){return false;}}));awaitCouponLock("identity_user");
                context.getBean(com.example.marketplace.inventory.api.Inventory.class).adjust(owner,product.id(),sku,false,new com.example.marketplace.inventory.api.Inventory.Change(0L,0L,"concurrent reduction",UUID.randomUUID()));
            });assertFalse(pending.get().get(10,TimeUnit.SECONDS));
        }
        assertEquals(0,jdbc.queryForObject("SELECT reserved FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",owner),Integer.class));
    }

    @Test void orderHttpRechecksSessionAfterActualAccountWait() throws Exception {
        for(boolean retry:List.of(false,true)) { for(String mode:List.of("logout","expired","password")) {
            var d=orderDraft("c-session-"+mode+retry);var browser=authBrowser();String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",d.memberId()),String.class);
            assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email," A1-private-passphrase-가나다 "),csrfToken(browser)).statusCode());
            String csrf=csrfToken(browser);var input=creationInput(d);var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/orders")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(authMapper.writeValueAsString(input))).build();
            var recovery=context.getBean(com.example.marketplace.identity.api.PasswordRecoveryOperations.class);String token=null;
            if(mode.equals("password")) {recovery.request(email);token=context.getBean(LocalPasswordResetMailbox.class).messages().stream().filter(message->message.recipient().equals(email)).findFirst().orElseThrow().token();}
            String recoveryToken=token;
            if(retry) { assertEquals(200,browser.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()); }
            var participants=orderParticipantState();
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
                new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE id=:id FOR UPDATE",Map.of("id",d.memberId()),UUID.class);
                    pending.set(pool.submit(()->browser.send(request,HttpResponse.BodyHandlers.ofString())));awaitCouponLock("identity_user");
                    if(mode.equals("password")){recovery.reset(recoveryToken,"replacement session password");}
                    else if(mode.equals("expired")){jdbc.update("UPDATE marketplace.spring_session SET expiry_time=0 WHERE principal_name=:id",Map.of("id",d.memberId().toString()));}
                    else {try{assertEquals(200,authRequest(browser,"/api/auth/logout","",csrf).statusCode());}catch(Exception e){throw new IllegalStateException(e);}}
                });
                assertEquals(401,pending.get().get(15,TimeUnit.SECONDS).statusCode(),mode);
            }
            assertEquals(retry?1:0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE member_id=:id",Map.of("id",d.memberId()),Integer.class),mode);
            assertEquals(participants,orderParticipantState(),mode+" retry="+retry);
        } }
    }

    @Test void orderSnapshotsSurviveSourceChanges() {
        var original=orderDraft("snapshot");UUID productId=original.quote().stores().getFirst().lines().getFirst().productId();
        var promo=promotions().save(original.memberId(),null,promotionInput(productId,"DRAFT",0));promotions().save(original.memberId(),promo.id(),promotionInput(productId,"ACTIVE",0));
        var quote=quotes().calculate(original.memberId(),List.of(quoteLine(original.quote().stores().getFirst().lines().getFirst().skuId(),1)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(original.quote().stores().getFirst().storeId(),original.quote().stores().getFirst().coupon().id())));
        var draft=new com.example.marketplace.order.api.Orders.Draft(original.purchaseSessionId(),original.requestKey(),original.requestHash(),original.memberId(),null,null,original.mode(),quote,original.contact(),original.createdAt(),original.expiresAt());
        var saved=orders().save(draft);assertThat(saved).isNotNull();
        assertEquals(draft.quote(),saved.quote());assertEquals(draft.contact(),saved.contact());
        var line=draft.quote().stores().getFirst().lines().getFirst();var coupon=draft.quote().stores().getFirst().coupon();
        assertThat(line.promotion()).isNotNull();promotions().save(draft.memberId(),promo.id(),promotionInput(productId,"STOPPED",1));
        jdbc.update("UPDATE marketplace.catalog_product SET title='나중에 수정',is_hidden_by_admin=true WHERE id=:id",Map.of("id",line.productId()));
        jdbc.update("UPDATE marketplace.catalog_sku SET price=123 WHERE id=:id",Map.of("id",line.skuId()));
        jdbc.update("UPDATE marketplace.issued_coupon SET title='수정된 쿠폰' WHERE id=:id",Map.of("id",coupon.id()));
        assertEquals(saved,orders().find(saved.orderNumber()));
        assertEquals(saved.quote().total(),jdbc.queryForObject("SELECT sum(total) FROM marketplace.seller_order WHERE order_id=:id",Map.of("id",saved.id()),Long.class));
        assertEquals(line.net(),jdbc.queryForObject("SELECT sum(u.net) FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:id",Map.of("id",saved.id()),Long.class));
        var failed=orderDraft("rollback");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(s->{orders().save(failed);throw new IllegalStateException("caller");}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",failed.purchaseSessionId()),Integer.class));
    }

    @Test void checkoutAttemptsAndOrderNumbersAreDistinct() {
        var d=orderDraft("attempt");var first=orders().save(d);assertThat(first).isNotNull();assertEquals(first,orders().save(d));
        var other=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),"second",d.requestHash(),d.memberId(),null,null,"SANDBOX",d.quote(),d.contact(),d.createdAt(),d.expiresAt());
        var second=orders().save(other);assertThat(second.checkoutId()).isNotEqualTo(first.checkoutId());assertThat(second.orderNumber()).isNotEqualTo(first.orderNumber());
        assertEquals("DEMO",orders().find(first.orderNumber()).mode());assertEquals("SANDBOX",orders().find(second.orderNumber()).mode());
        var changed=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),"c".repeat(64),d.memberId(),null,null,d.mode(),d.quote(),d.contact(),d.createdAt(),d.expiresAt());
        assertThrows(RuntimeException.class,()->orders().save(changed));
        for(String mode:List.of("LIVE","")) { assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET mode=:mode WHERE id=:id",Map.of("mode",mode,"id",first.id()))); }
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET mode=NULL WHERE id=:id",Map.of("id",first.id())));
        assertEquals(first,orders().find(first.orderNumber()));
    }

    @Test void orderNumberCollisionsRetryWithoutOrphans() {
        var d=orderDraft("collision");var first=orders().save(d);UUID collision=UUID.fromString(first.orderNumber().substring(1).replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})","$1-$2-$3-$4-$5"));
        var sequence=new java.util.concurrent.atomic.AtomicInteger();var api=new com.example.marketplace.order.infrastructure.JdbcOrders(jdbc,authMapper,transactionManager,()->sequence.getAndIncrement()==0?collision:UUID.randomUUID());
        var another=orderDraft("collision-second");var second=api.save(another);assertEquals(2,sequence.get());assertThat(second.orderNumber()).isNotEqualTo(first.orderNumber());assertEquals(second,api.find(second.orderNumber()));
        var stuck=new com.example.marketplace.order.infrastructure.JdbcOrders(jdbc,authMapper,transactionManager,()->collision);var rejected=orderDraft("collision-exhausted");
        assertThrows(IllegalStateException.class,()->stuck.save(rejected));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",rejected.purchaseSessionId()),Integer.class));
    }

    @Test void orderDeadlineAndMalformedSnapshotsLeaveNoRows() {
        var d=orderDraft("invalid");
        for(Instant expires:List.of(d.createdAt(),d.createdAt().minusSeconds(1),d.createdAt().plusSeconds(901),d.createdAt().plusNanos(1))) {
            var bad=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),d.quote(),d.contact(),d.createdAt(),expires);
            assertThrows(IllegalArgumentException.class,()->orders().save(bad));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",bad.purchaseSessionId()),Integer.class));
        }
        var q=d.quote();var altered=new com.example.marketplace.promotion.api.Quotes.Quote(q.memberId(),q.policyVersion(),q.evaluatedAt(),q.stores(),q.total()+1,q.fingerprint());
        var bad=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),altered,d.contact(),d.createdAt(),d.expiresAt());
        assertThrows(IllegalArgumentException.class,()->orders().save(bad));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",bad.purchaseSessionId()),Integer.class));
        var atLimit=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),d.createdAt(),d.createdAt().plusSeconds(900));
        assertEquals(atLimit.expiresAt(),orders().save(atLimit).expiresAt());
        var couponEnd=q.stores().getFirst().coupon().terms().endsAt();var created=couponEnd.minusSeconds(600);
        var boundary=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),created,couponEnd);
        assertEquals(couponEnd,orders().save(boundary).expiresAt());
        var over=new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),created,couponEnd.plusNanos(1000));
        assertThrows(IllegalArgumentException.class,()->orders().save(over));
    }

    @Test void orderMultiStoreUnitsAndDatabaseBoundaries() {
        var d=orderDraft("multi");var a=d.quote().stores().getFirst().lines().getFirst();var other=browseFixture("order-other",101,20);
        var extra=browseFixture("order-extra",203,20);UUID extraProduct=((com.example.marketplace.catalog.api.Products.Product)extra.get("product")).id();
        jdbc.update("UPDATE marketplace.catalog_product SET store_id=:store WHERE id=:id",Map.of("store",d.quote().stores().getFirst().storeId(),"id",extraProduct));
        var productPromo=promotions().save(d.memberId(),null,promotionInput(a.productId(),"DRAFT",0));promotions().save(d.memberId(),productPromo.id(),promotionInput(a.productId(),"ACTIVE",0));
        var shippingInput=new com.example.marketplace.promotion.api.Promotions.Input("배송 700원 할인","SHIPPING",List.of(),d.createdAt().minusSeconds(60),d.createdAt().plusSeconds(3600),"FIXED",700L,null,"DRAFT",0L);
        var shippingPromo=promotions().save(d.memberId(),null,shippingInput);promotions().save(d.memberId(),shippingPromo.id(),new com.example.marketplace.promotion.api.Promotions.Input(shippingInput.title(),shippingInput.scope(),shippingInput.productIds(),shippingInput.startsAt(),shippingInput.endsAt(),shippingInput.kind(),shippingInput.value(),shippingInput.cap(),"ACTIVE",0L));
        var q=quotes().calculate(d.memberId(),List.of(quoteLine(a.skuId(),3),quoteLine((UUID)other.get("sku"),3),quoteLine((UUID)extra.get("sku"),2)),List.of(new com.example.marketplace.promotion.api.Quotes.CouponSelection(d.quote().stores().getFirst().storeId(),d.quote().stores().getFirst().coupon().id())));
        var draft=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),d.memberId(),null,null,d.mode(),q,d.contact(),d.createdAt(),d.expiresAt());
        var saved=orders().save(draft);assertEquals(q,saved.quote());assertEquals(2,saved.sellers().size());
        assertEquals(q.total(),jdbc.queryForObject("SELECT amount FROM marketplace.purchase_order WHERE id=:id",Map.of("id",saved.id()),Long.class));
        var discounted=q.stores().stream().filter(s->s.coupon()!=null).findFirst().orElseThrow();assertTrue(discounted.productDiscount()>0);assertTrue(discounted.couponDiscount()>0);assertEquals(3000,discounted.shippingBase());assertEquals(700,discounted.shippingDiscount());
        assertEquals(8,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.order_id=:id",Map.of("id",saved.id()),Integer.class));
        for(var store:q.stores()) {
            UUID seller=saved.sellers().stream().filter(s->s.storeId().equals(store.storeId())).findFirst().orElseThrow().id();
            assertEquals(store.total(),jdbc.queryForObject("SELECT total FROM marketplace.seller_order WHERE id=:id",Map.of("id",seller),Long.class));
            assertEquals(List.of(store.subtotal(),store.productDiscount(),store.couponDiscount(),store.shippingBase(),store.shippingDiscount(),store.total()),jdbc.queryForObject("SELECT subtotal,product_discount,coupon_discount,shipping_base,shipping_discount,total FROM marketplace.seller_order WHERE id=:id",Map.of("id",seller),(rs,n)->List.of(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6))));
            for(var line:store.lines()) {
                assertEquals(List.of(line.unitPrice(),line.gross(),line.promotionDiscount(),line.couponDiscount(),line.net()),jdbc.queryForObject("SELECT unit_price,gross,promotion_discount,coupon_discount,net FROM marketplace.order_item WHERE seller_order_id=:seller AND sku_id=:sku",Map.of("seller",seller,"sku",line.skuId()),(rs,n)->List.of(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5))));
                assertEquals(line,authMapper.readValue(jdbc.queryForObject("SELECT line_snapshot::text FROM marketplace.order_item WHERE seller_order_id=:seller AND sku_id=:sku",Map.of("seller",seller,"sku",line.skuId()),String.class),com.example.marketplace.promotion.api.Quotes.Line.class));
                var actual=jdbc.query("SELECT u.ordinal,u.gross,u.promotion_discount,u.coupon_discount,u.net FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id WHERE i.seller_order_id=:seller AND i.sku_id=:sku ORDER BY u.ordinal",Map.of("seller",seller,"sku",line.skuId()),(rs,n)->new com.example.marketplace.promotion.api.Quotes.Unit(rs.getInt(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)));
                assertEquals(line.units(),actual);
            }
        }
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET member_id=NULL WHERE id=:id",Map.of("id",saved.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET amount=-1 WHERE id=:id",Map.of("id",saved.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET state='SHIPPED' WHERE id=:id",Map.of("id",saved.id())));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.seller_order SET store_id=:store WHERE order_id=:id",Map.of("id",saved.id(),"store",q.stores().getFirst().storeId())));
        assertEquals(saved,orders().find(saved.orderNumber()));
    }

    @Test void orderConcurrentKeysHaveOneDurableResult() throws Exception {
        for(boolean conflict:List.of(false,true)) {
            var d=orderDraft("concurrent-"+conflict);var other=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),conflict?"d".repeat(64):d.requestHash(),d.memberId(),null,null,d.mode(),d.quote(),d.contact(),d.createdAt(),d.expiresAt());
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var futures=List.of(d,other).stream().map(draft->pool.submit(()->{
                    try { return new TransactionTemplate(transactionManager).execute(status->{try { barrier.await(10,TimeUnit.SECONDS); } catch(Exception ex) { throw new IllegalStateException(ex); } return orders().save(draft);}); }
                    catch(IllegalArgumentException ex) { assertEquals("ORDER_REQUEST_CONFLICT",ex.getMessage());return null; }
                })).toList();
                var results=new java.util.ArrayList<com.example.marketplace.order.api.Orders.Snapshot>();for(var future:futures) { var result=future.get(15,TimeUnit.SECONDS);if(result!=null) { results.add(result); } }
                assertEquals(conflict?1:2,results.size());if(!conflict) { assertEquals(results.get(0),results.get(1)); }
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",d.purchaseSessionId()),Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE checkout_id=:id",Map.of("id",results.getFirst().checkoutId()),Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_contact WHERE order_id=:id",Map.of("id",results.getFirst().id()),Integer.class));
            }
        }
    }

    @Test void orderGuestAttemptBindingSurvivesCheckoutRestart() throws Exception {
        var d=orderDraft("guest");UUID checkout=UUID.randomUUID(),attempt=UUID.randomUUID(),buyer=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.guest_checkout(id,token_hash,browser_hash,purpose,expires_at,state,created_at) VALUES(:id,:token,:browser,'PURCHASE',clock_timestamp()+interval '30 minutes','VERIFIED',clock_timestamp())",Map.of("id",checkout,"token",UUID.randomUUID().toString(),"browser",UUID.randomUUID().toString()));
        jdbc.update("INSERT INTO marketplace.guest_attempt(id,checkout_id,buyer_name,phone,purpose,state,issued_at,expires_at) VALUES(:id,:checkout,'가상 구매자','01012345678','PURCHASE','VERIFIED',clock_timestamp(),clock_timestamp()+interval '3 minutes')",Map.of("id",attempt,"checkout",checkout));
        jdbc.update("UPDATE marketplace.guest_checkout SET current_attempt=:attempt WHERE id=:id",Map.of("id",checkout,"attempt",attempt));
        jdbc.update("INSERT INTO marketplace.guest_buyer(id,buyer_name,verified_phone,password_hash,created_at) VALUES(:id,'가상 구매자','01012345678','{argon2id}test-fixture',clock_timestamp())",Map.of("id",buyer));
        var q=quotes().calculate(null,List.of(quoteLine(d.quote().stores().getFirst().lines().getFirst().skuId(),2)),List.of());
        var guest=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),d.requestKey(),d.requestHash(),null,buyer,attempt,d.mode(),q,d.contact(),d.createdAt(),d.expiresAt());
        com.example.marketplace.order.api.Orders.Snapshot saved;
        var start=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(0,1).stream().map(index->pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{try { start.await(10,TimeUnit.SECONDS); } catch(Exception ex) { throw new IllegalStateException(ex); } return orders().save(guest); }))).toList();
            saved=futures.get(0).get(15,TimeUnit.SECONDS);assertEquals(saved,futures.get(1).get(15,TimeUnit.SECONDS));
        }
        assertEquals(buyer,saved.guestBuyerId());assertNull(saved.memberId());
        jdbc.update("UPDATE marketplace.guest_checkout SET current_attempt=NULL,state='OPEN' WHERE id=:id",Map.of("id",checkout));
        assertEquals(saved,orders().save(guest));assertEquals(attempt,jdbc.queryForObject("SELECT guest_attempt_id FROM marketplace.order_checkout_attempt WHERE id=:id",Map.of("id",saved.checkoutId()),UUID.class));
        var reused=new com.example.marketplace.order.api.Orders.Draft(d.purchaseSessionId(),"another-key",d.requestHash(),null,buyer,attempt,d.mode(),q,d.contact(),d.createdAt(),d.expiresAt());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->orders().save(reused)).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE guest_attempt_id=:id",Map.of("id",attempt),Integer.class));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.purchase_order SET member_id=:member WHERE id=:id",Map.of("member",d.memberId(),"id",saved.id())));
        assertEquals(saved,orders().find(saved.orderNumber()));
    }

    @Test void orderOwnerAndAttemptConstraints() {
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT to_regclass('marketplace.purchase_order')::text",String.class)).isNotNull();
        UUID member=activeMember("p08-owner@example.com"),session=UUID.randomUUID();
        UUID attempt=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace.order_checkout_attempt(id,purchase_session_id,request_key,request_hash,created_at,expires_at) VALUES(:id,:session,'first',:hash,clock_timestamp(),clock_timestamp()+interval '10 minutes')",Map.of("id",attempt,"session",session,"hash","a".repeat(64)));
        for(String mode:List.of("DEMO","SANDBOX")) {
            UUID id=UUID.randomUUID();
            assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.purchase_order(id,checkout_id,order_number,mode,state,amount,policy_version,quote_snapshot,created_at,expires_at) VALUES(:id,:checkout,:number,:mode,'PENDING_PAYMENT',0,'p08','{}',clock_timestamp(),clock_timestamp()+interval '10 minutes')",Map.of("id",id,"checkout",attempt,"number",UUID.randomUUID().toString(),"mode",mode)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.purchase_order WHERE id=:id",Map.of("id",id),Integer.class));
        }
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO marketplace.order_checkout_attempt(id,purchase_session_id,request_key,request_hash,created_at,expires_at) VALUES(:id,:session,'first',:hash,clock_timestamp(),clock_timestamp()+interval '10 minutes')",Map.of("id",UUID.randomUUID(),"session",session,"hash","a".repeat(64))));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",session),Integer.class));
        assertThat(member).isNotNull();
    }
}
