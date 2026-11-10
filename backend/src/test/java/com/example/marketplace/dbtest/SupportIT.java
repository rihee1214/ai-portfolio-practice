package com.example.marketplace.dbtest;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportIT extends PostgresContractFixture {
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
}
