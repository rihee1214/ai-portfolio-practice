package com.example.marketplace.dbtest;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteManagementIT extends PostgresContractFixture {
    private String request(HttpClient browser,String path,Object input,int expected) throws Exception {
        var response=accountRequest(browser,input==null?"GET":"POST",path,input,input==null?null:csrfToken(browser));
        assertEquals(expected,response.statusCode(),response.body());
        return response.body();
    }
    private Map<String,Object> edit(String kind,long version,String state) {
        var values=new HashMap<String,Object>();
        values.put("key",UUID.randomUUID());values.put("expectedVersion",version);values.put("kind",kind);values.put("state",state);
        values.put("title","사이트 운영 안내");values.put("body","<script>비공개 원문</script>");values.put("alt","");
        values.put("priority",1);values.put("reason","운영자가 게시 일정을 확인했습니다.");
        return values;
    }
    @Test void contentPublishesAndWithdrawsNoticeWithExactHistoryAndSchedule() throws Exception {
        var op=supportFixture("site-post","CONTENT").browser();var outsider=supportFixture("site-post-other","SYSTEM").browser();
        var visitor=authBrowser();var id=UUID.randomUUID();String desk="/api/admin/content/site-posts/"+id,pub="/api/site/posts/"+id;
        var first=edit("NOTICE",0,"DRAFT");request(outsider,desk,first,403);
        String draft=request(op,desk,first,200);assertEquals(draft,request(op,desk,first,200));
        var conflict=new HashMap<>(first);conflict.put("body","내용 변경");request(op,desk,conflict,409);
        request(visitor,pub,null,404);assertFalse(request(visitor,"/api/site/posts?kind=NOTICE",null,200).contains(id.toString()));
        var scheduled=edit("NOTICE",1,"PUBLISHED");scheduled.put("startsAt",Instant.parse("2100-01-01T00:00:00Z"));
        scheduled.put("endsAt",Instant.parse("2100-01-02T00:00:00Z"));
        request(op,desk,scheduled,200);request(visitor,pub,null,404);
        assertEquals(List.of(false,true,true,true,false,false),jdbc.getJdbcTemplate().queryForList("""
                SELECT marketplace.site_post_visible(state,starts_at,ends_at,observed)
                FROM marketplace.site_post_revision CROSS JOIN LATERAL unnest(ARRAY[
                  starts_at-interval '1 microsecond',starts_at,starts_at+interval '1 microsecond',
                  ends_at-interval '1 microsecond',ends_at,ends_at+interval '1 microsecond'
                ]) WITH ORDINALITY x(observed,n)
                WHERE post_id=? AND version=2 ORDER BY n
                """,Boolean.class,id));
        var publish=edit("NOTICE",2,"PUBLISHED");String active=request(op,desk,publish,200);
        assertTrue(request(visitor,pub,null,200).contains("<script>"));assertTrue(request(visitor,"/api/site/posts?kind=NOTICE",null,200).contains(id.toString()));
        request(op,desk,edit("NOTICE",3,"DRAFT"),200);assertEquals(active,request(op,desk,publish,200));
        request(visitor,pub,null,404);assertFalse(request(visitor,"/api/site/posts?kind=NOTICE",null,200).contains(id.toString()));
        assertEquals(4,authMapper.readTree(request(op,desk+"/history",null,200)).size());
        request(op,desk,edit("NOTICE",2,"DRAFT"),409);
    }
    @Test void shippingPolicyChangesOnlyNewQuotesAndLeavesPaidOrderAndRefundSnapshots() throws Exception {
        var op=supportFixture("site-money","SYSTEM").browser();var no=supportFixture("site-money-no","CONTENT").browser();
        var old=paymentFixture("site-old",false);var shipment=paidShipment(old);
        var unit=shipment.units().getFirst();var accepted=acceptCancellation(old,List.of(Map.of("itemId",unit.orderItemId(),"ordinal",unit.ordinal())),"site-old-refund");
        UUID attempt=UUID.fromString(accepted.path("refundAttemptId").asText());
        runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
        var draft=orderDraft("site-checkout");var originalInput=creationInput(draft);
        var concurrentDraft=orderDraft("site-running-checkout");var pending=creationInput(concurrentDraft);
        var product=browseFixture("site-price",49999,10);var lines=List.of(quoteLine((UUID)product.get("sku"),1));
        var initial=quotes().calculate(null,lines,List.of());assertEquals(3000,initial.stores().getFirst().shippingBase());
        String api="/api/admin/system/shipping-policy";
        var change=Map.of("key",UUID.randomUUID(),"expectedVersion",1,"baseFee",4567,"freeThreshold",51000,"reason","신규 견적 배송 정책");
        request(no,api,change,403);
        var gate=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var beforePolicy=new java.util.concurrent.atomic.AtomicReference<Map<String,String>>();
        var checkoutPid=new java.util.concurrent.atomic.AtomicInteger();
        var checkout=creationWith(context.getBean(com.example.marketplace.guest.api.GuestOrders.class),stage->{
            if(stage.equals("OUTBOX")) {
                beforePolicy.set(moneyRows());
                checkoutPid.set(jdbc.getJdbcTemplate().queryForObject("SELECT pg_backend_pid()",Integer.class));
                gate.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));
            }
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        String result;
        try {
            var order=pool.submit(()->checkout.create(concurrentDraft.purchaseSessionId().toString(),concurrentDraft.memberId(),null,pending));
            try {
                assertTrue(gate.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var edit=pool.submit(()->request(op,api,change,200));awaitExchangeBusinessLock("site_shipping_head","site-policy-writer");
                assertEquals(1,jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                        +"AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) AND query LIKE '%site_shipping_head%FOR UPDATE%'",Integer.class,checkoutPid.get()));
                assertEquals(1,authMapper.readTree(request(authBrowser(),"/api/site/shipping-policy",null,200)).path("version").asInt());
                release.countDown();var saved=order.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("P07-C-1",saved.quote().policyVersion());assertEquals(concurrentDraft.quote().fingerprint(),saved.quote().fingerprint());
                assertEquals(concurrentDraft.quote().stores().getFirst().shippingBase(),saved.quote().stores().getFirst().shippingBase());
                result=edit.get(10,java.util.concurrent.TimeUnit.SECONDS);
            } finally { release.countDown(); }
        } finally { pool.shutdownNow(); }
        var before=beforePolicy.get();
        assertEquals(result,request(op,api,change,200));assertEquals(2,authMapper.readTree(request(op,api,null,200)).size());
        assertSiteHistoryImmutable("site_shipping_policy","request_key=:key",Map.of("key",change.get("key")),"base_fee=base_fee+1");
        var changed=new HashMap<String,Object>(change);changed.put("baseFee",42);request(op,api,changed,409);
        var latest=quotes().calculate(null,lines,List.of());assertEquals(4567,latest.stores().getFirst().shippingBase());
        assertNotEquals(initial.fingerprint(),latest.fingerprint());
        assertEquals(54566,latest.total());assertEquals(before,moneyRows());
        assertEquals(4567,authMapper.readTree(request(authBrowser(),"/api/site/shipping-policy",null,200)).path("baseFee").asInt());
        var rejected=org.junit.jupiter.api.Assertions.assertThrows(com.example.marketplace.order.api.OrderRejected.class,
                ()->creation().create(draft.purchaseSessionId().toString(),draft.memberId(),null,originalInput));
        assertEquals(409,rejected.status());assertEquals(latest.policyVersion(),rejected.quote().policyVersion());assertEquals(before,moneyRows());
        var confirmedInput=new com.example.marketplace.order.infrastructure.OrderCreationService.Input(originalInput.requestKey(),originalInput.lines(),
                originalInput.coupons(),rejected.quote().fingerprint(),originalInput.contact(),originalInput.guestAttemptId(),0);
        var created=creation().create(draft.purchaseSessionId().toString(),draft.memberId(),null,confirmedInput);
        assertEquals(rejected.quote().fingerprint(),created.quote().fingerprint());assertEquals(latest.policyVersion(),created.quote().policyVersion());
        assertEquals(created.id(),creation().create(draft.purchaseSessionId().toString(),draft.memberId(),null,confirmedInput).id());
    }
    private void assertSiteHistoryImmutable(String table,String predicate,Map<String,Object> params,String mutation) {
        String target="marketplace."+table+" AS t";
        String select="SELECT to_jsonb(t)::text FROM "+target+" WHERE "+predicate;
        String original=jdbc.queryForObject(select,params,String.class);
        for(String sql:List.of("UPDATE "+target+" SET "+mutation+" WHERE "+predicate,"DELETE FROM "+target+" WHERE "+predicate)) {
            var rejected=org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    ()->jdbc.update(sql,params));
            var error=org.junit.jupiter.api.Assertions.assertInstanceOf(org.postgresql.util.PSQLException.class,rejected.getMostSpecificCause());
            assertEquals("23514",error.getSQLState());
            assertEquals("site history is immutable",java.util.Objects.requireNonNull(error.getServerErrorMessage()).getMessage());
            assertEquals(original,jdbc.queryForObject(select,params,String.class));
        }
    }
    private Map<String,String> moneyRows() {
        var result=new java.util.TreeMap<String,String>();
        for(String table:List.of("purchase_order","seller_order","order_item","payment_refund_attempt","payment_refund_budget","settlement_ledger","coupon_restoration")) {
            result.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));
        }
        return result;
    }
    @Test void bannerControlsActualNormalizedFileAndPreservesReplacedAssetAndFailedRevision() throws Exception {
        var op=supportFixture("site-banner","CONTENT").browser();var visitor=authBrowser();
        UUID id=UUID.randomUUID();String path="/api/admin/content/site-posts/"+id,publicPath="/api/site/posts/"+id;
        var draft=edit("BANNER",0,"DRAFT");draft.put("alt","첫 번째 배너");
        byte[] image=mediaPng();
        String saved=changeImage(op,path,draft,"first.png",image,200);
        String first=authMapper.readTree(saved).path("asset").asText();
        assertEquals(saved,changeImage(op,path,draft,"first.png",image,200));
        request(op,path+"/images/"+first,null,200);request(visitor,publicPath+"/images/"+first,null,404);
        request(visitor,"/api/media/"+first+"/content",null,404);
        var publish=edit("BANNER",1,"PUBLISHED");publish.put("alt","공개 배너");
        for(String url:List.of("//offsite","/admin/jobs","/events/%2e%2e","https://example.org","/products/a\\b")) {
            publish.put("destination",url);request(op,path,publish,400);
        }
        publish.put("destination","/events");request(op,path,publish,200);
        request(visitor,publicPath+"/images/"+first,null,200);
        var replace=edit("BANNER",2,"PUBLISHED");replace.put("alt","교체 배너");
        changeImage(op,path,replace,"broken.png",new byte[]{1,2,3},400);
        changeImage(op,path,replace,"../first.png",image,400);
        String second=authMapper.readTree(changeImage(op,path,replace,"next.png",image,200)).path("asset").asText();
        assertNotEquals(first,second);request(visitor,publicPath+"/images/"+first,null,404);
        request(visitor,publicPath+"/images/"+second,null,200);
        var archive=edit("BANNER",3,"ARCHIVED");archive.put("alt","보관 배너");request(op,path,archive,200);
        request(visitor,publicPath+"/images/"+second,null,404);
        assertEquals(2,jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM marketplace.media_site_image WHERE asset_id IN (?::uuid,?::uuid)",Integer.class,first,second));
        String before=request(op,path+"/history",null,200);
        assertSiteHistoryImmutable("site_post_revision","post_id=:id AND request_key=:key",Map.of("id",id,"key",publish.get("key")),"title=title||'!'");
        assertEquals(before,request(op,path+"/history",null,200));
    }
    private String changeImage(HttpClient client,String path,Map<String,Object> command,String filename,byte[] image,int status) throws Exception {
        var resp=imageResponse(client,path,command,filename,image);assertEquals(status,resp.statusCode(),resp.body());return resp.body();
    }
    private java.net.http.HttpResponse<String> imageResponse(HttpClient client,String path,Map<String,Object> command,String filename,byte[] image) throws Exception {
        String boundary="site"+UUID.randomUUID();var bytes=new java.io.ByteArrayOutputStream();
        String parts="--"+boundary+"\r\nContent-Disposition: form-data; name=\"command\"\r\nContent-Type: application/json\r\n\r\n"
                +authMapper.writeValueAsString(command)+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename
                +"\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        bytes.write(parts.getBytes(java.nio.charset.StandardCharsets.UTF_8));bytes.write(image);
        bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","multipart/form-data; boundary="+boundary).header("X-CSRF-TOKEN",csrfToken(client))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        return client.send(req,java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void currentContentAndSystemRightsAreRecheckedAfterActualMemberLockWait() throws Exception {
        for(String role:List.of("CONTENT","SYSTEM")) {
            var op=supportFixture("site-wait-"+role.toLowerCase(java.util.Locale.ROOT),role);
            String path=role.equals("SYSTEM")?"/api/admin/system/site-info":"/api/admin/content/site-posts?kind=FAQ";
            request(op.browser(),path,null,200);
            var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
            try(var gate=context.getBean(javax.sql.DataSource.class).getConnection()) {
                gate.setAutoCommit(false);
                try(var lock=gate.prepareStatement("SELECT id FROM marketplace.identity_user WHERE id=? FOR UPDATE")) {
                    lock.setObject(1,op.authority().member());lock.executeQuery().close();
                }
                try {
                    var waiting=pool.submit(()->accountRequest(op.browser(),"GET",path,null,null));
                    awaitExchangeBusinessLock("identity_user","site-"+role);
                    try(var pid=gate.createStatement();var row=pid.executeQuery("SELECT pg_backend_pid()")) {
                        assertTrue(row.next());
                        assertEquals(1,jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                                +"AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) AND query LIKE '%identity_user%FOR NO KEY UPDATE%'",
                                Integer.class,row.getInt(1)));
                    }
                    try(var revoke=gate.prepareStatement("DELETE FROM marketplace.admin_permission WHERE user_id=? AND permission=?")) {
                        revoke.setObject(1,op.authority().member());revoke.setString(2,role);assertEquals(1,revoke.executeUpdate());
                    }
                    gate.commit();var denied=waiting.get(10,java.util.concurrent.TimeUnit.SECONDS);
                    assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("supportGuide"));assertFalse(denied.body().contains("startsAt"));
                } finally { gate.rollback(); }
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void settingsAndFaqRejectControlCharactersWithoutChangingAuditOrReceipt() throws Exception {
        var op=supportFixture("site-text","CONTENT").browser();var sys=supportFixture("site-system","SYSTEM").browser();
        String path="/api/admin/content/site-posts/"+UUID.randomUUID();String guide="/api/admin/system/site-info";
        var body=edit("FAQ",0,"PUBLISHED");body.put("body","첫 줄\n둘째 줄");
        var bad=new HashMap<>(body);bad.put("body","첫 줄"+(char)0+"비공개");request(op,path,bad,400);
        assertEquals(0,authMapper.readTree(request(op,path+"/history",null,200)).size());
        assertEquals("첫 줄\n둘째 줄",authMapper.readTree(request(op,path,body,200)).path("body").asText());
        var initial=request(sys,guide,null,200);var revision=authMapper.readTree(initial).get(0).path("info").path("version").asLong();
        var valid=Map.of("key",UUID.randomUUID(),"expectedVersion",revision,"siteName","공개 이름","supportGuide","지원 안내\n다음 줄","reason","안내 변경");
        var invalid=new HashMap<String,Object>(valid);invalid.put("siteName","이름"+(char)7);request(sys,guide,invalid,400);
        assertEquals(initial,request(sys,guide,null,200));
        String receipt=request(sys,guide,valid,200);assertEquals(receipt,request(sys,guide,valid,200));
        assertSiteHistoryImmutable("site_info_revision","request_key=:key",Map.of("key",valid.get("key")),"site_name=site_name||'!'");
        assertEquals(authMapper.readTree(receipt),authMapper.readTree(request(authBrowser(),"/api/site/info",null,200)));
        assertTypedCommands(op,sys);
        String shipping="/api/admin/system/shipping-policy";String before=request(sys,shipping,null,200);
        request(sys,shipping,Map.of("key",UUID.randomUUID(),"expectedVersion",1,"baseFee",12,"freeThreshold",20,"reason","x"+(char)0),400);
        for(Object invalidFee:List.of(-1,3.75,"3000",2147483648L)) {
            request(sys,shipping,Map.of("key",UUID.randomUUID(),"expectedVersion",1,"baseFee",invalidFee,"freeThreshold",20,"reason","금액 검증"),400);
        }
        request(sys,shipping,Map.of("key",UUID.randomUUID(),"expectedVersion",1,"baseFee",3000,"freeThreshold",50000,"reason","범위 외","commission",0),400);
        assertEquals(before,request(sys,shipping,null,200));
    }
    private Map<String,String> siteRows() {
        var rows=new java.util.TreeMap<String,String>();
        for(String table:List.of("site_post","site_post_revision","site_info_revision","media_site_image")) {
            rows.put(table,jdbc.getJdbcTemplate().queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM marketplace."+table+" t",String.class));
        }
        return rows;
    }
    private void assertTypedCommands(HttpClient content,HttpClient system) throws Exception {
        var failures=new java.util.ArrayList<String>();
        for(String mode:List.of("notice-json","banner-multipart","site-info")) {
            boolean info=mode.equals("site-info"),image=mode.equals("banner-multipart");
            var actor=info?system:content;
            for(String field:info?List.of("expectedVersion","unknown"):List.of("expectedVersion","priority","kind","state","unknown")) {
                var variants=switch(field) {
                    case "kind","state" -> List.of("numeric-enum");
                    case "unknown" -> List.of("extra");
                    default -> List.of("missing","null","fraction","string");
                };
                for(String variant:variants) {
                    String path=info?"/api/admin/system/site-info":"/api/admin/content/site-posts/"+UUID.randomUUID();
                    long version=info?authMapper.readTree(request(system,path,null,200)).get(0).path("info").path("version").asLong():0;
                    var command=info?new HashMap<String,Object>(Map.of("key",UUID.randomUUID(),"expectedVersion",version,
                            "siteName","입력 검증","supportGuide","이용 안내","reason","형식 대조")):edit(image?"BANNER":"NOTICE",version,"DRAFT");
                    if(image) { command.put("alt","입력 검증 배너"); }
                    long integer=field.equals("priority")?1:version;
                    switch(variant) {
                        case "missing" -> command.remove(field);
                        case "null" -> command.put(field,null);
                        case "fraction" -> command.put(field,integer+0.5);
                        case "string" -> command.put(field,Long.toString(integer));
                        case "numeric-enum" -> command.put(field,image&&field.equals("kind")?2:0);
                        default -> command.put(field,"unexpected");
                    }
                    var before=siteRows();
                    var result=image?imageResponse(actor,path,command,"banner.png",mediaPng())
                            :accountRequest(actor,"POST",path,command,csrfToken(actor));
                    var after=siteRows();
                    var changed=before.keySet().stream().filter(table->!before.get(table).equals(after.get(table))).toList();
                    if(result.statusCode()!=400||!changed.isEmpty()) {
                        failures.add(mode+"/"+field+"/"+variant+" HTTP "+result.statusCode()+" changed="+changed);
                    }
                }
            }
        }
        assertEquals(List.of(),failures);
    }
    @Test void systemInspectsJobStatesWithoutPayloadAndCannotResetUnsupportedJobs() throws Exception {
        var system=supportFixture("site-job","SYSTEM").browser();var other=supportFixture("site-job-no","SUPPORT").browser();
        var jobs=context.getBean(com.example.marketplace.platform.api.Jobs.class);
        String secret="private-job-token-"+UUID.randomUUID();String type="E_READ_ONLY.v1";
        UUID id=jobs.enqueue(new com.example.marketplace.platform.api.Jobs.Command(type,secret+"key",secret+"cause",secret+"payload",Instant.EPOCH));
        String path="/api/admin/system/site-jobs/"+id;request(other,path,null,403);
        var claimed=jobs.claim(java.util.Set.of(type)).orElseThrow();assertEquals(id,claimed.id());
        String running=request(system,path,null,200);assertFalse(running.contains(secret));assertFalse(running.contains(claimed.ownerToken().toString()));
        assertEquals("RUNNING",authMapper.readTree(running).path("status").asText());
        assertTrue(jobs.fail(id,claimed.ownerToken(),true,"SECRET_INTERNAL_DIAGNOSTIC"));
        var before=jobs.find(id).orElseThrow();var metadata=request(system,path,null,200);assertFalse(metadata.contains("SECRET_INTERNAL"));assertFalse(metadata.contains(secret));
        assertEquals("PROCESSING_ERROR",authMapper.readTree(metadata).path("error").asText());
        var list=request(system,"/api/admin/system/site-jobs?status=FAILED",null,200);assertTrue(list.contains(id.toString()));assertFalse(list.contains(secret));
        request(system,"/api/admin/system/site-jobs?status=unregistered",null,400);
        assertEquals(before,jobs.find(id).orElseThrow());
    }
    @Test void concurrentReplayAndFailedPublicationCommitLeaveOneVisibleVersionAndReceipt() throws Exception {
        var op=supportFixture("site-atomic","CONTENT").browser();
        UUID id=UUID.randomUUID();String path="/api/admin/content/site-posts/"+id;
        var cmd=edit("NOTICE",0,"PUBLISHED");String csrf=csrfToken(op);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var calls=List.of(1,2).stream().map(n->pool.submit(()->{start.await();return accountRequest(op,"POST",path,cmd,csrf);})).toList();
            start.countDown();var first=calls.getFirst().get(10,java.util.concurrent.TimeUnit.SECONDS);
            var second=calls.getLast().get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200,first.statusCode());assertEquals(first.statusCode(),second.statusCode());assertEquals(first.body(),second.body());
        }
        String history=request(op,path+"/history",null,200);assertEquals(1,authMapper.readTree(history).size());
        var db=jdbc.getJdbcTemplate();var update=edit("NOTICE",1,"DRAFT");
        db.execute("CREATE FUNCTION marketplace.test_site_rollback() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test site hook'; END $$");
        try {
            db.execute("CREATE TRIGGER test_site_rollback BEFORE UPDATE ON marketplace.site_post FOR EACH ROW WHEN (NEW.id='"+id+"') EXECUTE FUNCTION marketplace.test_site_rollback()");
            request(op,path,update,500);assertEquals(history,request(op,path+"/history",null,200));
            assertEquals(1,authMapper.readTree(request(authBrowser(),"/api/site/posts/"+id,null,200)).path("version").asInt());
        } finally { db.execute("DROP TRIGGER IF EXISTS test_site_rollback ON marketplace.site_post");db.execute("DROP FUNCTION marketplace.test_site_rollback()"); }
        request(op,path,update,200);assertEquals(2,authMapper.readTree(request(op,path+"/history",null,200)).size());
    }
}
