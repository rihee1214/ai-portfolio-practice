package com.example.marketplace.dbtest;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserApiIT extends PostgresContractFixture {
    private static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Seoul");
    // Keep monetary browser causes away from the current-time shared browser journeys.
    private static final LocalDate SETTLEMENT_FIRST_WEEK = LocalDate.now(SETTLEMENT_ZONE)
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(16);
    @org.springframework.test.context.bean.override.convention.TestBean(name="settlementClock", methodName="browserSettlementClock")
    private Clock settlementClock;
    static Clock browserSettlementClock() {
        return Clock.fixed(SETTLEMENT_FIRST_WEEK.plusWeeks(2).atStartOfDay(SETTLEMENT_ZONE).toInstant(), ZoneOffset.UTC);
    }

    private Map<String,Object> settlementBrowserOrder(String project, LocalDate week) throws Exception {
        var purchase = cancellationMoneyFixture("p11-browser-" + project, false, false);
        assertEquals(33001L, purchase.order().quote().total());
        assertEquals(1, purchase.order().sellers().size());
        var policyAt = jdbc.queryForObject("""
                SELECT p.effective_at FROM marketplace.order_settlement_policy snapshot
                JOIN marketplace.settlement_policy p ON p.version=snapshot.version
                WHERE snapshot.order_id=:id AND snapshot.version='SETTLEMENT_V1'
                """, Map.of("id", purchase.order().id()), java.sql.Timestamp.class).toInstant();
        assertFalse(purchase.order().createdAt().isBefore(policyAt));
        Instant confirmedAt = week.plusDays(2).atTime(12, 0).atZone(SETTLEMENT_ZONE).toInstant();
        Instant deliveredAt = confirmedAt.minusSeconds(60);
        var shipment = paidShipment(purchase);
        completeShipment(purchase, shipment, deliveredAt);
        var dispatchAt = jdbc.queryForObject("SELECT occurred_at FROM marketplace.order_dispatch WHERE seller_order_id=:id",
                Map.of("id", shipment.sellerOrderId()), java.sql.Timestamp.class).toInstant();
        assertFalse(dispatchAt.isBefore(purchase.order().createdAt()));
        assertTrue(deliveredAt.isAfter(dispatchAt));
        assertEquals(deliveredAt, jdbc.queryForObject("SELECT occurred_at FROM marketplace.order_initial_delivery WHERE seller_order_id=:id",
                Map.of("id", shipment.sellerOrderId()), java.sql.Timestamp.class).toInstant());
        var money = jdbc.queryForList("""
                SELECT u.net FROM marketplace.order_unit u JOIN marketplace.order_item i ON i.id=u.item_id
                WHERE i.seller_order_id=:id ORDER BY u.ordinal
                """, Map.of("id", shipment.sellerOrderId()), Long.class);
        assertEquals(List.of(10000L, 10000L, 10001L), money);
        assertEquals(3000L, purchase.order().quote().total() - money.stream().mapToLong(Long::longValue).sum());
        var api = confirmationsWith(Clock.fixed(confirmedAt, ZoneOffset.UTC), stage -> { });
        var number = purchase.order().orderNumber();
        var authority = shipmentAuthority(purchase);
        var view = api.get(number, authority);
        var units = view.units().stream().filter(com.example.marketplace.order.api.OrderConfirmations.Eligibility::eligible)
                .map(com.example.marketplace.order.api.OrderConfirmations.Eligibility::unit).toList();
        assertEquals(3, units.size());
        var receipt = api.confirm(number, authority,
                new com.example.marketplace.order.api.OrderConfirmations.Command("p11-browser-" + project, view.revision(), units));
        assertEquals(confirmedAt, receipt.confirmedAt());
        assertTrue(receipt.confirmedAt().isAfter(deliveredAt));
        assertEquals("CLOSED", orders().find(number).state());
        assertEquals(week, com.example.marketplace.settlement.domain.SettlementRules.week(receipt.confirmedAt()));
        assertFalse(settlementClock.instant().isBefore(week.plusWeeks(1).atStartOfDay(SETTLEMENT_ZONE).toInstant()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM marketplace.order_confirmation
                WHERE confirmed_at>=:start AND confirmed_at<:end
                """, Map.of("start", java.sql.Timestamp.from(week.atStartOfDay(SETTLEMENT_ZONE).toInstant()),
                        "end", java.sql.Timestamp.from(week.plusWeeks(1).atStartOfDay(SETTLEMENT_ZONE).toInstant())), Integer.class));
        return Map.of("storeId", purchase.order().sellers().getFirst().storeId(), "sellerOrderId", shipment.sellerOrderId(),
                "causeId", receipt.receiptId(), "weekStart", week);
    }
    @Test void paymentMockFixtureIsNotProductionApi() {
        try(var isolated=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) { isolated.register(PaymentFixtureEndpoint.class);isolated.refresh();assertTrue(isolated.getBeansOfType(PaymentFixtureEndpoint.class).isEmpty()); }
        assertTrue(PaymentFixtureEndpoint.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes"));
    }

    @Test void shipmentBrowserFixtureIsNotProductionApi() { try(var isolated=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) { isolated.register(ShipmentBrowserFixture.class,ReturnBrowserFixture.class,ExchangeBrowserFixture.class,RefundBrowserFixture.class);isolated.refresh();assertTrue(isolated.getBeansOfType(ShipmentBrowserFixture.class).isEmpty());assertTrue(isolated.getBeansOfType(ReturnBrowserFixture.class).isEmpty());assertTrue(isolated.getBeansOfType(ExchangeBrowserFixture.class).isEmpty());assertTrue(isolated.getBeansOfType(RefundBrowserFixture.class).isEmpty()); } }

    @Test void refundBrowserFixtureUsesCurrentOwnerHttpAndRealBoundedWorkersWithoutFinancialSqlCompletion() throws Exception {
        seedRefundBrowserOrders("probe");var raw=accountRequest(authBrowser(),"GET","/api/__test/refunds/seed/probe/member",null,null);assertEquals(200,raw.statusCode());var seed=authMapper.readTree(raw.body());String number=seed.path("orderNumber").asText();UUID attempt=UUID.fromString(seed.path("attemptId").asText());var browser=HttpClient.newBuilder().localAddress(new java.net.InetSocketAddress("127.0.0.13",0).getAddress()).cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(seed.path("buyerEmail").asText(),SIGNUP_PASSWORD),csrfToken(browser)).statusCode());String path="/api/__test/refunds/"+number;var before=accountRequest(browser,"GET",path+"/database",null,null);assertEquals(200,before.statusCode());var view=accountRequest(browser,"GET","/api/orders/"+number+"/refunds",null,null);assertEquals(200,view.statusCode());assertEquals("UNKNOWN",authMapper.readTree(view.body()).get(0).path("state").asText());assertEquals(seed.path("amount").asText(),authMapper.readTree(view.body()).get(0).path("amount").asText());assertEquals(404,accountRequest(authBrowser(),"GET",path+"/database",null,null).statusCode());assertEquals(403,accountRequest(browser,"POST",path+"/work",Map.of("action","AUTO"),null).statusCode());assertEquals(before.body(),accountRequest(browser,"GET",path+"/database",null,null).body());assertEquals(403,accountRequest(browser,"POST","/api/admin/system/refunds/"+attempt+"/query",Map.of("key","forbidden-owner","expectedVersion",2,"reason","owner has no SYSTEM"),csrfToken(browser)).statusCode());assertEquals(200,accountRequest(browser,"POST",path+"/work",Map.of("action","AUTO"),csrfToken(browser)).statusCode());var reviewed=authMapper.readTree(accountRequest(browser,"GET",path+"/database",null,null).body());assertEquals(5,reviewed.path("provider").get(0).path("query_calls").asInt());assertEquals(authMapper.readTree(before.body()).path("budget"),reviewed.path("budget"));assertTrue(authMapper.readTree(accountRequest(browser,"GET","/api/orders/"+number+"/refunds",null,null).body()).get(0).path("reviewRequired").asBoolean());assertEquals(200,accountRequest(browser,"POST",path+"/work",Map.of("action","SUCCESS"),csrfToken(browser)).statusCode());var completed=authMapper.readTree(accountRequest(browser,"GET",path+"/database",null,null).body());assertEquals(1,completed.path("provider").get(0).path("submit_calls").asInt());assertEquals(6,completed.path("provider").get(0).path("query_calls").asInt());assertEquals(1,completed.path("provider").get(0).path("effects").asInt());assertEquals("NONE",completed.path("payment").get(0).path("compensation").asText());assertEquals(2,completed.path("attempts").size());assertTrue(java.util.stream.StreamSupport.stream(completed.path("attempts").spliterator(),false).anyMatch(row->row.path("id").asText().equals(attempt.toString())&&row.path("state").asText().equals("SUCCEEDED")));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"f-browser-fixture-actual-http-db.json"),authMapper.writeValueAsString(Map.of("before",authMapper.readTree(before.body()),"reviewed",reviewed,"completed",completed,"actualProfileAndPorts",true,"fabricatedFinancialReceipt",false)));
    }

    @Test void returnBrowserFixtureUsesActualPortsAndScopedObservations() throws Exception {
        var f=returnParties("d-bfx");String number=f.buyer().order().orderNumber();ReturnBrowserFixture.register("probe","normal",Map.of("orderNumber",number,"sellerOrderId",f.shipment().sellerOrderId()),shipmentAuthority(f.seller()));String fixture="/api/__test/returns/"+number;var initial=accountRequest(f.buyer().browser(),"GET",fixture+"/database",null,null);assertEquals(200,initial.statusCode(),initial.body());var before=authMapper.readTree(initial.body());assertEquals(0,before.path("claim_return_request").size());var request=new com.example.marketplace.claim.api.Returns.Request("fixture-real-browser-request",confirmations().get(number,shipmentAuthority(f.buyer())).revision(),f.shipment().units().stream().map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.SELLER)).toList(),"실제 현재 owner HTTP 신청");var accepted=accountRequest(f.buyer().browser(),"POST","/api/orders/"+number+"/returns",request,csrfToken(f.buyer().browser()));assertEquals(202,accepted.statusCode());UUID id=UUID.fromString(authMapper.readTree(accepted.body()).path("returnId").asText());assertEquals(403,accountRequest(f.buyer().browser(),"POST",fixture+"/prepare",Map.of("returnId",id,"phase","INSPECT"),null).statusCode());assertEquals(404,accountRequest(authBrowser(),"GET",fixture+"/database",null,null).statusCode());var inspected=accountRequest(f.buyer().browser(),"POST",fixture+"/prepare",Map.of("returnId",id,"phase","INSPECT","responsibility","SELLER"),csrfToken(f.buyer().browser()));assertEquals(200,inspected.statusCode(),inspected.body());assertEquals("AWAITING_CONSENT",authMapper.readTree(inspected.body()).path("state").asText());var physical=authMapper.readTree(accountRequest(f.buyer().browser(),"GET",fixture+"/database",null,null).body());assertEquals(4,physical.path("restored").size());assertEquals(0,physical.path("payment_refund_attempt").size());String base="/api/orders/"+number+"/returns/"+id;var quoted=accountRequest(f.buyer().browser(),"POST",base+"/quote",Map.of("expectedRevision",3),csrfToken(f.buyer().browser()));assertEquals(200,quoted.statusCode());var quote=authMapper.readTree(quoted.body()).path("feeQuote");var consent=accountRequest(f.buyer().browser(),"POST",base+"/consent",Map.of("key","fixture-real-consent","expectedRevision",3,"quoteId",quote.path("id").asText(),"quoteFingerprint",quote.path("fingerprint").asText(),"consent",true,"legacyConsent",false),csrfToken(f.buyer().browser()));assertEquals(202,consent.statusCode());var item=accountRequest(f.buyer().browser(),"POST",fixture+"/work",Map.of("action","ITEM","returnId",id),csrfToken(f.buyer().browser()));assertEquals(200,item.statusCode(),item.body());var pending=authMapper.readTree(accountRequest(f.buyer().browser(),"GET",fixture+"/database",null,null).body());assertEquals("PAID",pending.path("order").path("state").asText());assertEquals(1,pending.path("provider").size());assertEquals(1,pending.path("claim_shipping_obligation").size());assertEquals(0,pending.path("claim_return_completion").size());var shipping=accountRequest(f.buyer().browser(),"POST",fixture+"/work",Map.of("action","SHIPPING","sellerOrderId",f.shipment().sellerOrderId()),csrfToken(f.buyer().browser()));assertEquals(200,shipping.statusCode(),shipping.body());var completed=authMapper.readTree(accountRequest(f.buyer().browser(),"GET",fixture+"/database",null,null).body());assertEquals("CLOSED",completed.path("order").path("state").asText());assertEquals(2,completed.path("provider").size());assertTrue(java.util.stream.StreamSupport.stream(completed.path("provider").spliterator(),false).allMatch(row->row.path("effects").asInt()==1&&row.path("submit_calls").asInt()==1));assertEquals(4,completed.path("restored").size());assertEquals(1,completed.path("claim_return_completion").size());assertEquals(before.path("units"),completed.path("units"));assertEquals(before.path("order").path("quote"),completed.path("order").path("quote"));assertTrue(java.util.stream.StreamSupport.stream(completed.path("inventory").spliterator(),false).allMatch(row->row.path("status").asText().equals("COMMITTED")));var guest=cancellationGuest("d-bfg");var gasBrowser=HttpClient.newBuilder().localAddress(new java.net.InetSocketAddress("127.0.0.7",0).getAddress()).cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();assertEquals(200,accountRequest(gasBrowser,"POST","/api/guest/access/login",Map.of("orderNumber",guest.order().orderNumber(),"phone",guest.fixture().phone,"password",GUEST_PASSWORD),csrfToken(gasBrowser)).statusCode());assertEquals(200,accountRequest(gasBrowser,"GET","/api/__test/returns/"+guest.order().orderNumber()+"/database",null,null).statusCode());assertEquals(404,accountRequest(gasBrowser,"GET",fixture+"/database",null,null).statusCode());java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"d-browser-fixture-actual-api-db.json"),authMapper.writeValueAsString(Map.of("before",before,"afterPhysicalBeforeConsent",physical,"afterItemBeforeOriginalShipping",pending,"completed",completed,"sqlFabricatedCompletion",false,"actualBoundGuestSource","127.0.0.7","currentGasFixtureObservation",200,"foreignGasFixtureObservation",404)));
    }

    @Test void couponFixtureIsDisabledOutsideTests() throws Exception {
        try(var isolated=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){isolated.register(CouponFixture.class);isolated.refresh();assertTrue(isolated.getBeansOfType(CouponFixture.class).isEmpty());}
        var browser=authBrowser();assertEquals(401,accountRequest(browser,"POST","/api/__test/coupon-reservations",Map.of("action","reserve","couponId",UUID.randomUUID(),"skuId",UUID.randomUUID()),csrfToken(browser)).statusCode());assertEquals(403,accountRequest(browser,"POST","/api/__test/coupon-reservations",Map.of("action","reserve"),null).statusCode());
        assertTrue(CouponFixture.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes"));
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
        if ("true".equals(System.getenv("A3_RUN_BROWSER"))) {
            supportFixture("p11-browser", "SETTLEMENTS");
            var desktop = settlementBrowserOrder("desktop", SETTLEMENT_FIRST_WEEK);
            var mobile = settlementBrowserOrder("mobile", SETTLEMENT_FIRST_WEEK.plusWeeks(1));
            for (String field : List.of("storeId", "sellerOrderId", "causeId", "weekStart")) {
                assertFalse(desktop.get(field).equals(mobile.get(field)), field);
            }
            var evidence = java.nio.file.Path.of(System.getenv("A3_EVIDENCE"), "settlement-browser-order.json");
            java.nio.file.Files.writeString(evidence, authMapper.writeValueAsString(Map.of("desktop", desktop, "mobile", mobile)));
        }
        runAuthBrowsersIfRequested();
    }
}
