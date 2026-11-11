package com.example.marketplace.dbtest;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.settlement.api.Settlements;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportIT extends SettlementContractFixture {
    private static final String CASES="/api/support/cases";
    private String readOk(HttpClient client,String path)throws Exception{
        var response=accountRequest(client,"GET",path,null,null);
        assertEquals(200,response.statusCode(),path);
        return response.body();
    }
    private String post(HttpClient browser,String path,Map<String,?> body,int status) throws Exception {
        var response=accountRequest(browser,"POST",path,body,csrfToken(browser));
        assertEquals(status,response.statusCode(),response.body());
        return response.body();
    }
    private void verifyOrderHandoff(HttpClient customer,String number,UUID sellerOrder,String memberMessage,String memberCase,String operatorFile)throws Exception{
        var initial=supportFixture("e-order-first","SUPPORT");
        var successor=supportFixture("e-order-next","SUPPORT");
        String orderPath="/api/orders/"+number,paymentPath="/api/payments/"+number+"/history";
        String orderBefore=readOk(customer,orderPath),paymentBefore=readOk(customer,paymentPath);
        String assign="/api/admin/support/assignments/"+memberMessage,operatorDetail=memberCase+"?reader=OPERATOR";
        assertEquals(404,accountRequest(initial.browser(),"GET",operatorDetail,null,null).statusCode());
        assertFalse(readOk(initial.browser(),"/api/admin/support/queue").contains(number));
        post(initial.browser(),assign,Map.of("key",UUID.randomUUID(),"operator",initial.authority().member(),"expectedVersion",0),200);
        var detail=authMapper.readTree(readOk(initial.browser(),operatorDetail));
        assertEquals(200,accountRequest(initial.browser(),"GET",operatorFile,null,null).statusCode());
        assertEquals(number,detail.get("orderNumber").asText());
        assertEquals(sellerOrder.toString(),detail.get("sellerOrderId").asText());
        String followUp="판매자 답변을 확인했습니다. 원 주문의 배송 상태를 확인하겠습니다.";
        post(initial.browser(),memberCase+"/messages?reader=OPERATOR",Map.of("key",UUID.randomUUID(),"expectedVersion",detail.get("version").asLong(),"body",followUp),200);
        assertTrue(readOk(customer,memberCase).contains(followUp));
        post(initial.browser(),assign,Map.of("key",UUID.randomUUID(),"operator",successor.authority().member(),"expectedVersion",1),200);
        assertEquals(404,accountRequest(initial.browser(),"GET",operatorDetail,null,null).statusCode());
        assertEquals(404,accountRequest(initial.browser(),"GET",operatorFile,null,null).statusCode());
        assertEquals(200,accountRequest(successor.browser(),"GET",operatorFile,null,null).statusCode());
        assertTrue(readOk(successor.browser(),operatorDetail).contains(followUp));
        assertEquals(orderBefore,readOk(customer,orderPath));assertEquals(paymentBefore,readOk(customer,paymentPath));
    }
    @Test void lostIntakeAndReassignmentNeverDiscloseAnUnassignedBody() throws Exception {
        var visitor=authBrowser();
        var order=paymentFixture("b-lost",false);
        var key=UUID.randomUUID();
        String ack=post(visitor,"/api/support/lost-access",Map.of("key",key,"body",order.order().orderNumber()),200);
        assertEquals(ack,post(visitor,"/api/support/lost-access",Map.of("key",UUID.randomUUID(),"body","unknown-order"),200));
        assertEquals(1,authMapper.readTree(ack).size());
        var id=jdbc.queryForObject("SELECT id FROM marketplace.operations_support_case WHERE request_key=:key AND order_number IS NULL AND member_id IS NULL AND guest_id IS NULL",Map.of("key",key),UUID.class);
        var first=supportFixture("b-first","SUPPORT");
        var second=supportFixture("b-next","SUPPORT");
        String detail=CASES+"/"+id+"?reader=OPERATOR";
        assertEquals(404,accountRequest(first.browser(),"GET",detail,null,null).statusCode());
        var queue=accountRequest(first.browser(),"GET","/api/admin/support/queue",null,null);
        assertEquals(200,queue.statusCode());
        assertFalse(queue.body().contains(order.order().orderNumber()));
        String assignment="/api/admin/support/assignments/"+id;
        var claim=Map.of("key",UUID.randomUUID(),"operator",first.authority().member(),"expectedVersion",0);
        String saved=post(first.browser(),assignment,claim,200);
        assertEquals(saved,post(first.browser(),assignment,claim,200));
        assertTrue(accountRequest(first.browser(),"GET",detail,null,null).body().contains(order.order().orderNumber()));
        post(first.browser(),assignment,Map.of("key",UUID.randomUUID(),"operator",second.authority().member(),"expectedVersion",1),200);
        assertEquals(404,accountRequest(first.browser(),"GET",detail,null,null).statusCode());
        assertEquals(200,accountRequest(second.browser(),"GET",detail,null,null).statusCode());
        var raceKey=UUID.randomUUID();
        post(visitor,"/api/support/lost-access",Map.of("key",raceKey,"body","동시 배정"),200);
        UUID raceTarget=jdbc.queryForObject("SELECT id FROM marketplace.operations_support_case WHERE request_key=:key",Map.of("key",raceKey),UUID.class);
        raceClaim(raceTarget,0,java.util.List.of(first,second));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_assignment_event WHERE target_id=:id",Map.of("id",raceTarget),Integer.class));
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",second.authority().member()));
        var denied=accountRequest(second.browser(),"GET",detail,null,null);
        assertEquals(404,denied.statusCode());assertFalse(denied.body().contains(order.order().orderNumber()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_assignment_event WHERE target_id=:id",Map.of("id",id),Integer.class));
        var third=supportFixture("b-third","SUPPORT");
        var available=accountRequest(first.browser(),"GET","/api/admin/support/queue",null,null);
        assertEquals(200,available.statusCode());assertFalse(available.body().contains(order.order().orderNumber()));
        assertTrue(available.body().contains(id.toString()));
        raceClaim(id,2,java.util.List.of(first,third));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_assignment_event WHERE target_id=:id",Map.of("id",id),Integer.class));
        assertEquals(second.authority().member(),jdbc.queryForObject("SELECT previous_operator FROM marketplace.operations_assignment_event WHERE target_id=:id AND version=3",Map.of("id",id),UUID.class));
    }
    private void raceClaim(UUID raceTarget,long expected,java.util.List<SupportFixture> contendersList) throws Exception {
        var ready=new java.util.concurrent.CountDownLatch(2);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var contenders=contendersList.stream().map(agent->pool.submit(()->{
                String token=csrfToken(agent.browser());
                ready.countDown();
                if(!ready.await(10,java.util.concurrent.TimeUnit.SECONDS)){throw new IllegalStateException("second operator did not reach claim");}
                return accountRequest(agent.browser(),"POST","/api/admin/support/assignments/"+raceTarget,
                        Map.of("key",UUID.randomUUID(),"operator",agent.authority().member(),"expectedVersion",expected),token).statusCode();
            })).toList();
            var statuses=java.util.List.of(contenders.get(0).get(),contenders.get(1).get());
            assertTrue(statuses.contains(200) && statuses.contains(403),statuses.toString());
        }
    }
    @Test void orderCasesRequireCurrentMemberOrGasAndMatchingSellerOrder() throws Exception {
        var buyer=paymentFixture("b-order",false);
        var guest=cancellationGuest("b-gas");
        var visitor=authBrowser();
        var key=UUID.randomUUID();
        var memberBody=Map.of("key",key,"body","구매자 주문 문의","orderNumber",buyer.order().orderNumber(),"sellerOrderId",buyer.order().sellers().getFirst().id());
        String receipt=post(buyer.browser(),CASES,memberBody,200);
        assertEquals(receipt,post(buyer.browser(),CASES,memberBody,200));
        var storeOrder=buyer.order().sellers().getFirst();
        var seller=asSeller(buyer,storeOrder.id());
        var stores=context.getBean(com.example.marketplace.seller.api.Stores.class);
        var shop=stores.own(seller.authority().member());
        String memberCase=CASES+"/"+authMapper.readTree(receipt).path("id").asText();
        var admin=supportFixture("b-lifecycle","ACCOUNTS");
        UUID sku=storeOrder.items().getFirst().skuId();
        var browse=context.getBean(com.example.marketplace.catalog.api.Browse.class);
        assertTrue(browse.forSku(sku)!=null);
        jdbc.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=(SELECT product_id FROM marketplace.catalog_sku WHERE id=:sku)",Map.of("sku",sku));
        assertEquals(null,browse.forSku(sku));
        assertEquals(200,accountRequest(seller.browser(),"GET",memberCase+"?reader=SELLER",null,null).statusCode());
        var suspended=stores.transition(admin.authority().member(),shop.id(),"suspend",new com.example.marketplace.seller.api.Stores.Change(shop.version(),"기존 주문 판매 정지"));
        assertFalse(stores.capabilities(seller.authority().member()).newSales());
        post(seller.browser(),memberCase+"/messages?reader=SELLER",Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","판매 정지 후 주문 답변"),200);
        stores.transition(seller.authority().member(),shop.id(),"close",new com.example.marketplace.seller.api.Stores.Change(suspended.version(),"기존 주문 문의 응답 검증"));
        assertFalse(stores.capabilities(seller.authority().member()).newSales());
        var otherSeller=asSeller(buyer,buyer.order().sellers().get(1).id());
        String memberMessage=authMapper.readTree(receipt).path("id").asText();
        String images=memberCase+"/messages/"+memberMessage+"/images";
        byte[] pixel=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4XmNgYGD4D8UQBgAd9AP95BvM/wAAAABJRU5ErkJggg==");
        var attachment=upload(buyer.browser(),images+"/0?version=0&key="+UUID.randomUUID(),"member.png",pixel,csrfToken(buyer.browser()));
        assertEquals(200,attachment.statusCode(),attachment.body());
        String binary=images+"/"+authMapper.readTree(attachment.body()).path("asset").asText()+"?reader=SELLER";
        assertEquals(404,accountRequest(otherSeller.browser(),"GET",memberCase+"?reader=SELLER",null,null).statusCode());
        assertEquals(404,accountRequest(otherSeller.browser(),"GET",binary,null,null).statusCode());
        assertEquals(200,accountRequest(seller.browser(),"GET",binary,null,null).statusCode());
        assertTrue(accountRequest(seller.browser(),"GET",memberCase+"?reader=SELLER",null,null).body().contains("구매자 주문 문의"));
        post(seller.browser(),memberCase+"/messages?reader=SELLER",Map.of("key",UUID.randomUUID(),"expectedVersion",2,"body","폐점 후 기존 거래 답변"),200);
        assertTrue(accountRequest(buyer.browser(),"GET",memberCase,null,null).body().contains("폐점 후 기존 거래 답변"));
        verifyOrderHandoff(buyer.browser(),buyer.order().orderNumber(),storeOrder.id(),memberMessage,memberCase,binary.replace("reader=SELLER","reader=OPERATOR"));
        post(admin.browser(),"/api/admin/accounts/"+seller.authority().member()+"/suspend",Map.of("reason","판매자 계정 정지 후 비밀 접근 검증"),200);
        var suspendedRead=accountRequest(seller.browser(),"GET",memberCase+"?reader=SELLER",null,null);
        assertTrue(java.util.Set.of(401,403,404).contains(suspendedRead.statusCode()));
        assertFalse(suspendedRead.body().contains("구매자 주문 문의"));
        assertTrue(java.util.Set.of(401,403,404).contains(accountRequest(seller.browser(),"GET",binary,null,null).statusCode()));
        var blockedReply=accountRequest(seller.browser(),"POST",memberCase+"/messages?reader=SELLER",
                Map.of("key",UUID.randomUUID(),"expectedVersion",4,"body","정지 뒤 답변"),csrfToken(seller.browser()));
        assertTrue(java.util.Set.of(401,403,404).contains(blockedReply.statusCode()));
        verifyBlindIntake(seller.browser(),buyer.order().orderNumber());
        post(admin.browser(),"/api/admin/accounts/"+seller.authority().member()+"/restore",Map.of("reason","기존 문의 지원 복원 확인"),200);
        assertTrue(java.util.Set.of(401,403,404).contains(accountRequest(seller.browser(),"GET",binary,null,null).statusCode()));
        var renewed=authBrowser();
        String sellerEmail=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",seller.authority().member()),String.class);
        assertEquals(200,authRequest(renewed,"/api/auth/login",loginForm(sellerEmail,SIGNUP_PASSWORD),csrfToken(renewed)).statusCode());
        assertEquals(200,accountRequest(renewed,"GET",binary,null,null).statusCode());
        var guestBody=Map.of("key",UUID.randomUUID(),"body","비회원 주문 문의","orderNumber",guest.order().orderNumber(),"sellerOrderId",guest.order().sellers().getFirst().id());
        post(visitor,CASES,guestBody,404);
        post(visitor,"/api/guest/access/login",Map.of("orderNumber",guest.order().orderNumber(),"phone",guest.fixture().phone,"password",GUEST_PASSWORD),200);
        String guestReceipt=post(visitor,CASES,guestBody,200);
        String detail=CASES+"/"+authMapper.readTree(guestReceipt).path("id").asText();
        assertEquals(404,accountRequest(buyer.browser(),"GET",detail,null,null).statusCode());
        assertTrue(accountRequest(visitor,"GET",detail,null,null).body().contains("비회원 주문 문의"));
        post(visitor,CASES,memberBody,404);
        post(visitor,CASES,Map.of("key",UUID.randomUUID(),"body","변조","orderNumber",guest.order().orderNumber(),"sellerOrderId",buyer.order().sellers().getFirst().id()),404);
        var reply=Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","추가 문의");
        var saved=post(visitor,detail+"/messages",reply,200);
        assertEquals(saved,post(visitor,detail+"/messages",reply,200));
        post(visitor,detail+"/messages",Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","낡은 요청"),409);
        String imageBase=detail+"/messages/"+authMapper.readTree(guestReceipt).path("id").asText()+"/images";
        var data=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",data);
        var image=upload(visitor,imageBase+"/0?version=0&key="+UUID.randomUUID(),"guest.png",data.toByteArray(),csrfToken(visitor));
        assertEquals(200,image.statusCode(),image.body());
        String file=imageBase+"/"+authMapper.readTree(image.body()).path("asset").asText();
        assertEquals(200,accountRequest(visitor,"GET",file,null,null).statusCode());
        assertEquals(404,accountRequest(buyer.browser(),"GET",file,null,null).statusCode());
        var other=cancellationGuest("b-other");
        jdbc.update("UPDATE marketplace.guest_buyer SET verified_phone=:phone WHERE id=:buyer",
                Map.of("phone",com.example.marketplace.phone.api.PhoneVerifications.normalize(guest.fixture().phone),"buyer",other.order().guestBuyerId()));
        var otherBrowser=authBrowser();
        post(otherBrowser,"/api/guest/access/login",Map.of("orderNumber",other.order().orderNumber(),"phone",guest.fixture().phone,"password",GUEST_PASSWORD),200);
        for(String path:java.util.List.of(detail,file)){assertEquals(404,accountRequest(otherBrowser,"GET",path,null,null).statusCode());}
        var login=Map.of("orderNumber",guest.order().orderNumber(),"phone",guest.fixture().phone,"password",GUEST_PASSWORD);
        var stale=authBrowser();
        var cookies=((java.net.CookieManager)visitor.cookieHandler().orElseThrow()).getCookieStore();
        var old=cookies.getCookies().stream().filter(c->c.getName().equals("GUEST_ACCESS")).findFirst().orElseThrow();
        ((java.net.CookieManager)stale.cookieHandler().orElseThrow()).getCookieStore().add(java.net.URI.create("http://127.0.0.1:"+port),old);
        post(visitor,"/api/guest/access/login",login,200);
        for(String path:java.util.List.of(detail,file)){
            assertEquals(200,accountRequest(visitor,"GET",path,null,null).statusCode());
            assertEquals(404,accountRequest(stale,"GET",path,null,null).statusCode());
        }
        jdbc.update("UPDATE marketplace.guest_access_session SET idle_expires_at=clock_timestamp()-interval '1 second' WHERE guest_buyer_id=:guest AND revoked_at IS NULL",Map.of("guest",guest.order().guestBuyerId()));
        for(String path:java.util.List.of(detail,file)){assertEquals(404,accountRequest(visitor,"GET",path,null,null).statusCode());}
        post(visitor,"/api/guest/access/login",login,200);
        jdbc.update("UPDATE marketplace.guest_access_session SET revoked_at=clock_timestamp() WHERE guest_buyer_id=:guest AND revoked_at IS NULL",Map.of("guest",guest.order().guestBuyerId()));
        assertEquals(404,accountRequest(visitor,"GET",detail,null,null).statusCode());
        assertEquals(404,accountRequest(visitor,"GET",file,null,null).statusCode());
        verifyBlindIntake(visitor,guest.order().orderNumber());
    }
    private void verifyBlindIntake(HttpClient client,String number)throws Exception{
        int sessions=jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session",Map.of(),Integer.class);
        int members=jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user",Map.of(),Integer.class);
        var real=UUID.randomUUID();var missing=UUID.randomUUID();
        var one=post(client,"/api/support/lost-access",Map.of("key",real,"body",number),200);
        assertEquals(one,post(client,"/api/support/lost-access",Map.of("key",missing,"body","O00000000000000000000000000000000"),200));
        assertEquals(1,authMapper.readTree(one).size());
        assertFalse(one.contains(number));assertEquals(404,accountRequest(client,"GET","/api/orders/"+number,null,null).statusCode());
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM marketplace.operations_support_case WHERE request_key IN (:keys)
                AND order_number IS NULL AND member_id IS NULL AND guest_id IS NULL AND seller_order_id IS NULL
                """,Map.of("keys",List.of(real,missing)),Integer.class));
        assertEquals(sessions,jdbc.queryForObject("SELECT count(*) FROM marketplace.guest_access_session",Map.of(),Integer.class));
        assertEquals(members,jdbc.queryForObject("SELECT count(*) FROM marketplace.identity_user",Map.of(),Integer.class));
    }
    @Test void assignedSupportCollectsInspectsApprovesAndExecutesOnlyConfirmedOriginalUnits() throws Exception {
        var purchase=twoEqualUnits("support");
        var shipment=paidShipment(purchase);
        var currentWeek=com.example.marketplace.settlement.domain.SettlementRules.week(Instant.now());
        var boundary=com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek);
        var priorSunday=boundary.minusSeconds(60);
        completeShipment(purchase,shipment,priorSunday.minusSeconds(60));
        var confirmation=confirmationsWith(Clock.fixed(priorSunday,ZoneOffset.UTC),stage->{ });
        var number=purchase.order().orderNumber();
        var buyer=shipmentAuthority(purchase);
        var view=confirmation.get(number,buyer);
        var confirmed=confirmation.confirm(number,buyer,new OrderConfirmations.Command("both-confirmed",view.revision(),
                view.units().stream().map(OrderConfirmations.Eligibility::unit).toList()));
        var operator=supportFixture("p11b-op","SUPPORT");
        var opened=authMapper.readTree(post(purchase.browser(),"/api/support/cases",Map.of("key",UUID.randomUUID(),"orderNumber",purchase.order().orderNumber(),
                "sellerOrderId",shipment.sellerOrderId(),"body","구매확정 후 수령한 상품의 하자 문의"),200));
        String caseId=opened.path("id").asText();
        var assignment=context.getBean(com.example.marketplace.operations.api.SupportAssignments.class);
        var who=operator.authority();
        var assignee=new com.example.marketplace.operations.api.SupportAssignments.Actor(who.member(),who.browser(),who.version());
        var target=UUID.fromString(caseId);
        String path="/api/admin/support/cases/"+caseId+"/confirmed-refunds";
        assertTrue(List.of(403,404).contains(accountRequest(operator.browser(),"GET",path,null,null).statusCode()));
        var entry=assignment.queue(assignee).stream().filter(a->a.targetId().equals(target)).findFirst().orElseThrow();
        assignment.assign(target,assignee,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),entry.version(),who.member()));
        var other=supportFixture("p11b-other","SUPPORT");
        assertTrue(List.of(403,404).contains(accountRequest(other.browser(),"GET",path,null,null).statusCode()));
        var member=Map.of("order",purchase.order().id());
        var quantity=shipment.units().stream().filter(u->u.ordinal()==0).findFirst().orElseThrow();
        var params=new java.util.HashMap<String,Object>(Map.of("caseVersion",opened.path("version").asLong(),"item",quantity.orderItemId(),"ordinal",0));
        var previousResultJobs=new java.util.ArrayList<UUID>();
        for(int ordinal:List.of(0,1)) {
            params.put("ordinal",ordinal);
            UUID collection=UUID.randomUUID(),inspection=UUID.randomUUID(),approval=UUID.randomUUID();
            var missing=new java.util.HashMap<String,Object>(params);
            missing.put("key",UUID.randomUUID());missing.put("previous",inspection);missing.put("reason","missing inspection");
            post(operator.browser(),path+"/APPROVE",missing,409);
            params.put("key",collection);params.put("reference","운송장-"+ordinal);
            post(operator.browser(),path+"/COLLECT",params,200);
            params.put("key",inspection);params.put("previous",collection);params.put("reference","검수-"+ordinal);
            params.put("responsibility","SELLER");params.put("reason","회수한 상품의 생산 결함 확인");
            post(operator.browser(),path+"/INSPECT",params,200);
            params.remove("responsibility");params.remove("reference");params.put("key",approval);params.put("previous",inspection);
            params.put("reason","검수 기록에 따른 판매자 책임 환불 승인");
            post(operator.browser(),path+"/APPROVE",params,200);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:approval",Map.of("approval",approval),Integer.class));
            params.remove("reason");params.put("key",UUID.randomUUID());params.put("previous",approval);
            params.put("amount",1);post(operator.browser(),path+"/EXECUTE",params,400);params.remove("amount");
            if(ordinal==0) {
                var otherWho=other.authority();
                var nextActor=new com.example.marketplace.operations.api.SupportAssignments.Actor(otherWho.member(),otherWho.browser(),otherWho.version());
                for(boolean reassign:List.of(false,true)) {
                    var beforeCommand=supportFinancialSnapshot();
                    var immutable=Map.copyOf(params);
                    var csrf=csrfToken(operator.browser());
                    try(var lock=context.getBean(javax.sql.DataSource.class).getConnection();
                            var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                        lock.setAutoCommit(false);
                        try(var statement=lock.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) {
                            statement.setObject(1,purchase.order().id());try(var rows=statement.executeQuery()){assertTrue(rows.next());}
                        }
                        var pending=pool.submit(()->accountRequest(operator.browser(),"POST",path+"/EXECUTE",immutable,csrf).statusCode());
                        try {
                            awaitExchangeBusinessLock("purchase_order","confirmed-support-"+reassign);
                            if(reassign) {
                                assignment.assign(target,assignee,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),1,otherWho.member()));
                            }else {
                                assertEquals(1,jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id AND permission='SUPPORT'",Map.of("id",who.member())));
                            }
                        }finally { lock.commit(); }
                        assertEquals(reassign?404:403,pending.get(15,java.util.concurrent.TimeUnit.SECONDS));
                    }
                    assertEquals(beforeCommand,supportFinancialSnapshot());
                    if(reassign) {
                        assignment.assign(target,nextActor,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),2,who.member()));
                    }else {
                        jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) VALUES(:id,'SUPPORT')",Map.of("id",who.member()));
                    }
                }
                var before=supportFinancialSnapshot();
                var sql=jdbc.getJdbcTemplate();
                sql.execute("CREATE FUNCTION marketplace.test_abort_support_execution() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected execution failure';END $$");
                sql.execute("CREATE TRIGGER test_abort_support_execution BEFORE INSERT ON marketplace.claim_support_refund_execution FOR EACH ROW EXECUTE FUNCTION marketplace.test_abort_support_execution()");
                try { post(operator.browser(),path+"/EXECUTE",params,500); }
                finally { sql.execute("DROP TRIGGER test_abort_support_execution ON marketplace.claim_support_refund_execution");sql.execute("DROP FUNCTION marketplace.test_abort_support_execution()"); }
                assertEquals(before,supportFinancialSnapshot(),"failed final write rolls back reservation, binding, budget, job and outbox");
                var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
                var start=new java.util.concurrent.CountDownLatch(1);
                var immutable=Map.copyOf(params);
                try {
                    java.util.concurrent.Callable<String> execute=()->{start.await();return post(operator.browser(),path+"/EXECUTE",immutable,200);};
                    var a=pool.submit(execute);var b=pool.submit(execute);start.countDown();
                    assertEquals(a.get(),b.get());
                }finally { pool.shutdownNow(); }
                var conflict=new java.util.HashMap<>(params);conflict.put("key",UUID.randomUUID());
                post(operator.browser(),path+"/EXECUTE",conflict,409);
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_support_refund_execution WHERE approval_id=:id",Map.of("id",approval),Integer.class));
            }
            var result=authMapper.readTree(post(operator.browser(),path+"/EXECUTE",params,200));
            assertEquals(result,authMapper.readTree(post(operator.browser(),path+"/EXECUTE",params,200)));
            int index=ordinal;
            var row=result.valueStream().filter(r->r.path("ordinal").asInt()==index).findFirst().orElseThrow();
            assertEquals("10019",row.path("amount").asText());
            UUID attempt=UUID.fromString(row.path("attempt").asText());
            if(ordinal==0) {
                attempt=retrySupportFailure(operator.browser(),path,params,approval,attempt,"ITEM",previousResultJobs);
            }else {
                jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','LOST_RESPONSE')",Map.of("id",approval));
                runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
                assertEquals("UNKNOWN",authMapper.readTree(readOk(operator.browser(),path)).valueStream().filter(r->r.path("ordinal").asInt()==index).findFirst().orElseThrow().path("result").asText());
                assertEquals(10019L,jdbc.queryForObject("SELECT reserved FROM marketplace.payment_refund_budget WHERE order_id=:order",member,Long.class));
                var forbidden=new java.util.HashMap<>(params);forbidden.put("previous",attempt);forbidden.put("key",UUID.randomUUID());forbidden.put("reason","unknown");
                post(operator.browser(),path+"/RETRY_FAILED",forbidden,409);
                final UUID unknown=attempt;
                new TransactionTemplate(transactionManager).executeWithoutResult(t->context.getBean(com.example.marketplace.payment.api.Refunds.class).requestQuery(unknown,UUID.randomUUID(),"조회로 원 시도 결과 확인"));
            }
            runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
            assertEquals("SUCCEEDED",authMapper.readTree(readOk(operator.browser(),path)).valueStream().filter(r->r.path("ordinal").asInt()==index).findFirst().orElseThrow().path("result").asText());
            assertEquals(ordinal,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE order_id=:order",member,Integer.class));
            params.remove("previous");
        }
        UUID shipping=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE order_id=:order",member,UUID.class);
        UUID origin=jdbc.queryForObject("SELECT claim_id FROM marketplace.claim_shipping_obligation WHERE order_id=:order",member,UUID.class);
        int originOrdinal=jdbc.queryForObject("SELECT ordinal FROM marketplace.claim_support_refund_approval WHERE id=:id",Map.of("id",origin),Integer.class);
        params.put("ordinal",originOrdinal);
        shipping=retrySupportFailure(operator.browser(),path,params,origin,shipping,"SHIPPING",previousResultJobs);
        runCancellationJob("REFUND_SUBMIT.v1",shipping);runCancellationJob("REFUND_RESULT_RECORDED.v1",shipping);
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM marketplace.claim_shipping_obligation WHERE order_id=:order",member,String.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE confirmation_id=:id AND state='CONFIRMED'",Map.of("id",confirmed.receiptId()),Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE order_id=:order AND state='SUCCEEDED'",member,Integer.class));
        var latest=readOk(operator.browser(),path);
        var budget=jdbc.queryForMap("SELECT paid,reserved,succeeded FROM marketplace.payment_refund_budget WHERE order_id=:order",member);
        for(UUID old:previousResultJobs) { runStoredJob("REFUND_RESULT_RECORDED.v1",old); }
        assertEquals(latest,readOk(operator.browser(),path));
        assertEquals(budget,jdbc.queryForMap("SELECT paid,reserved,succeeded FROM marketplace.payment_refund_budget WHERE order_id=:order",member));
        var nextStart=com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek.plusWeeks(2));
        var laterStart=com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek.plusWeeks(3));
        var soleSku=purchase.order().sellers().getFirst().items().getFirst().skuId();
        var small=repeatPurchase(purchase,soleSku,1,"carry-small");
        var large=repeatPurchase(purchase,soleSku,2,"carry-large");
        var otherSale=twoEqualUnits("carry-other");
        var smallCause=paidAndConfirmedAt(small,nextStart.plusSeconds(600));
        var otherCause=paidAndConfirmedAt(otherSale,nextStart.plusSeconds(600));
        var largeCause=paidAndConfirmedAt(large,laterStart.plusSeconds(600));
        var admin=supportFixture("p11b-carry","SETTLEMENTS").authority();
        var actor=new Settlements.Actor(admin.member(),admin.browser(),admin.version());
        var service=context.getBean(Settlements.class);
        var sellerActor=new Settlements.Actor(buyer.member(),buyer.browser(),buyer.version());
        var sellerRead=context.getBean(com.example.marketplace.settlement.infrastructure.SellerSettlementRead.class);
        var testClock=(SettlementClock)clock;
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{
            testClock.at=boundary;
            var priorWeek=currentWeek.minusWeeks(1);
            Object beforeDraft=t.createSavepoint();
            var produced=service.open(new Settlements.Open(UUID.randomUUID(),priorWeek),actor);
            var id=Map.of("id",produced.id());
            String ledger=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(l))::text FROM marketplace.settlement_ledger l JOIN marketplace.settlement_batch_item i ON i.ledger_id=l.id WHERE i.batch_id=:id",id,String.class);
            String members=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(i))::text FROM marketplace.settlement_batch_item i WHERE batch_id=:id",id,String.class);
            t.rollbackToSavepoint(beforeDraft);
            t.releaseSavepoint(beforeDraft);
            jdbc.update("INSERT INTO marketplace.settlement_ledger SELECT * FROM jsonb_populate_recordset(NULL::marketplace.settlement_ledger,CAST(:rows AS jsonb)) ON CONFLICT(id) DO NOTHING",Map.of("rows",ledger));
            jdbc.update("INSERT INTO marketplace.settlement_batch(id,week_start,period_start,period_end,created_by,state,readiness_version) VALUES(:id,:week,:start,:end,:actor,'DRAFT',1)",Map.of("id",produced.id(),"week",java.sql.Date.valueOf(priorWeek),"start",java.sql.Timestamp.from(produced.periodStart()),"end",java.sql.Timestamp.from(produced.periodEnd()),"actor",actor.member()));
            jdbc.update("INSERT INTO marketplace.settlement_batch_item SELECT * FROM jsonb_populate_recordset(NULL::marketplace.settlement_batch_item,CAST(:rows AS jsonb))",Map.of("rows",members));
            jdbc.update("UPDATE marketplace.settlement_batch SET gross=:gross,fee=:fee,net=:net,version=version+1 WHERE id=:id",Map.of("id",produced.id(),"gross",Long.parseLong(produced.gross()),"fee",Long.parseLong(produced.fee()),"net",Long.parseLong(produced.net())));
            jdbc.getJdbcTemplate().execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.getJdbcTemplate().execute("SET CONSTRAINTS ALL DEFERRED");
            var legacy=service.get(produced.id(),actor);
            assertEquals(1,jdbc.queryForObject("SELECT readiness_version FROM marketplace.settlement_batch WHERE id=:id",id,Integer.class));
            assertTrue(legacy.balances().isEmpty());
            var prior=service.open(new Settlements.Open(UUID.randomUUID(),priorWeek),actor);
            assertEquals(2,jdbc.queryForObject("SELECT readiness_version FROM marketplace.settlement_batch WHERE id=:id",id,Integer.class));
            assertTrue(prior.version()>legacy.version());
            assertEquals(legacy.items(),prior.items());
            assertEquals(1,jdbc.queryForObject("SELECT readiness_version FROM marketplace.settlement_batch_revision WHERE batch_id=:id AND version=:version",Map.of("id",prior.id(),"version",legacy.version()),Integer.class));
            var reviewed=service.review(prior.id(),new Settlements.Change(UUID.randomUUID(),prior.version()),actor);
            var paid=service.simulatePay(prior.id(),new Settlements.Change(UUID.randomUUID(),reviewed.version()),actor);
            assertEquals(2,paid.items().stream().filter(i->i.sellerOrderId().equals(shipment.sellerOrderId())&&i.kind().equals("PRODUCT")).count());
            testClock.at=com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek.plusWeeks(1));
            var adjustment=service.open(new Settlements.Open(UUID.randomUUID(),currentWeek),actor);
            var mine=adjustment.items().stream().filter(i->i.sellerOrderId().equals(shipment.sellerOrderId())).toList();
            assertEquals(List.of("-10019","-10019"),mine.stream().filter(i->i.kind().equals("REFUND")).map(Settlements.Item::amount).toList());
            assertEquals(List.of("-500","-501"),mine.stream().filter(i->i.kind().equals("COMMISSION_REVERSAL")).map(Settlements.Item::fee).sorted().toList());
            assertEquals(1,mine.stream().filter(i->i.kind().equals("SHIPPING_REFUND")).count());
            var checked=service.review(adjustment.id(),new Settlements.Change(UUID.randomUUID(),adjustment.version()),actor);
            service.simulatePay(checked.id(),new Settlements.Change(UUID.randomUUID(),checked.version()),actor);
            var mineWeek=sellerRead.read(sellerActor).stream().filter(w->w.weekStart().equals(currentWeek)).findFirst().orElseThrow();
            assertEquals("0",mineWeek.payout());
            assertEquals(Long.toString(-mine.stream().mapToLong(i->Long.parseLong(i.net())).sum()),mineWeek.closing());
            testClock.at=com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek.plusWeeks(2));
            service.open(new Settlements.Open(UUID.randomUUID(),currentWeek.plusWeeks(1)),actor);
            var quiet=sellerRead.read(sellerActor).stream().filter(w->w.weekStart().equals(currentWeek.plusWeeks(1))).findFirst().orElseThrow();
            assertEquals(mineWeek.closing(),quiet.opening());assertEquals(quiet.opening(),quiet.closing());assertEquals("0",quiet.payout());
            var store=purchase.order().sellers().getFirst().storeId();
            var foreignStore=otherSale.order().sellers().getFirst().storeId();
            assertFalse(store.equals(foreignStore));
            var partial=settleWeek(service,actor,testClock,currentWeek.plusWeeks(2));
            assertTrue(partial.items().stream().anyMatch(i->i.storeId().equals(store)&&i.causeId().equals(smallCause)));
            assertTrue(partial.items().stream().anyMatch(i->i.storeId().equals(foreignStore)&&i.causeId().equals(otherCause)));
            var local=partial.balances().stream().filter(b->b.storeId().equals(store)).findFirst().orElseThrow();
            var foreign=partial.balances().stream().filter(b->b.storeId().equals(foreignStore)).findFirst().orElseThrow();
            assertEquals(mineWeek.closing(),local.opening());
            long unpaid=Long.parseLong(local.opening())-Long.parseLong(local.net());
            assertTrue(unpaid>0 && Long.parseLong(local.net())>0);
            assertEquals(Long.toString(unpaid),local.closing());assertEquals("0",local.payout());
            assertTrue(Long.parseLong(foreign.net())>0);
            assertEquals("0",foreign.opening());assertEquals("0",foreign.closing());assertEquals(foreign.net(),foreign.payout());
            var excess=settleWeek(service,actor,testClock,currentWeek.plusWeeks(3));
            assertTrue(excess.items().stream().anyMatch(i->i.storeId().equals(store)&&i.causeId().equals(largeCause)));
            var after=excess.balances().stream().filter(b->b.storeId().equals(store)).findFirst().orElseThrow();
            assertEquals(local.closing(),after.opening());
            long payable=Long.parseLong(after.net())-unpaid;
            assertTrue(payable>0);assertEquals(Long.toString(payable),after.payout());assertEquals("0",after.closing());
            assertEquals(paid,service.get(prior.id(),actor));
            jdbc.getJdbcTemplate().execute("SET CONSTRAINTS ALL IMMEDIATE");
            t.setRollbackOnly();
        });
    }
    private PaymentFixture repeatPurchase(PaymentFixture source,UUID sku,int quantity,String key) {
        var member=source.authority().member();
        var lines=List.of(quoteLine(sku,quantity));
        var quote=quotes().calculate(member,lines,List.of());
        var order=creation().create(source.authority().browser(),member,null,new com.example.marketplace.order.infrastructure.OrderCreationService.Input(
                key,lines,List.of(),quote.fingerprint(),source.order().contact(),null,0));
        return new PaymentFixture(order,source.browser(),source.authority());
    }
    private UUID paidAndConfirmedAt(PaymentFixture purchase,Instant at) {
        var shipment=paidShipment(purchase);
        completeShipment(purchase,shipment,at.minusSeconds(60));
        var api=confirmationsWith(Clock.fixed(at,ZoneOffset.UTC),stage->{ });
        var authority=shipmentAuthority(purchase);
        var number=purchase.order().orderNumber();
        var view=api.get(number,authority);
        return api.confirm(number,authority,new OrderConfirmations.Command("carry-confirm",view.revision(),
                view.units().stream().map(OrderConfirmations.Eligibility::unit).toList())).receiptId();
    }
    private Settlements.Batch settleWeek(Settlements service,Settlements.Actor actor,SettlementClock testClock,java.time.LocalDate week) {
        testClock.at=com.example.marketplace.settlement.domain.SettlementRules.start(week.plusWeeks(1));
        var draft=service.open(new Settlements.Open(UUID.randomUUID(),week),actor);
        var reviewed=service.review(draft.id(),new Settlements.Change(UUID.randomUUID(),draft.version()),actor);
        return service.simulatePay(draft.id(),new Settlements.Change(UUID.randomUUID(),reviewed.version()),actor);
    }
    private Map<String,String> supportFinancialSnapshot() {
        var state=new java.util.TreeMap<>(cancellationParticipants());
        for(String table:List.of("claim_support_collection","claim_support_inspection","claim_support_refund_approval","claim_support_refund_execution","claim_support_refund_result","claim_support_refund_retry","payment_refund_budget","payment_refund_attempt","payment_refund_event","job","outbox_event")) {
            state.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));
        }
        return state;
    }
    @Test void supportReplyWrongOriginalAndBuyerInspectionDoNotAuthorizeMoney() throws Exception {
        var purchase=twoEqualUnits("denied");var shipment=paidShipment(purchase);
        completeShipment(purchase,shipment,Instant.now());
        var owner=shipmentAuthority(purchase);String number=purchase.order().orderNumber();
        var source=confirmations().get(number,owner);
        confirmations().confirm(number,owner,new OrderConfirmations.Command("confirmed-denied",source.revision(),
                source.units().stream().map(OrderConfirmations.Eligibility::unit).toList()));
        var foreign=twoEqualUnits("foreign");
        var operator=supportFixture("b-denied","SUPPORT");
        var a=operator.authority();var actor=new com.example.marketplace.operations.api.SupportAssignments.Actor(a.member(),a.browser(),a.version());
        var tickets=context.getBean(com.example.marketplace.operations.api.SupportAssignments.class);
        var inquiry=authMapper.readTree(post(purchase.browser(),CASES,Map.of("key",UUID.randomUUID(),"orderNumber",number,"sellerOrderId",shipment.sellerOrderId(),"body","검수 책임 확인"),200));
        UUID id=UUID.fromString(inquiry.path("id").asText());String path="/api/admin/support/cases/"+id+"/confirmed-refunds/";
        var target=tickets.queue(actor).stream().filter(e->e.targetId().equals(id)).findFirst().orElseThrow();
        tickets.assign(id,actor,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),target.version(),a.member()));
        String details=CASES+"/"+id;
        long version=inquiry.path("version").asLong();
        var before=supportFinancialSnapshot();
        var response=authMapper.readTree(post(operator.browser(),details+"/messages?reader=OPERATOR",Map.of("key",UUID.randomUUID(),"expectedVersion",version,"body","배송비와 환불 요청을 확인했습니다."),200));
        assertEquals(before,supportFinancialSnapshot());
        version=authMapper.readTree(readOk(operator.browser(),details+"?reader=OPERATOR")).path("version").asLong();
        assertEquals(version,response.path("version").asLong());
        assertTrue(version>inquiry.path("version").asLong());
        var unit=shipment.units().getFirst();
        var cmd=new java.util.HashMap<String,Object>(Map.of("caseVersion",version,"key",UUID.randomUUID(),"item",foreign.order().sellers().getFirst().items().getFirst().id(),"ordinal",0,"reference","타 주문 수량"));
        var denied=accountRequest(operator.browser(),"POST",path+"COLLECT",cmd,csrfToken(operator.browser()));
        assertTrue(List.of(404,409).contains(denied.statusCode()));
        assertEquals(before,supportFinancialSnapshot());
        UUID collection=UUID.randomUUID(),inspection=UUID.randomUUID();
        cmd.put("key",collection);cmd.put("item",unit.orderItemId());cmd.put("ordinal",unit.ordinal());cmd.put("reference","실제 회수 확인");
        post(operator.browser(),path+"COLLECT",cmd,200);
        cmd.put("key",inspection);cmd.put("previous",collection);cmd.put("responsibility","BUYER");cmd.put("reason","구매자 훼손 확인");cmd.put("reference","검수 기록");
        post(operator.browser(),path+"INSPECT",cmd,200);
        cmd.remove("reference");cmd.remove("responsibility");cmd.put("previous",inspection);cmd.put("key",UUID.randomUUID());
        var inspected=supportFinancialSnapshot();
        post(operator.browser(),path+"APPROVE",cmd,409);
        var other=authMapper.readTree(post(purchase.browser(),CASES,Map.of("key",UUID.randomUUID(),"orderNumber",number,"sellerOrderId",shipment.sellerOrderId(),"body","같은 주문의 별도 문의"),200));
        UUID secondId=UUID.fromString(other.path("id").asText());
        tickets.assign(secondId,actor,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),0,a.member()));
        cmd.put("caseVersion",other.path("version").asLong());cmd.put("key",UUID.randomUUID());cmd.put("previous",collection);
        cmd.put("reference","다른 문의 회수 기록");cmd.put("responsibility","SELLER");
        post(operator.browser(),"/api/admin/support/cases/"+secondId+"/confirmed-refunds/INSPECT",cmd,409);
        assertEquals(inspected,supportFinancialSnapshot());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE order_id=:id",Map.of("id",purchase.order().id()),Integer.class));
    }
    @Test void confirmedDefectAndSellerReturnOrExchangeShareOneShippingRefundInEitherOrder() throws Exception {
        for(boolean exchange:List.of(false,true)) {
            for(boolean supportFirst:List.of(false,true)) {
                String key="bmx"+(exchange?"e":"r")+(supportFirst?"s":"d");
                var parties=returnParties(key,true,2,"5000");
                var buyer=parties.buyer();var seller=parties.shipment().sellerOrderId();
                var physical=parties.shipment().units().getFirst();
                var approvedUnit=parties.shipment().units().stream().filter(u->!u.equals(physical)).findFirst().orElseThrow();
                var owner=shipmentAuthority(buyer);String number=buyer.order().orderNumber();
                var eligibility=confirmations().get(number,owner);
                boolean payBetween=!exchange&&!supportFirst;
                var w1=com.example.marketplace.settlement.domain.SettlementRules.week(Instant.now()).plusWeeks(12);
                var w2=w1.plusWeeks(1);
                var confirmApi=payBetween?confirmationsWith(Clock.fixed(com.example.marketplace.settlement.domain.SettlementRules.start(w1).plusSeconds(600),ZoneOffset.UTC),stage->{ }):confirmations();
                var confirmed=confirmApi.confirm(number,owner,new OrderConfirmations.Command(key,eligibility.revision(),
                        List.of(new OrderConfirmations.Unit(approvedUnit.orderItemId(),approvedUnit.ordinal()))));
                var operator=supportFixture(key,"SUPPORT");
                var ticket=authMapper.readTree(post(buyer.browser(),CASES,Map.of("key",UUID.randomUUID(),"orderNumber",number,"sellerOrderId",seller,"body","확정 수량 하자 문의"),200));
                UUID inquiry=UUID.fromString(ticket.path("id").asText());
                var authority=operator.authority();
                var actor=new com.example.marketplace.operations.api.SupportAssignments.Actor(authority.member(),authority.browser(),authority.version());
                var assignments=context.getBean(com.example.marketplace.operations.api.SupportAssignments.class);
                var target=assignments.queue(actor).stream().filter(e->e.targetId().equals(inquiry)).findFirst().orElseThrow();
                assignments.assign(inquiry,actor,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),target.version(),authority.member()));
                String base="/api/admin/support/cases/"+inquiry+"/confirmed-refunds/";
                var cmd=new java.util.HashMap<String,Object>();
                cmd.put("caseVersion",ticket.path("version").asLong());cmd.put("item",approvedUnit.orderItemId());cmd.put("ordinal",approvedUnit.ordinal());
                UUID collect=UUID.randomUUID(),inspect=UUID.randomUUID(),approval=UUID.randomUUID();
                cmd.put("key",collect);cmd.put("reference","회수 "+key);post(operator.browser(),base+"COLLECT",cmd,200);
                cmd.put("key",inspect);cmd.put("previous",collect);cmd.put("responsibility","SELLER");cmd.put("reason","실제 검수에서 하자 확인");
                post(operator.browser(),base+"INSPECT",cmd,200);
                cmd.remove("reference");cmd.remove("responsibility");cmd.put("key",approval);cmd.put("previous",inspect);
                post(operator.browser(),base+"APPROVE",cmd,200);
                cmd.remove("reason");cmd.put("previous",approval);cmd.put("key",UUID.randomUUID());
                var ids=Map.of("id",seller);
                var settlements=context.getBean(Settlements.class);
                Settlements.Actor finance=null;
                Settlements.Batch paid=null;
                String originalRow=null;
                if(payBetween) {
                    var officer=supportFixture("bmx-ledger","SETTLEMENTS").authority();
                    finance=new Settlements.Actor(officer.member(),officer.browser(),officer.version());
                    supportAttemptWeek(approval,com.example.marketplace.settlement.domain.SettlementRules.start(w2).plusSeconds(600));
                }
                for(boolean support:List.of(supportFirst,!supportFirst)) {
                    if(support) {
                        var result=authMapper.readTree(post(operator.browser(),base+"EXECUTE",cmd,200));
                        UUID attempt=UUID.fromString(result.get(0).path("attempt").asText());
                        runCancellationJob("REFUND_SUBMIT.v1",attempt);
                        runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
                    }else if(exchange) {
                        settlePartialExchangeRefund(inspectedPartialExchange(parties,key));
                    }else {
                        settleSellerReturnItem(recoveryPhysicalUnit(parties,physical,key));
                    }
                    runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);
                    if(payBetween&&!support) {
                        paid=settleWeek(settlements,finance,(SettlementClock)clock,w1);
                        var charges=paid.items().stream().filter(i->i.sellerOrderId().equals(seller)&&i.kind().equals("SHIPPING")).toList();
                        assertEquals(1,charges.size());assertEquals("3000",charges.getFirst().amount());
                        assertEquals(seller,charges.getFirst().causeId());
                        assertEquals(w1,com.example.marketplace.settlement.domain.SettlementRules.week(charges.getFirst().occurredAt()));
                        originalRow=shippingLedgerRow(seller);
                    }
                    assertEquals(support==supportFirst?0:1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",ids,Integer.class));
                }
                UUID shipping=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE seller_order_id=:id",ids,UUID.class);
                runCancellationJob("REFUND_SUBMIT.v1",shipping);runCancellationJob("REFUND_RESULT_RECORDED.v1",shipping);
                runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",seller);
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE seller_order_id=:id AND purpose='SHIPPING' AND state='SUCCEEDED'",ids,Integer.class));
                assertEquals(1,jdbc.queryForObject("""
                        SELECT count(*) FROM marketplace.order_aftercare_shipping s
                        JOIN marketplace.claim_support_refund_result r ON r.attempt_id=s.refund_attempt_id AND r.approval_id=s.cause_id
                        WHERE s.seller_order_id=:id AND s.refund_attempt_id=:attempt AND s.amount=r.amount
                        AND r.state='SUCCEEDED' AND r.purpose='SHIPPING' AND r.reference=s.reference
                        """,Map.of("id",seller,"attempt",shipping),Integer.class),key);
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE confirmation_id=:id AND state='CONFIRMED'",Map.of("id",confirmed.receiptId()),Integer.class));
                assertEquals("CLOSED",orders().find(number).state());
                if(payBetween) {
                    var adjusted=settleWeek(settlements,finance,(SettlementClock)clock,w2);
                    var lines=adjusted.items().stream().filter(i->i.sellerOrderId().equals(seller)).toList();
                    assertEquals(List.of("COMMISSION_REVERSAL","REFUND","SHIPPING_REFUND"),lines.stream().map(Settlements.Item::kind).sorted().toList());
                    var reverse=lines.stream().filter(i->i.kind().equals("SHIPPING_REFUND")).findFirst().orElseThrow();
                    assertEquals(shipping,reverse.refundAttemptId());assertEquals(approval,reverse.causeId());assertEquals("-3000",reverse.amount());
                    assertEquals(w2,com.example.marketplace.settlement.domain.SettlementRules.week(reverse.occurredAt()));
                    assertEquals(originalRow,shippingLedgerRow(seller));
                    assertEquals(paid,settlements.get(paid.id(),finance));
                }
            }
        }
    }
    private String shippingLedgerRow(UUID seller) {
        return jdbc.queryForObject("""
                SELECT to_jsonb(l)::text FROM marketplace.settlement_ledger l
                WHERE l.seller_order_id=:seller AND l.kind='SHIPPING'
                """,Map.of("seller",seller),String.class);
    }
    // Control only the two observations for this approval in the isolated DB; use the real
    // payment, outbox, result and settlement handlers to cross a week without a wall-clock wait.
    private void supportAttemptWeek(UUID approval,Instant at) {
        var sql=jdbc.getJdbcTemplate();
        sql.execute("CREATE TABLE marketplace.test_support_week(approval uuid PRIMARY KEY,observed_at timestamptz NOT NULL)");
        jdbc.update("INSERT INTO marketplace.test_support_week VALUES(:approval,:at)",Map.of("approval",approval,"at",java.sql.Timestamp.from(at)));
        sql.execute("""
                CREATE FUNCTION marketplace.test_support_week_time() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE observed timestamptz;
                BEGIN
                 SELECT observed_at INTO observed FROM marketplace.test_support_week WHERE approval=NEW.claim_id;
                 IF FOUND THEN
                  IF TG_OP='INSERT' THEN NEW.created_at=observed;
                  ELSIF NEW.state='SUCCEEDED' THEN NEW.result_at=observed+interval '1 minute';END IF;
                 END IF;
                 RETURN NEW;
                END $$;
                """);
        sql.execute("CREATE TRIGGER test_support_week_time BEFORE INSERT OR UPDATE OF state ON marketplace.payment_refund_attempt FOR EACH ROW EXECUTE FUNCTION marketplace.test_support_week_time()");
    }
    private UUID retrySupportFailure(HttpClient operator,String path,Map<String,Object> unit,UUID approval,UUID previous,String purpose,List<UUID> previousJobs) throws Exception {
        var bind=Map.of("id",approval,"purpose",purpose);
        jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,:purpose,'FAILED')",bind);
        runCancellationJob("REFUND_SUBMIT.v1",previous);
        UUID resultJob=jdbc.queryForObject("SELECT id FROM marketplace.job WHERE type='REFUND_RESULT_RECORDED.v1' AND cause_key=:attempt AND status='READY'",Map.of("attempt",previous.toString()),UUID.class);
        var owned=claimActualReturnJob(resultJob);
        assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,context.getBean(com.example.marketplace.claim.infrastructure.JdbcFinancialResults.class).result(owned.job()));
        previousJobs.add(resultJob);
        var command=Map.<String,Object>of("item",unit.get("item"),"ordinal",unit.get("ordinal"),"key",UUID.randomUUID(),
                "caseVersion",unit.get("caseVersion"),"previous",previous,"reason","기존 확정 실패 결과와 승인 근거 확인");
        var retry=post(operator,path+"/RETRY_FAILED",command,200);
        assertEquals(retry,post(operator,path+"/RETRY_FAILED",command,200));
        var next=jdbc.queryForObject("SELECT attempt_id FROM marketplace.claim_support_refund_retry WHERE prior_attempt=:old",Map.of("old",previous),UUID.class);
        assertFalse(previous.equals(next));
        assertEquals(2,jdbc.queryForObject("SELECT sequence FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",next),Integer.class));
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",previous),String.class));
        jdbc.update("UPDATE marketplace.payment_refund_mock_setting SET behavior='SUCCESS' WHERE claim_id=:id AND purpose=:purpose",bind);
        return next;
    }
}
