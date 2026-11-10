package com.example.marketplace.dbtest;

import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.seller.api.Stores;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsIT extends PostgresContractFixture {
    private String post(HttpClient client,String path,Object command,int status)throws Exception{
        var response=accountRequest(client,"POST",path,command,csrfToken(client));
        assertEquals(status,response.statusCode(),path+" "+response.body());return response.body();
    }
    private String get(HttpClient client,String path,int status)throws Exception{
        var response=accountRequest(client,"GET",path,null,null);
        assertEquals(status,response.statusCode(),path);return response.body();
    }
    @Test void restrictedSellerShipsAndHandlesOnlyItsExistingReturnAndOrderInquiry()throws Exception{
        var admin=supportFixture("e-store-admin","ACCOUNTS");
        for(boolean close:List.of(false,true)){
            var customer=paymentFixture(close?"e-closed":"e-stopped",false);
            var foreignShipment=paidShipment(customer);
            var lower=customer.order().sellers().stream().filter(s->!s.id().equals(foreignShipment.sellerOrderId())).findFirst().orElseThrow();
            var seller=asSeller(customer,lower.id());String number=customer.order().orderNumber();
            assertFalse(seller.authority().member().equals(customer.authority().member()));
            var stores=context.getBean(Stores.class);var store=stores.own(seller.authority().member());
            String inquiry=authMapper.readTree(post(customer.browser(),"/api/support/cases",
                    Map.of("key",UUID.randomUUID(),"body","기존 주문 문의","orderNumber",number,"sellerOrderId",lower.id()),200)).path("id").asText();
            var authority=close?seller.browser():admin.browser();
            String transition=close?"/api/seller/stores/"+store.id()+"/close":"/api/admin/accounts/stores/"+store.id()+"/suspend";
            var change=Map.of("version",store.version(),"reason","실제 기존 거래 경계");
            post(authority,transition,change,200);post(authority,transition,change,409);
            assertEquals(close?"CLOSED":"SUSPENDED",stores.own(seller.authority().member()).state());
            assertEquals(1,stores.history(admin.authority().member(),store.id(),true).stream()
                    .filter(event->event.reason().equals("실제 기존 거래 경계")).count());
            assertFalse(stores.capabilities(seller.authority().member()).newSales());
            assertTrue(stores.capabilities(seller.authority().member()).existingTrades());
            var sku=lower.items().getFirst().skuId();
            post(customer.browser(),"/api/quotes",Map.of("lines",List.of(Map.of("skuId",sku,"quantity",1)),"coupons",List.of()),409);
            var ship=shipments().seller(lower.id(),shipmentAuthority(seller)).getFirst();
            String shipping="/api/seller/shipments/"+ship.id()+"/ship";
            get(seller.browser(),"/api/seller/orders/"+foreignShipment.sellerOrderId()+"/shipments",404);
            post(seller.browser(),"/api/seller/shipments/"+foreignShipment.id()+"/ship",shipBody(foreignShipment,"foreign-lower"),404);
            post(customer.browser(),shipping,shipBody(ship,"foreign-ship"),404);
            post(seller.browser(),shipping,shipBody(ship,"ship-"+ship.id()),200);
            Instant now=Instant.now();
            shipments().progress(ship.id(),"transit-"+ship.id(),"IN_TRANSIT",1,now.minusSeconds(1));
            shipments().progress(ship.id(),"done-"+ship.id(),"DELIVERED",2,now);
            String messages="/api/support/cases/"+inquiry+"/messages?reader=SELLER";
            var reply=Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","기존 배송과 반품을 확인합니다.");
            post(customer.browser(),messages,reply,404);post(seller.browser(),messages,reply,200);
            assertTrue(get(customer.browser(),"/api/support/cases/"+inquiry,200).contains("기존 배송과 반품"));
            var accepted=context.getBean(Returns.class).request(number,shipmentAuthority(customer),returnRequest(customer,ship,"e-return-"+close));
            String approve="/api/seller/returns/"+accepted.returnId()+"/approve";
            var command=Map.of("key","e-approve-"+close,"expectedRevision",0,"reason","판매 정지 중 기존 반품 처리");
            post(customer.browser(),approve,command,404);
            assertEquals("APPROVED",authMapper.readTree(post(seller.browser(),approve,command,200)).path("state").asText());
            var unit=ship.units().getFirst();String returns="/api/seller/returns/"+accepted.returnId();
            var receive=Map.of("key","e-receive","expectedRevision",1,"reason","기존 거래 수령","units",
                    List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal(),"status","RECEIVED")));
            post(customer.browser(),returns+"/receive",receive,404);post(seller.browser(),returns+"/receive",receive,200);
            var inspect=Map.of("key","e-inspect","expectedRevision",2,"reason","기존 거래 검수","units",
                    List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal(),"condition","SALEABLE","responsibility","BUYER")));
            post(customer.browser(),returns+"/inspect",inspect,404);post(seller.browser(),returns+"/inspect",inspect,200);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
            if(!close){
                String restore="/api/admin/accounts/stores/"+store.id()+"/restore";
                post(admin.browser(),restore,change,409);
                post(admin.browser(),restore,Map.of("version",store.version()+1,"reason","기존 매대 복구"),200);
                assertEquals("OPEN",stores.own(seller.authority().member()).state());
                assertFalse(stores.own(seller.authority().member()).visible());
            }
        }
    }
    @Test void supportHandoffCannotReleaseUnknownMoneyOrInvokeSystemReconciliation() throws Exception {
        var f=paymentFixture("e-system-support",false);var shipment=paidShipment(f);
        var unit=shipment.units().getFirst();var accepted=acceptCancellation(f,List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal())),"e_unknown");
        UUID attempt=UUID.fromString(accepted.path("refundAttemptId").asText()),claim=UUID.fromString(accepted.path("claimId").asText());
        jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','UNKNOWN')",Map.of("id",claim));
        runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
        var support=supportFixture("e-money-support","SUPPORT");var next=supportFixture("e-next-support","SUPPORT");var system=supportFixture("e-system","SYSTEM");
        var p=Map.of("id",attempt,"order",f.order().id());
        String sql="""
                SELECT a.state,a.amount,b.reserved,b.succeeded,m.submit_calls,m.query_calls,m.effects FROM marketplace.payment_refund_attempt a
                JOIN marketplace.payment_refund_budget b ON b.order_id=a.order_id JOIN marketplace.payment_refund_mock m ON m.attempt_id=a.id
                WHERE a.id=:id AND a.order_id=:order
                """;
        var before=jdbc.queryForMap(sql,p);
        assertEquals("UNKNOWN",before.get("state"));assertEquals(before.get("amount"),before.get("reserved"));
        assertEquals(1,((Number)before.get("submit_calls")).intValue());
        String order="/api/orders/"+f.order().orderNumber();String snapshot=get(f.browser(),order,200);
        var lower=f.order().sellers().stream().filter(s->s.items().stream().anyMatch(i->i.id().equals(unit.orderItemId()))).findFirst().orElseThrow();
        String ticket=authMapper.readTree(post(f.browser(),"/api/support/cases",Map.of("key",UUID.randomUUID(),"body","환불 상태 문의",
                "orderNumber",f.order().orderNumber(),"sellerOrderId",lower.id()),200)).path("id").asText();
        String detail="/api/support/cases/"+ticket,assignment="/api/admin/support/assignments/"+ticket;
        post(support.browser(),assignment,Map.of("key",UUID.randomUUID(),"expectedVersion",0,"operator",support.authority().member()),200);
        var imagePath=detail+"/messages/"+ticket+"/images";
        var upload=upload(f.browser(),imagePath+"/0?version=0&key="+UUID.randomUUID(),"proof.png",mediaPng(),csrfToken(f.browser()));
        assertEquals(200,upload.statusCode(),upload.body());
        String file=imagePath+"/"+authMapper.readTree(upload.body()).get("asset").asText()+"?reader=OPERATOR";
        get(system.browser(),detail+"?reader=OPERATOR",404);get(system.browser(),file,404);get(support.browser(),file,200);
        String note="결과 미확정. 동일 시도의 조회가 필요합니다.";
        post(support.browser(),detail+"/messages?reader=OPERATOR",Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body",note),200);
        post(support.browser(),assignment,Map.of("key",UUID.randomUUID(),"expectedVersion",1,"operator",next.authority().member()),200);
        get(support.browser(),file,404);get(next.browser(),file,200);
        assertTrue(get(next.browser(),detail+"?reader=OPERATOR",200).contains(note));assertTrue(get(f.browser(),detail,200).contains(note));
        String systemPath="/api/admin/system/refunds/"+attempt;
        var info=authMapper.readTree(get(system.browser(),systemPath,200));
        var query=Map.of("key","e-system-observe","expectedVersion",info.get("version").asLong(),"reason","같은 환불 조회");
        post(next.browser(),systemPath+"/query",query,403);
        post(next.browser(),systemPath+"/reconcile",Map.of("key","e-forbidden","expectedVersion",info.get("version").asLong(),
                "reason","지원 권한 직접 대사","evidenceReference","support","transactionKey","unknown"),403);
        post(next.browser(),"/api/admin/accounts/"+f.authority().member()+"/restore",Map.of("reason","권한 없음"),403);
        post(next.browser(),"/api/seller/shipments/"+shipment.id()+"/ship",shipBody(shipment,"지원 대행 불가"),403);
        assertEquals(before,jdbc.queryForMap(sql,p));assertEquals(snapshot,get(f.browser(),order,200));
        post(system.browser(),systemPath+"/query",query,202);runCancellationJob("REFUND_SUBMIT.v1",attempt);
        var after=jdbc.queryForMap(sql,p);
        assertEquals(((Number)before.get("query_calls")).intValue()+1,((Number)after.get("query_calls")).intValue());
        for(String field:List.of("state","amount","reserved","succeeded","submit_calls","effects")){assertEquals(before.get(field),after.get(field),field);}
        assertEquals(snapshot,get(f.browser(),order,200));
    }
}
