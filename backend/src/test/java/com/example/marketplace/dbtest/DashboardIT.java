package com.example.marketplace.dbtest;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardIT extends SettlementContractFixture {
    private JsonNode command(java.net.http.HttpClient actor,String path,Map<String,?> body) throws Exception {
        var reply=accountRequest(actor,"POST",path,body,csrfToken(actor));
        assertEquals(200,reply.statusCode(),reply.body());
        return authMapper.readTree(reply.body());
    }
    private static void delta(JsonNode baseline,JsonNode current,String metric,long change) {
        assertEquals(new java.math.BigInteger(value(baseline,metric)).add(java.math.BigInteger.valueOf(change)).toString(),
                value(current,metric),metric);
    }
    @Test void moderationAndRepeatedReportClosuresUseCurrentVisibilityAndDistinctDatedEvents() throws Exception {
        var seller=twoEqualUnits("dash-mod");
        var product=seller.order().quote().stores().getFirst().lines().getFirst().productId();
        var slug=context.getBean(com.example.marketplace.catalog.api.Products.class).own(seller.authority().member(),product).slug();
        var investigator=supportFixture("dr-content","CONTENT");var op=investigator.browser();
        var reporter=supportFixture("dr-report","SUPPORT").browser();
        String desk="/api/admin/content/dashboard",shop="/api/seller/dashboard";
        runStoredJob(TYPE,schedule());var initial=readView(op,desk);var initialShop=readView(seller.browser(),shop);
        var report=command(reporter,"/api/products/"+slug+"/reports",Map.of("key",UUID.randomUUID(),"reason","상품 신고"));
        String base="/api/admin/content/reports/"+report.get("reportId").asText(),actions=base+"/moderation",commands=base+"/commands";
        command(op,commands,Map.of("key",UUID.randomUUID(),"action","ASSIGN","operator",investigator.authority().member(),"version",0));
        command(op,commands,Map.of("key",UUID.randomUUID(),"action","START","version",1));
        command(op,commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","판단 근거","version",2));
        long version=readView(op,actions).get("targetVersion").asLong();
        var hidden=command(op,actions,Map.of("key",UUID.randomUUID(),"hide",true,"caseVersion",3,"closeVersion",3,
                "edition",0,"targetVersion",version,"reason","조치 근거"));
        runStoredJob(TYPE,schedule());var closed=readView(op,desk);var hiddenShop=readView(seller.browser(),shop);
        delta(initial,closed,"REPORT_CLOSURES",1);delta(initial,closed,"REPORT_CLOSED",1);
        delta(initial,closed,"REPORT_RECEIVED_TOTAL",1);delta(initial,closed,"MODERATION_HIDE",1);
        delta(initialShop,hiddenShop,"PRODUCTS_REGISTERED",0);delta(initialShop,hiddenShop,"PRODUCTS_HIDDEN",1);
        delta(initialShop,hiddenShop,"PRODUCTS_PUBLIC",-1);
        command(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",3,"reason","재검토","reopenBasis","JUDGMENT_ERROR"));
        runStoredJob(TYPE,schedule());var reopened=readView(op,desk);
        delta(initial,reopened,"REPORT_CLOSED",0);delta(initial,reopened,"REPORT_INVESTIGATING",1);delta(initial,reopened,"REPORT_CLOSURES",1);
        command(op,actions,Map.of("key",UUID.randomUUID(),"hide",false,"caseVersion",4,"closeVersion",3,
                "edition",0,"targetVersion",version+1,"sourceHide",hidden.get("id").asText(),"reason","복원 근거"));
        command(op,commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","후속 판단","version",4));
        runStoredJob(TYPE,schedule());var finalView=readView(op,desk);var restoredShop=readView(seller.browser(),shop);
        delta(initial,finalView,"REPORT_CLOSURES",2);delta(initial,finalView,"REPORT_CLOSED",1);
        delta(initial,finalView,"REPORT_INVESTIGATING",0);delta(initial,finalView,"REPORT_RECEIVED_TOTAL",1);
        delta(initial,finalView,"MODERATION_RESTORE",1);delta(initial,finalView,"MODERATION_HIDE",1);
        delta(initialShop,restoredShop,"PRODUCTS_HIDDEN",0);delta(initialShop,restoredShop,"PRODUCTS_PUBLIC",0);
    }
    @Test void accountsApplicationIntakeRemainsTwoAfterApprovalAndRejection() throws Exception {
        var op=supportFixture("dr-app-op","ACCOUNTS");String desk="/api/admin/accounts/dashboard";
        var service=context.getBean(com.example.marketplace.seller.api.SellerApplications.class);
        var first=activeMember("dr-app-a@example.com");var second=activeMember("dr-app-b@example.com");
        runStoredJob(TYPE,schedule());var before=readView(op.browser(),desk);
        var input=new com.example.marketplace.seller.api.SellerApplications.Input("대시보드 신청 매대","판매 계획");
        var a=service.submit(first,input);var b=service.submit(second,input);
        runStoredJob(TYPE,schedule());var pending=readView(op.browser(),desk);
        delta(before,pending,"APPLICATION_RECEIVED",2);delta(before,pending,"APPLICATION_PENDING",2);
        service.decide(op.authority().member(),a.id(),"APPROVED","서류 확인");
        service.decide(op.authority().member(),b.id(),"REJECTED","서류 보완");
        runStoredJob(TYPE,schedule());var decided=readView(op.browser(),desk);
        delta(before,decided,"APPLICATION_RECEIVED",2);delta(before,decided,"APPLICATION_PENDING",0);
        delta(before,decided,"APPLICATION_APPROVED",1);delta(before,decided,"APPLICATION_REJECTED",1);
    }
    @Test void explicitBasicShipmentDelayFailureAndResumeAreCountedIndependently() throws Exception {
        var seller=twoEqualUnits("dr-delay");var shipment=paidShipment(seller);
        shipments().ship(shipment.id(),shipmentAuthority(seller),shipCommand(shipment,"dispatch"));
        var operator=supportFixture("dr-delay-op","SUPPORT");
        String desk="/api/admin/support/dashboard",shop="/api/seller/dashboard";
        runStoredJob(TYPE,schedule());var baseline=readView(operator.browser(),desk);var own=readView(seller.browser(),shop);
        for(String action:java.util.List.of("DELAYED","FAILED","RESUME")) {
            assertTrue(shipments().exception(shipment.id(),"dr-"+action,action,"실제 배송사 예외",java.time.Instant.now()).applied());
            runStoredJob(TYPE,schedule());var overall=readView(operator.browser(),desk);var store=readView(seller.browser(),shop);
            long delayed=action.equals("DELAYED")?1:0,failed=action.equals("FAILED")?1:0;
            delta(baseline,overall,"SHIPMENT_DELAYED",delayed);delta(baseline,overall,"SHIPMENT_FAILED",failed);
            delta(own,store,"SHIPMENT_DELAYED",delayed);delta(own,store,"SHIPMENT_FAILED",failed);
        }
    }
    private JsonNode readView(java.net.http.HttpClient browser,String path) throws Exception {
        var reply=accountRequest(browser,"GET",path,null,null);
        assertEquals(200,reply.statusCode(),reply.body());
        return authMapper.readTree(reply.body());
    }
    private Map<String,String> financialAndNoticeRows() {
        var rows=new java.util.TreeMap<String,String>();
        var tables=jdbc.getJdbcTemplate().queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname='marketplace'
                  AND (tablename LIKE 'payment\\_%' ESCAPE '\\' OR tablename LIKE 'settlement\\_%' ESCAPE '\\'
                    OR tablename LIKE 'notification\\_%' ESCAPE '\\' OR tablename IN ('purchase_order','seller_order','order_item','order_unit','coupon_restoration'))
                ORDER BY tablename
                """,String.class);
        assertTrue(tables.containsAll(java.util.List.of("payment_refund_budget","settlement_ledger","settlement_batch_item","notification_event","notification_inbox")));
        for(String table:tables) {
            if(!table.matches("[a-z0-9_]+")) { throw new AssertionError("unexpected table name"); }
            rows.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM marketplace."+table+" t",String.class));
        }
        return rows;
    }
    @Test void completedOriginalItemAndShippingRefundUseSuccessAmountsWithoutChangingSourceRows() throws Exception {
        var seller=cancellationMoneyFixture("dash-ref",false,false);
        cancellationMoneyConserved(seller,java.util.List.of(10000L,10000L,10001L),3000,33001);
        var before=financialAndNoticeRows();
        runStoredJob(TYPE,schedule());
        var view=readView(seller.browser(),"/api/seller/dashboard");
        assertEquals("33001",value(view,"PAID_AMOUNT"));
        assertEquals("30003",value(view,"PRODUCT_GROSS"));
        assertEquals("2",value(view,"COUPON_DISCOUNT"));
        assertEquals("3000",value(view,"SHIPPING_CHARGED"));
        assertEquals("30001",value(view,"REFUNDED_ITEM"));
        assertEquals("3000",value(view,"REFUNDED_SHIPPING"));
        assertEquals("0",value(view,"NET_TRADED"));
        assertEquals(before,financialAndNoticeRows());
    }
    @Test void productAndStockReadModelsFollowActualSaleAndSellerStoppingTheProduct() throws Exception {
        var purchase=twoEqualUnits("dr-stock");var seller=purchase.authority().member();
        var line=purchase.order().quote().stores().getFirst().lines().getFirst();
        var products=context.getBean(com.example.marketplace.catalog.api.Products.class);
        var p=products.own(seller,line.productId());
        var paymentsBefore=financialAndNoticeRows();runStoredJob(TYPE,schedule());
        var before=readView(purchase.browser(),"/api/seller/dashboard");assertEquals(paymentsBefore,financialAndNoticeRows());
        long available=Long.parseLong(value(before,"STOCK_AVAILABLE")),onHand=Long.parseLong(value(before,"STOCK_ON_HAND"));
        long registered=Long.parseLong(value(before,"PRODUCTS_REGISTERED")),published=Long.parseLong(value(before,"PRODUCTS_PUBLIC"));
        assertTrue(registered>=1 && published>=1);
        paidShipment(purchase);
        products.save(seller,p.id(),new com.example.marketplace.catalog.api.Products.Input(p.categoryId(),p.slug(),p.title(),
                p.description(),"STOPPED",p.version()));
        runStoredJob(TYPE,schedule());var after=readView(purchase.browser(),"/api/seller/dashboard");
        assertEquals(Long.toString(onHand-2),value(after,"STOCK_ON_HAND"));
        assertEquals(Long.toString(onHand-2-Long.parseLong(value(after,"STOCK_RESERVED"))),value(after,"STOCK_AVAILABLE"));
        assertTrue(available>=Long.parseLong(value(after,"STOCK_AVAILABLE")));
        assertEquals(Long.toString(registered),value(after,"PRODUCTS_REGISTERED"));
        assertEquals(Long.toString(published-1),value(after,"PRODUCTS_PUBLIC"));
        assertEquals(Long.toString(Long.parseLong(value(before,"PRODUCTS_STOPPED"))+1),value(after,"PRODUCTS_STOPPED"));
    }
    @Test void confirmationReceiptsAcrossSeoulMidnightUseOriginalUnitAmountsAndSeparateDays() throws Exception {
        var seller=twoEqualUnits("d-midnight");
        var shipment=paidShipment(seller);
        var zone=java.time.ZoneId.of("Asia/Seoul");
        var today=java.time.LocalDate.now(zone);
        var midnight=today.atStartOfDay(zone).toInstant();
        completeShipment(seller,shipment,midnight.minusSeconds(60));
        var who=shipmentAuthority(seller);String number=seller.order().orderNumber();
        assertEquals(2,shipment.units().size());
        for(var physical:shipment.units()) {
            var observed=physical.ordinal()==0?midnight.minusNanos(1000):midnight.plusNanos(1000);
            var api=confirmationsWith(java.time.Clock.fixed(observed,java.time.ZoneOffset.UTC),s->{ });
            api.confirm(number,who,new com.example.marketplace.order.api.OrderConfirmations.Command("midnight-"+physical.ordinal(),
                    api.get(number,who).revision(),java.util.List.of(new com.example.marketplace.order.api.OrderConfirmations.Unit(
                            physical.orderItemId(),physical.ordinal()))));
        }
        runStoredJob(TYPE,schedule());
        for(var day:java.util.List.of(today.minusDays(1),today)) {
            var view=readView(seller.browser(),"/api/seller/dashboard?from="+day+"&to="+day);
            assertEquals("10019",value(view,"CONFIRMED_PRODUCT_NET"));
        }
    }
    @Test void successfulRefundAndAppliedFeeCountOnceWhileUnknownAndFailedRemainExcluded() throws Exception {
        for(String outcome:java.util.List.of("SUCCEEDED","UNKNOWN","FAILED")) {
            var returned=financialReturn("dr-fee-"+outcome.toLowerCase(java.util.Locale.ROOT),outcome);
            assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,financialWorker(returned).resume(returned.job()));
            assertTrue(returned.jobs().complete(returned.job().id(),returned.job().ownerToken()));
            var parts=returned.physical().parties();var sale=parts.shipment().sellerOrderId();
            var params=Map.of("id",returned.consent().assessmentId(),"sale",sale);
            long deducted=jdbc.queryForObject("SELECT coalesce(sum(deducted),0) FROM marketplace.claim_fee_application WHERE assessment_id=:id",params,Long.class);
            assertEquals(outcome.equals("SUCCEEDED"),deducted>0);
            var before=financialAndNoticeRows();
            runStoredJob(TYPE,schedule());var view=readView(parts.seller().browser(),"/api/seller/dashboard");
            assertEquals(Long.toString(deducted),value(view,"RECOVERY_FEE_DEDUCTED"));
            long refund=outcome.equals("SUCCEEDED")?returned.outcome().amount():0;
            assertEquals(Long.toString(refund),value(view,"REFUNDED_ITEM"));
            assertEquals(Long.toString(Long.parseLong(value(view,"PAID_AMOUNT"))-refund),value(view,"NET_TRADED"));
            assertEquals(before,financialAndNoticeRows());
        }
    }
    @Test void reassignedCaseMovesOnNextSnapshotButDetailPermissionChangesImmediately() throws Exception {
        var first=supportFixture("dr-assn-a","SUPPORT");
        var next=supportFixture("dr-assn-b","SUPPORT");
        var visitor=authBrowser();var key=UUID.randomUUID();String privateBody="dashboard-private-"+key;
        assertEquals(200,accountRequest(visitor,"POST","/api/support/lost-access",
                Map.of("key",key,"body",privateBody),csrfToken(visitor)).statusCode());
        var id=jdbc.queryForObject("SELECT id FROM marketplace.operations_support_case WHERE request_key=:key",Map.of("key",key),UUID.class);
        String assignment="/api/admin/support/assignments/"+id, detail="/api/support/cases/"+id+"?reader=OPERATOR";
        String dashboard="/api/admin/support/dashboard";
        assertEquals(200,accountRequest(first.browser(),"POST",assignment,
                Map.of("key",UUID.randomUUID(),"expectedVersion",0,"operator",first.authority().member()),csrfToken(first.browser())).statusCode());
        assertTrue(readView(first.browser(),detail).toString().contains(privateBody));
        runStoredJob(TYPE,schedule());
        var before=readView(first.browser(),dashboard);
        assertEquals("1",value(before,"MY_CASES_WAITING"));assertFalse(before.toString().contains(id.toString()));
        assertFalse(before.toString().contains(privateBody));
        assertEquals(200,accountRequest(first.browser(),"POST",assignment,
                Map.of("key",UUID.randomUUID(),"expectedVersion",1,"operator",next.authority().member()),csrfToken(first.browser())).statusCode());
        assertEquals(404,accountRequest(first.browser(),"GET",detail,null,null).statusCode());
        assertTrue(readView(next.browser(),detail).toString().contains(privateBody));
        assertEquals(before.path("metrics"),readView(first.browser(),dashboard).path("metrics"));
        assertEquals("0",value(readView(next.browser(),dashboard),"MY_CASES_WAITING"));
        runStoredJob(TYPE,schedule());
        var after=readView(first.browser(),dashboard);
        assertEquals(value(before,"CASES_WAITING"),value(after,"CASES_WAITING"));
        assertEquals("0",value(after,"MY_CASES_WAITING"));assertEquals("1",value(readView(next.browser(),dashboard),"MY_CASES_WAITING"));
    }
    @Test void oldestExchangeAgeUsesMaximumOfActiveShipmentsPerStoreAndGlobally() throws Exception {
        var first=pendingActualExchange("dr-age-a");
        var second=pendingActualExchange("dr-age-b");
        var support=supportFixture("dr-age-op","SUPPORT");
        for(var exchange:java.util.List.of(first,second)) { runExchangeActualJob(exchange.jobId()); }
        runStoredJob(TYPE,schedule());
        var all=readView(support.browser(),"/api/admin/support/dashboard");
        var stamp=java.sql.Timestamp.from(java.time.Instant.parse(all.path("sourceAsOf").asText()));
        long maximum=jdbc.queryForObject("""
                SELECT greatest(0,floor(extract(epoch FROM :stamp::timestamptz-min(shipped_at))))::bigint
                FROM marketplace.fulfillment_exchange_shipment WHERE state IN ('SHIPPED','IN_TRANSIT')
                """,Map.of("stamp",stamp),Long.class);
        assertEquals(Long.toString(maximum),value(all,"REPLACEMENT_OLDEST_SECONDS"));
        for(var exchange:java.util.List.of(first,second)) {
            var view=readView(exchange.parties().seller().browser(),"/api/seller/dashboard");
            long age=jdbc.queryForObject("""
                    SELECT greatest(0,floor(extract(epoch FROM :stamp::timestamptz-shipped_at)))::bigint
                    FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:exchange
                    """,Map.of("stamp",stamp,"exchange",exchange.accepted().exchangeId()),Long.class);
            assertEquals(Long.toString(age),value(view,"REPLACEMENT_OLDEST_SECONDS"));
            assertEquals("0",value(view,"SHIPMENT_DELAYED"));
            deliverPreparedExchange(exchange);
        }
        runStoredJob(TYPE,schedule());
        for(var exchange:java.util.List.of(first,second)) {
            var view=readView(exchange.parties().seller().browser(),"/api/seller/dashboard");
            assertEquals("1",value(view,"REPLACEMENT_DELIVERED"));
            assertFalse(view.toString().contains("REPLACEMENT_OLDEST_SECONDS"));
        }
    }
    @Test void concurrentSystemCommandsCoalesceAndAFailedRunKeepsTheLastSuccess() throws Exception {
        var system=supportFixture("dash-retry","SYSTEM").browser();
        var support=supportFixture("dash-deny","SUPPORT").browser();
        var seller=twoEqualUnits("dash-last");paidShipment(seller);runStoredJob(TYPE,schedule());
        String published=head();String route="/api/admin/system/dashboard/refresh";
        int initial=jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_run",Map.of(),Integer.class);
        var key=UUID.randomUUID();var command=Map.of("key",key,"reason","동시 재집계");
        assertEquals(403,accountRequest(support,"POST",route,command,csrfToken(support)).statusCode());
        String csrf=csrfToken(system);var ready=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{
                ready.await();return accountRequest(system,"POST",route,command,csrf);
            })).toList();
            ready.countDown();
            for(var result:results) { assertEquals(200,result.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()); }
        }
        assertEquals(initial+1,jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_run",Map.of(),Integer.class));
        assertEquals(409,accountRequest(system,"POST",route,Map.of("key",key,"reason","다른 본문"),csrf).statusCode());
        UUID id=jdbc.queryForObject("""
                SELECT r.job_id FROM marketplace.dashboard_run r JOIN marketplace.dashboard_request q ON q.run_id=r.id
                WHERE q.request_key=:key
                """,Map.of("key",key),UUID.class);
        var now=new JobClock();var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(
                new OneJobJdbc(context.getBean(javax.sql.DataSource.class),id),transactionManager,now);
        now.now=jobs.find(id).orElseThrow().nextRunAt();
        var failed=worker(jobs,phase->{ if(phase.equals("AFTER_TRADE")) { throw new IllegalStateException("injected source failure"); } });
        try(var engine=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,java.util.List.of(failed))) { assertTrue(engine.runOnce()); }
        assertEquals("READY",jobs.find(id).orElseThrow().status());assertEquals(published,head());
        var last=authMapper.readTree(accountRequest(seller.browser(),"GET","/api/seller/dashboard",null,null).body());
        assertEquals("FAILED",last.path("status").asText());assertTrue(last.path("generation").isTextual());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_snapshot WHERE job_id=:id",Map.of("id",id),Integer.class));
        now.now=jobs.find(id).orElseThrow().nextRunAt();var resumed=jobs.claim(java.util.Set.of(TYPE)).orElseThrow();
        worker(jobs,phase->{ }).handle(resumed);assertTrue(jobs.complete(id,resumed.ownerToken()));
        org.junit.jupiter.api.Assertions.assertNotEquals(published,head());
        assertEquals(java.util.List.of(1),jdbc.queryForList("SELECT attempt FROM marketplace.dashboard_failed_attempt WHERE job_id=:id",Map.of("id",id),Integer.class));
        assertTrue(Long.parseLong(value(readView(system,"/api/admin/system/dashboard"),"REFRESH_FAILURES"))>=1);
        assertFalse(last.toString().contains("REFRESH_FAILURES"));
        verifyPublishedRowsImmutable(id,key,system);
    }
    private void verifyPublishedRowsImmutable(UUID jobId,UUID requestKey,java.net.http.HttpClient system) throws Exception {
        String path="/api/admin/system/dashboard";
        var published=readView(system,path);
        var generation=UUID.fromString(published.path("generation").asText());
        var params=Map.of("job",jobId,"key",requestKey,"id",generation);
        var rows=Map.of("dashboard_snapshot","id=:id AND job_id=:job",
                "dashboard_metric","snapshot_id=:id AND scope='GLOBAL' AND metric='REFRESH_FAILURES'",
                "dashboard_request","request_key=:key",
                "dashboard_failed_attempt","job_id=:job AND attempt=1");
        var changes=Map.of("dashboard_snapshot","source_as_of=source_as_of+interval '1 second'",
                "dashboard_metric","value=value+1","dashboard_request","reason=reason||'!'",
                "dashboard_failed_attempt","failed_at=failed_at+interval '1 second'");
        String originalHead=head();
        for(var entry:rows.entrySet()) {
            String table=entry.getKey(),target="marketplace."+table+" AS t WHERE "+entry.getValue();
            String select="SELECT to_jsonb(t)::text FROM "+target;
            String original=jdbc.queryForObject(select,params,String.class);
            for(String sql:java.util.List.of("UPDATE marketplace."+table+" SET "+changes.get(table)+" WHERE "+entry.getValue(),
                    "DELETE FROM "+target)) {
                rejectSnapshotMutation(()->jdbc.update(sql,params),"DASHBOARD_SNAPSHOT_IMMUTABLE");
                assertEquals(original,jdbc.queryForObject(select,params,String.class));
            }
        }
        String nonexistent="SELECT count(*) FROM marketplace.dashboard_metric WHERE snapshot_id=:id AND metric='LATE_PROBE'";
        assertEquals(0,jdbc.queryForObject(nonexistent,params,Integer.class));
        rejectSnapshotMutation(()->jdbc.update("""
                INSERT INTO marketplace.dashboard_metric(snapshot_id,scope,scope_id,day,metric,value)
                VALUES(:id,'GLOBAL',NULL,NULL,'LATE_PROBE',1)
                """,params),"DASHBOARD_SNAPSHOT_SEALED");
        assertEquals(0,jdbc.queryForObject(nonexistent,params,Integer.class));
        assertEquals(originalHead,head());
        assertEquals(published,readView(system,path));
    }
    private static void rejectSnapshotMutation(org.junit.jupiter.api.function.Executable mutation,String expectedError) {
        var rejected=org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,mutation);
        var database=org.junit.jupiter.api.Assertions.assertInstanceOf(org.postgresql.util.PSQLException.class,rejected.getMostSpecificCause());
        assertEquals("23514",database.getSQLState());
        assertEquals(expectedError,java.util.Objects.requireNonNull(database.getServerErrorMessage()).getMessage());
    }
    @Test void accessRevokedWhileReadingCannotReturnPreviouslyVisibleMoney() throws Exception {
        var operator=supportFixture("dash-revoke","SETTLEMENTS");
        String path="/api/admin/settlements/dashboard";
        assertEquals(200,accountRequest(operator.browser(),"GET",path,null,null).statusCode());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var gate=context.getBean(javax.sql.DataSource.class).getConnection()) {
            gate.setAutoCommit(false);
            try(var query=gate.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) {
                query.setObject(1,operator.authority().member());query.executeQuery().close();
            }
            try {
                var waiting=pool.submit(()->accountRequest(operator.browser(),"GET",path,null,null));
                awaitHistoryLock("identity_user");
                try(var revoke=gate.prepareStatement("DELETE FROM marketplace.admin_permission WHERE user_id=? AND permission='SETTLEMENTS'")) {
                    revoke.setObject(1,operator.authority().member());assertEquals(1,revoke.executeUpdate());
                }
                gate.commit();var denied=waiting.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("PAID_AMOUNT"));
                assertFalse(denied.body().contains("generation"));
            }finally { gate.rollback(); }
        }finally { pool.shutdownNow(); }
    }

    @Test void readerPinsPeriodAndCurrentValuesWhenAnotherGenerationPublishes() throws Exception {
        var seller=twoEqualUnits("dash-reader");runStoredJob(TYPE,schedule());
        var requestActor=new com.example.marketplace.reporting.api.Dashboards.Actor(seller.authority().member(),seller.authority().browser(),seller.authority().version());
        var service=context.getBean(com.example.marketplace.reporting.api.Dashboards.class);
        var initial=service.read(requestActor,"SELLER",null,null);
        paidShipment(seller);var id=schedule();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var reader=new com.example.marketplace.reporting.infrastructure.DashboardReader(jdbc,
                context.getBean(com.example.marketplace.platform.api.Jobs.class),context.getBean(com.example.marketplace.reporting.infrastructure.DashboardRuns.class),
                context.getBean(com.example.marketplace.identity.api.SellerMembership.class),context.getBean(com.example.marketplace.identity.api.HistorySessions.class),
                context.getBean(com.example.marketplace.seller.api.Stores.class),context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),
                ()->{ entered.countDown();try { assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS)); }
                    catch(InterruptedException stopped) { Thread.currentThread().interrupt();throw new IllegalStateException(stopped); } });
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var during=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(tx->reader.read(requestActor,"SELLER",null,null)));
            try {
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                runStoredJob(TYPE,id);release.countDown();
                var pinned=during.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(initial.generation(),pinned.generation());assertEquals(initial.metrics(),pinned.metrics());
                var next=service.read(requestActor,"SELLER",null,null);
                org.junit.jupiter.api.Assertions.assertNotEquals(initial.generation(),next.generation());
                assertTrue(next.metrics().stream().anyMatch(m->m.id().equals("PAID_AMOUNT")&&new java.math.BigInteger(m.value()).signum()>0));
                assertTrue(next.metrics().stream().anyMatch(m->m.id().equals("SHIPMENT_PREPARING")&&m.value().equals("1")));
            }finally { release.countDown(); }
        }
    }
    @Test void finalFenceAfterActualJobLockWaitRollsBackHeadAndRunUntilFreshAttempt() throws Exception {
        UUID id=schedule();var clock=new JobClock();
        var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(javax.sql.DataSource.class),id),transactionManager,clock);
        clock.now=jobs.find(id).orElseThrow().nextRunAt();
        var first=jobs.claim(java.util.Set.of(TYPE)).orElseThrow();String published=head();
        var ready=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);
        var guarded=worker(jobs,stage->{ if("STAGE_COMMITTED".equals(stage)) {
            ready.countDown();try { assertTrue(proceed.await(10,java.util.concurrent.TimeUnit.SECONDS)); }
            catch(InterruptedException stop) { Thread.currentThread().interrupt();throw new IllegalStateException(stop); }
        } });
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var gate=context.getBean(javax.sql.DataSource.class).getConnection()) {
            gate.setAutoCommit(false);var running=pool.submit(()->guarded.handle(first));
            try {
                assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
                try(var stmt=gate.prepareStatement("SELECT id FROM marketplace.job WHERE id=? FOR UPDATE")) {
                    stmt.setObject(1,id);stmt.executeQuery().close();
                }
                proceed.countDown();
                awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%marketplace.job WHERE id=%FOR UPDATE%')",Boolean.class),"final dashboard lease check must wait for actual row lock");
                clock.now=first.leaseUntil();gate.commit();
                var failure=org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,()->running.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("JOB_LEASE_LOST",failure.getCause().getMessage());
                assertEquals(published,head());
                assertTrue(jdbc.queryForObject("SELECT state='PENDING' AND completed_at IS NULL FROM marketplace.dashboard_run WHERE job_id=:id",Map.of("id",id),Boolean.class));
                var own=jobs.claim(java.util.Set.of(TYPE)).orElseThrow();
                worker(jobs,stage->{ }).handle(own);assertTrue(jobs.complete(id,own.ownerToken()));
            }finally { gate.rollback();proceed.countDown(); }
        }finally { pool.shutdownNow(); }
    }
    private static final String TYPE="DASHBOARD_REFRESH.v1";
    private UUID schedule() throws Exception {
        var operator=supportFixture("dr-"+UUID.randomUUID().toString().substring(0,8),"SYSTEM");
        var id=context.getBean(com.example.marketplace.reporting.api.Dashboards.class)
                .refresh(new com.example.marketplace.reporting.api.Dashboards.Actor(operator.authority().member(),
                        operator.authority().browser(),operator.authority().version()),UUID.randomUUID(),"시도별 집계 복구 검사");
        return jdbc.queryForObject("SELECT job_id FROM marketplace.dashboard_run WHERE id=:id",Map.of("id",id),UUID.class);
    }
    private com.example.marketplace.reporting.infrastructure.DashboardWorker worker(
            com.example.marketplace.platform.api.JobLeases leases,com.example.marketplace.reporting.infrastructure.DashboardWorker.Checkpoint point) {
        return new com.example.marketplace.reporting.infrastructure.DashboardWorker(jdbc,
                context.getBean(com.example.marketplace.reporting.infrastructure.DashboardTradeFacts.class),
                context.getBean(com.example.marketplace.reporting.infrastructure.DashboardBalances.class),leases,transactionManager,point);
    }
    private String head() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_array(h.run_id,h.snapshot_id,r.completed_at,
                 (SELECT jsonb_agg(to_jsonb(m) ORDER BY m.scope,m.scope_id,m.metric,m.day)
                  FROM marketplace.dashboard_metric m WHERE m.snapshot_id=h.snapshot_id))::text
                FROM marketplace.dashboard_head h LEFT JOIN marketplace.dashboard_run r ON r.id=h.run_id WHERE singleton
                """,Map.of(),String.class);
    }
    @Test void privateRepeatableSnapshotSurvivesHeartbeatAndOnlyLiveTakeoverPublishes() throws Exception {
        var seller=twoEqualUnits("dash-fence");
        var store=seller.order().sellers().getFirst().storeId();
        var jobId=schedule();
        var clock=new JobClock();
        var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(
                new OneJobJdbc(context.getBean(javax.sql.DataSource.class),jobId),transactionManager,clock);
        clock.now=jobs.find(jobId).orElseThrow().nextRunAt();
        var first=jobs.claim(java.util.Set.of(TYPE)).orElseThrow();
        var before=head();
        var staged=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var trade=new java.util.concurrent.CountDownLatch(1);
        var resume=new java.util.concurrent.CountDownLatch(1);
        var old=worker(jobs,phase->{
            try {
                if(phase.equals("AFTER_TRADE")) { trade.countDown();assertTrue(resume.await(15,java.util.concurrent.TimeUnit.SECONDS)); }
                if(phase.equals("STAGE_COMMITTED")) { staged.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS)); }
            }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); }
        });
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var original=pool.submit(()->old.handle(first));
            try {
                assertTrue(trade.await(15,java.util.concurrent.TimeUnit.SECONDS));
                paidShipment(seller);
                clock.advance(3);assertTrue(jobs.heartbeat(jobId,first.ownerToken()));
                resume.countDown();assertTrue(staged.await(15,java.util.concurrent.TimeUnit.SECONDS));
                var p=Map.of("job",jobId,"store",store);
                assertEquals(0,jdbc.queryForObject("""
                        SELECT count(*) FROM marketplace.dashboard_metric m JOIN marketplace.dashboard_snapshot s ON s.id=m.snapshot_id
                        WHERE s.job_id=:job AND s.attempt=1 AND m.scope_id=:store AND m.metric IN('PAID_AMOUNT','SHIPMENT_PREPARING')
                        """,p,Integer.class));
                assertEquals(before,head());
                var privateId=jdbc.queryForObject("SELECT id FROM marketplace.dashboard_snapshot WHERE job_id=:job AND attempt=1",p,UUID.class);
                var publicView=readView(seller.browser(),"/api/seller/dashboard");
                assertFalse(publicView.toString().contains(privateId.toString()));
                if(publicView.path("generation").isTextual()) {
                    assertEquals("0",value(publicView,"PAID_AMOUNT"));
                }else { assertEquals(0,publicView.path("metrics").size()); }
                clock.now=jobs.find(jobId).orElseThrow().leaseUntil().plusNanos(1000);
                var second=jobs.claim(java.util.Set.of(TYPE)).orElseThrow();
                assertEquals(2,second.attempts());release.countDown();
                var failure=org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                        ()->original.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("JOB_LEASE_LOST",failure.getCause().getMessage());assertEquals(before,head());
                assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM marketplace.dashboard_run WHERE job_id=:job",p,String.class));
                worker(jobs,phase->{ }).handle(second);
                assertTrue(jobs.complete(jobId,second.ownerToken()));
                assertEquals(2,jdbc.queryForObject("""
                        SELECT count(DISTINCT m.metric) FROM marketplace.dashboard_head h JOIN marketplace.dashboard_metric m ON m.snapshot_id=h.snapshot_id
                        WHERE m.scope_id=:store AND m.metric IN('PAID_AMOUNT','SHIPMENT_PREPARING') AND m.value>0
                        """,p,Integer.class));
                assertFalse(jobs.complete(jobId,first.ownerToken()));
                runStoredJob(TYPE,schedule());
                var newest=head();
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->worker(jobs,phase->{ }).handle(second));
                assertEquals(newest,head());
            } finally { resume.countDown();release.countDown(); }
        }
    }

    @Test void publishedGenerationAndSourceRowsSurviveRealWorkerRestartBeforeAck() throws Exception {
        var job=schedule();var jobs=context.getBean(com.example.marketplace.platform.api.Jobs.class);
        var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE")).resolve("dashboard-restart-"+job));
        Process first=confirmationProcess(folder,"first",jobs.find(job).orElseThrow().nextRunAt(),job,"ACK"),second=null;
        try {
            var marker=folder.resolve("first-effect");
            awaitJobCondition(()->java.nio.file.Files.exists(marker)||!first.isAlive(),"dashboard published before ACK");
            assertTrue(java.nio.file.Files.exists(marker),"inspect first.log");
            var saved=head();
            var owned=jobs.find(job).orElseThrow();assertEquals("RUNNING",owned.status());
            first.destroyForcibly();assertTrue(ContractTiming.awaitProcess(first,10,java.util.concurrent.TimeUnit.SECONDS));
            second=confirmationProcess(folder,"second",owned.leaseUntil().plusNanos(1000),job,"NONE");
            assertTrue(ContractTiming.awaitProcess(second,40,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,second.exitValue(),"inspect second.log");assertEquals(saved,head());
            assertEquals("SUCCEEDED",jobs.find(job).orElseThrow().status());assertEquals(2,jobs.find(job).orElseThrow().attempts());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_snapshot WHERE job_id=:id",Map.of("id",job),Integer.class));
            assertFalse(jobs.complete(job,owned.ownerToken()));
        }finally {
            for(Process owned:new Process[]{first,second}) {
                if(owned!=null&&owned.isAlive()) { owned.destroyForcibly();ContractTiming.awaitProcess(owned,10,java.util.concurrent.TimeUnit.SECONDS); }
            }
        }
    }
    @Test void paidSellerSeesOwnSnapshotAndHistoricalReadsDoNotEnqueueWork() throws Exception {
        var seller=twoEqualUnits("dash");
        paidShipment(seller);
        var operator=supportFixture("dash-refresh","SYSTEM").browser();
        var cmd=Map.of("key",UUID.randomUUID(),"reason","서울 날짜별 매대 대사");
        var posted=accountRequest(operator,"POST","/api/admin/system/dashboard/refresh",cmd,csrfToken(operator));
        assertEquals(200,posted.statusCode(),posted.body());
        var run=Long.parseLong(posted.body());
        var repeat=accountRequest(operator,"POST","/api/admin/system/dashboard/refresh",cmd,csrfToken(operator));
        assertEquals(posted.body(),repeat.body());
        var job=jdbc.queryForObject("SELECT job_id FROM marketplace.dashboard_run WHERE id=:id",Map.of("id",run),UUID.class);
        runStoredJob("DASHBOARD_REFRESH.v1",job);
        var reply=accountRequest(seller.browser(),"GET","/api/seller/dashboard",null,null);
        assertEquals(200,reply.statusCode(),reply.body());
        var view=authMapper.readTree(reply.body());
        assertEquals("READY",view.path("status").asText());
        assertTrue(view.path("generation").isTextual());
        var amount=jdbc.queryForObject("SELECT total::text FROM marketplace.seller_order WHERE id=:id",
                Map.of("id",ownSeller(seller)),String.class);
        assertEquals(amount,value(view,"PAID_AMOUNT"));
        assertEquals("20038",value(view,"PRODUCT_GROSS"));
        int before=jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_run",Map.of(),Integer.class);
        var previous=accountRequest(seller.browser(),"GET","/api/seller/dashboard?from=2010-01-01&to=2010-01-31",null,null);
        var historical=authMapper.readTree(previous.body());
        assertEquals(200,previous.statusCode());
        assertEquals(view.path("generation"),historical.path("generation"));
        assertEquals("0",value(historical,"PAID_AMOUNT"));
        assertEquals(400,accountRequest(seller.browser(),"GET","/api/seller/dashboard?from=2010-01-01&to=2011-01-02",null,null).statusCode());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM marketplace.dashboard_run",Map.of(),Integer.class));
        var denied=accountRequest(operator,"GET","/api/admin/settlements/dashboard",null,null);
        assertEquals(403,denied.statusCode());assertFalse(denied.body().contains(amount));
    }
    private static String value(JsonNode view,String metric) {
        for(var entry:view.path("metrics")) {
            if(entry.path("id").asText().equals(metric)) { assertTrue(entry.path("value").isTextual());return entry.path("value").asText(); }
        }
        throw new AssertionError("Missing dashboard metric "+metric);
    }
}
