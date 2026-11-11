package com.example.marketplace.dbtest;

import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.Browse;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.operations.infrastructure.ProductReports;
import com.example.marketplace.operations.infrastructure.ReportInvestigations;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportsIT extends PostgresContractFixture {
    private HttpClient member(String email) throws Exception {
        activeMember(email);
        var client=authBrowser();
        assertEquals(200,authRequest(client,"/api/auth/login",loginForm(email,SIGNUP_PASSWORD),csrfToken(client)).statusCode());
        return client;
    }
    private String product(String suffix) {
        var fixture=browseFixture(suffix,1200,5);
        return ((Products.Product)fixture.get("product")).slug();
    }

    private String post(HttpClient client,String path,Object data,int status) throws Exception {
        var response=accountRequest(client,"POST",path,data,csrfToken(client));
        assertEquals(status,response.statusCode(),response.body());
        return response.body();
    }
    private String get(HttpClient client,String path,int status) throws Exception {
        var response=accountRequest(client,"GET",path,null,null);
        assertEquals(status,response.statusCode(),path);
        return response.body();
    }
    private String id(String json,String field) throws Exception {return authMapper.readTree(json).path(field).asText();}
    private Map<String,Object> assign(long version,UUID operator){
        return Map.of("key",UUID.randomUUID(),"action","ASSIGN","operator",operator,"version",version);
    }

    @Test void privateAnswerRequiresBothAssignmentsAndNeverSubstitutesAReplacedAttachment() throws Exception {
        var product=browseFixture("c-secret",1200,3);
        String slug=((Products.Product)product.get("product")).slug();
        String sellerEmail=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",product.get("owner")),String.class);
        var seller=authBrowser();
        assertEquals(200,authRequest(seller,"/api/auth/login",loginForm(sellerEmail,SIGNUP_PASSWORD),csrfToken(seller)).statusCode());
        var author=member("private-reporter@example.com");
        var stranger=member("private-outsider@example.com");
        var investigator=supportFixture("c-dual","CONTENT");
        UUID operator=investigator.authority().member();
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:op,'SUPPORT')",Map.of("op",operator));
        String question=id(post(author,"/api/products/"+slug+"/questions",Map.of("key",UUID.randomUUID(),"body","비밀 문의","visibility","PRIVATE"),200),"id");
        String answer=id(post(seller,"/api/questions/"+question+"/answer",Map.of("key",UUID.randomUUID(),"body","비밀 원문","expectedVersion",1),200),"id");
        String target="/api/reports/content/ANSWER/"+question+"/"+answer+"/0";
        String questionTarget="/api/reports/content/QUESTION/"+question+"/"+question+"/0";
        String imageBase="/api/questions/"+question+"/messages/"+answer+"/images";
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4XmNgYGD4D8UQBgAd9AP95BvM/wAAAABJRU5ErkJggg==");
        var photo=upload(seller,imageBase+"/0?version=0&key="+UUID.randomUUID(),"file.png",png,csrfToken(seller));
        assertEquals(200,photo.statusCode(),photo.body());
        var key=UUID.randomUUID();
        var input=Map.of("key",key,"reason","비밀 사유","assetId",id(photo.body(),"asset"));
        post(stranger,target,input,404);post(author,questionTarget,input,404);
        post(stranger,"/api/reports/content/ANSWER/"+question+"/"+UUID.randomUUID()+"/0",input,404);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_report_evidence WHERE request_key=:key",Map.of("key",key),Integer.class));
        String report=id(post(author,target,input,200),"reportId");
        post(seller,questionTarget,Map.of("key",UUID.randomUUID(),"reason","작성자 대응"),200);
        String path="/api/admin/content/reports/"+report;
        String binary=path+"/evidence/"+key+"/image";
        var op=investigator.browser();
        get(op,path,404);
        post(op,path+"/commands",assign(0,operator),200);
        for(String url:List.of(path,path+"/source",binary)){get(op,url,404);}
        var supportOnly=supportFixture("c-only","SUPPORT");
        get(supportOnly.browser(),path,403);
        post(op,"/api/admin/support/assignments/"+question,Map.of("key",UUID.randomUUID(),"expectedVersion",0,"operator",operator),200);
        post(op,target,input,404);
        assertTrue(get(op,path,200).contains("비밀 사유"));assertTrue(get(op,path+"/source",200).contains("비밀 원문"));get(op,binary,200);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                var blocker=context.getBean(javax.sql.DataSource.class).getConnection()){
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM marketplace.content_product_question WHERE id=? FOR UPDATE")){
                lock.setObject(1,UUID.fromString(question));lock.executeQuery().close();
            }
            var waiting=pool.submit(()->accountRequest(op,"GET",path+"/source",null,null));
            var fileWaiting=pool.submit(()->accountRequest(op,"GET",binary,null,null));
            try{
                awaitJobCondition(()->jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                        AND query LIKE '%content_product_question%' AND query LIKE '%FOR SHARE%'
                        """,Map.of(),Integer.class)>=2,"both report source and binary wait for their message row");
                jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:op AND permission='CONTENT'",Map.of("op",operator));
                blocker.commit();
                var denied=waiting.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("비밀 원문"));
                assertEquals(403,fileWaiting.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode());
            }finally{blocker.rollback();}
        }
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:op,'CONTENT')",Map.of("op",operator));
        var other=supportFixture("c-other-investigator","CONTENT");
        post(op,path+"/commands",assign(1,other.authority().member()),200);
        for(String url:List.of(path,path+"/source",binary)){get(op,url,404);get(other.browser(),url,404);}
        post(other.browser(),path+"/commands",assign(2,operator),200);
        get(op,binary,200);
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:op AND permission='SUPPORT'",Map.of("op",operator));
        for(String url:List.of(path,path+"/source",binary)){get(op,url,404);}
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:op,'SUPPORT')",Map.of("op",operator));
        post(op,"/api/admin/support/assignments/"+question,Map.of("key",UUID.randomUUID(),"expectedVersion",1,"operator",supportOnly.authority().member()),200);
        for(String url:List.of(path,path+"/source",binary)){get(op,url,404);}
        jdbc.update("INSERT INTO marketplace.admin_permission VALUES(:op,'CONTENT')",Map.of("op",supportOnly.authority().member()));
        var queueRow=java.util.stream.StreamSupport.stream(authMapper.readTree(get(op,"/api/admin/content/reports",200)).spliterator(),false)
                .filter(row->report.equals(row.path("reportId").asText())).findFirst().orElseThrow();
        assertTrue(queueRow.path("mine").asBoolean());
        post(op,path+"/commands",assign(queueRow.path("version").asLong(),supportOnly.authority().member()),200);
        assertTrue(get(supportOnly.browser(),path,200).contains("비밀 사유"));
        assertTrue(get(supportOnly.browser(),path+"/source",200).contains("비밀 원문"));get(supportOnly.browser(),binary,200);
        post(supportOnly.browser(),path+"/commands",assign(queueRow.path("version").asLong()+1,operator),200);
        post(supportOnly.browser(),"/api/admin/support/assignments/"+question,Map.of("key",UUID.randomUUID(),"expectedVersion",2,"operator",operator),200);
        get(op,binary,200);
        var newer=upload(seller,imageBase+"/0?version=1&key="+UUID.randomUUID(),"new.png",png,csrfToken(seller));
        assertEquals(200,newer.statusCode());
        get(op,binary,404);get(op,path,200);
        assertFalse(get(op,"/api/admin/content/reports",200).contains("비밀"));
    }

    @Test void migrationPreservesOldEvidenceWithoutInferringVersionsFromTimestamps() {
        var ds=freshLegacyDatabase("report-evidence-upgrade");
        var target=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds);
        String schema="marketplace";
        var migration=org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("112").load();
        migration.migrate();
        UUID actor=UUID.randomUUID(),report=UUID.randomUUID();
        var p=Map.of("actor",actor,"report",report);
        target.update("INSERT INTO marketplace.identity_user(id,email,state,created_at) VALUES(:actor,'legacy-report@example.com','PENDING_EMAIL',now())",p);
        target.update("INSERT INTO marketplace.operations_report(id,reporter_id,target_kind,target_id,edition,state,version,assignee) VALUES(:report,:actor,'PRODUCT',:report,0,'CLOSED',5,:actor)",p);
        target.update("""
                INSERT INTO marketplace.operations_report_event(report_id,version,actor,request_key,command,previous_state,next_state,reason,occurred_at)
                SELECT :report,v,:actor,gen_random_uuid(),cmd,prev,nxt,'당시 판단',ts::timestamptz FROM (VALUES
                (1,'CLOSE','INVESTIGATING','CLOSED','2026-10-01'),(2,'REOPEN','CLOSED','INVESTIGATING','2026-10-02'),
                (4,'CLOSE','INVESTIGATING','CLOSED','2026-10-03'),(5,'ASSIGN','CLOSED','CLOSED','2026-10-04')) AS events(v,cmd,prev,nxt,ts)
                """,p);
        target.update("""
                INSERT INTO marketplace.operations_report_evidence(reporter_id,request_key,report_id,submitted_target,reason,outcome,received_at)
                SELECT :actor,gen_random_uuid(),:report,'PRODUCT',ts,'REOPEN_REQUESTED',ts::timestamptz
                FROM (VALUES('2026-10-03'),('2026-10-04')) AS evidence(ts)
                """,p);
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("114").load().migrate();
        var evidenceSql="SELECT * FROM marketplace.operations_report_evidence ORDER BY request_key";
        var auditSql="SELECT * FROM marketplace.operations_report_event ORDER BY version";
        var originalEvidence=target.queryForList(evidenceSql,p);
        var originalAudit=target.queryForList(auditSql,p);
        assertEquals(List.of(4L,5L),target.queryForList("SELECT case_version FROM marketplace.operations_report_evidence ORDER BY received_at",p,Long.class));
        var upgrade=org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
        assertPlannedCurrentUpgrade(upgrade,114);
        assertEquals(originalEvidence,target.queryForList(evidenceSql,p));
        assertEquals(originalAudit,target.queryForList(auditSql,p));
        assertEquals(List.of(0L,0L),target.queryForList("SELECT case_version FROM marketplace.operations_report_ordered_evidence WHERE report_id=:report",p,Long.class));
        // Exercise the real commands against the upgraded database with a fixed identity and visible product.
        var sessions=org.mockito.Mockito.mock(HistorySessions.class);
        var access=org.mockito.Mockito.mock(AccessPolicy.class);
        var browse=org.mockito.Mockito.mock(Browse.class);
        org.mockito.Mockito.when(sessions.lockReadable("upgrade",actor,1L)).thenReturn(true);
        org.mockito.Mockito.when(access.allowed(actor,AccessArea.CONTENT)).thenReturn(true);
        org.mockito.Mockito.when(browse.identify("upgrade-product")).thenReturn(report);
        var user=new ProductReports.Actor(actor,"upgrade",1L);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(ds));
        var investigation=new ReportInvestigations(target,sessions,access,null,null,browse,null);
        var intake=new ProductReports(target,browse,sessions,null,access,null);
        for(var row:originalEvidence){
            var cmd=new ReportInvestigations.Command(UUID.randomUUID(),5L,ReportInvestigations.Action.REOPEN,null,null,
                    "과거 근거",ReportInvestigations.ReopenBasis.NEW_EVIDENCE,(UUID)row.get("request_key"));
            var denied=org.junit.jupiter.api.Assertions.assertThrows(com.example.marketplace.order.api.OrderRejected.class,
                    ()->tx.execute(t->investigation.change(user,report,cmd)));
            assertEquals(409,denied.status());
        }
        UUID fresh=UUID.randomUUID();
        var receipt=tx.execute(t->intake.submit(user,"upgrade-product",new ProductReports.Submission(fresh,"검증된 후속 근거",null)));
        assertEquals("REOPEN_REQUESTED",receipt.outcome());
        var cmd=new ReportInvestigations.Command(UUID.randomUUID(),6L,ReportInvestigations.Action.REOPEN,null,null,
                "후속 근거 확인",ReportInvestigations.ReopenBasis.NEW_EVIDENCE,fresh);
        assertEquals("INVESTIGATING",tx.execute(t->investigation.change(user,report,cmd)).state());
    }

    @Test void investigationTransitionsReplayAndRollbackWithoutHidingTheTarget() throws Exception {
        var author=member("c-outcome@example.com");
        String slug=product("c-process");
        String target="/api/products/"+slug+"/reports";
        String report=id(post(author,target,Map.of("key",UUID.randomUUID(),"reason","신고 근거"),200),"reportId");
        String path="/api/admin/content/reports/"+report;
        String commands=path+"/commands";
        var a=supportFixture("c-assign-a","CONTENT");var b=supportFixture("c-assign-b","CONTENT");
        var tokenA=csrfToken(a.browser());var tokenB=csrfToken(b.browser());
        SupportFixture winner,other;
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(()->{start.await();return accountRequest(a.browser(),"POST",commands,assign(0,a.authority().member()),tokenA);});
            var two=pool.submit(()->{start.await();return accountRequest(b.browser(),"POST",commands,assign(0,b.authority().member()),tokenB);});
            start.countDown();int first=one.get().statusCode();int second=two.get().statusCode();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(first,second));
            winner=first==200?a:b;other=first==200?b:a;
        }
        var op=winner.browser();
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","조기 종결","version",1),409);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","START","version",1),200);
        var additional=Map.of("key",UUID.randomUUID(),"reason","종결 전 추가 근거");
        var extra=post(author,target,additional,200);
        assertEquals(extra,post(author,target,additional,200));
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","오래된 판단","version",2),409);
        var close=Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","처리 근거","version",3);
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.operations_report_event ADD CONSTRAINT deny_test_close CHECK(command<>'CLOSE')");
        try{post(op,commands,close,500);assertEquals(3,authMapper.readTree(get(op,path,200)).path("version").asInt());}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.operations_report_event DROP CONSTRAINT deny_test_close");}
        var closed=post(op,commands,close,200);assertEquals(closed,post(op,commands,close,200));
        assertEquals("VIOLATION",authMapper.readTree(get(author,"/api/reports/mine",200)).get(0).path("decision").asText());
        assertFalse(get(author,"/api/reports/mine",200).contains("처리 근거"));
        post(author,target,Map.of("key",UUID.randomUUID(),"reason","신고 근거"),409);
        var evidence=UUID.randomUUID();
        assertEquals("REOPEN_REQUESTED",id(post(author,target,Map.of("key",evidence,"reason","새 근거"),200),"outcome"));
        assertTrue(get(op,path,200).contains("CLOSED"));
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",5,"reason","분류 없음"),400);
        for(var invalid:List.of(additional.get("key"),UUID.randomUUID())){
            post(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",5,"reason","부적합","reopenBasis","NEW_EVIDENCE","evidenceKey",invalid),409);
        }
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",5,"reason","새 근거 확인","reopenBasis","NEW_EVIDENCE","evidenceKey",evidence),200);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","NO_VIOLATION","reason","재판정","version",6),200);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",7,"reason","지난 종결 근거","reopenBasis","NEW_EVIDENCE","evidenceKey",evidence),409);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",7,"reason","판단 오류 정정","reopenBasis","JUDGMENT_ERROR"),200);
        post(op,commands,assign(8,other.authority().member()),200);
        get(op,path,404);get(op,path+"/source",404);
        var detail=authMapper.readTree(get(other.browser(),path,200));
        assertEquals("INVESTIGATING",detail.path("state").asText());assertEquals(7,detail.path("history").size());
        assertEquals(evidence.toString(),detail.path("history").get(3).path("evidenceKey").asText());
        var ref=Map.of("id",UUID.fromString(report));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
                jdbc.update("UPDATE marketplace.operations_report_event SET reason='덮어쓰기' WHERE report_id=:id",ref));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
                jdbc.update("DELETE FROM marketplace.operations_report_evidence WHERE report_id=:id",ref));
        assertEquals(7,authMapper.readTree(get(other.browser(),path,200)).path("history").size());
        get(authBrowser(),"/api/browse/products/"+slug,200);
    }

    @Test void reviewGenerationAndChildKindMustMatchTheCurrentlyVisibleContent() throws Exception {
        var f=paymentFixture("c-original",false);
        var item=f.order().sellers().getFirst().items().getFirst();
        UUID product=jdbc.queryForObject("SELECT product_id FROM marketplace.catalog_sku WHERE id=:sku",Map.of("sku",item.skuId()),UUID.class);
        UUID review=UUID.randomUUID();
        var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",review).addValue("item",item.id())
                .addValue("member",f.authority().member()).addValue("product",product).addValue("store",f.order().sellers().getFirst().storeId());
        jdbc.update("INSERT INTO marketplace.content_review(id,order_item_id,member_id,product_id,store_id,rating,body) VALUES(:id,:item,:member,:product,:store,4,'원래 후기')",p);
        String root="/api/reports/content/";
        var author=f.browser();
        var one=Map.of("key",UUID.randomUUID(),"reason","본문 근거");
        String first=id(post(author,root+"REVIEW/"+review+"/"+review+"/1",one,200),"reportId");
        for(String kind:List.of("COMMENT","SELLER_REPLY")){
            UUID child=UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace.content_review_response(id,review_id,edition,member_id,kind,body) VALUES(:child,:id,1,:member,:kind,'이전 세대')",p.addValue("child",child).addValue("kind",kind));
            post(author,root+kind+"/"+review+"/"+child+"/1",Map.of("key",UUID.randomUUID(),"reason","자식"),200);
            post(author,root+(kind.equals("COMMENT")?"SELLER_REPLY":"COMMENT")+"/"+review+"/"+child+"/1",Map.of("key",UUID.randomUUID(),"reason","변조"),404);
        }
        var op=supportFixture("c-generation","CONTENT");
        String path="/api/admin/content/reports/"+first;
        post(op.browser(),path+"/commands",assign(0,op.authority().member()),200);get(op.browser(),path+"/source",200);
        jdbc.update("UPDATE marketplace.content_review SET author_deleted=true WHERE id=:id",p);
        get(op.browser(),path,200);get(op.browser(),path+"/source",404);
        jdbc.update("UPDATE marketplace.content_review SET author_deleted=false, edition=2,body='새 세대' WHERE id=:id",p);
        get(op.browser(),path+"/source",404);
        post(author,root+"REVIEW/"+review+"/"+review+"/1",Map.of("key",UUID.randomUUID(),"reason","변조"),404);
        String second=id(post(author,root+"REVIEW/"+review+"/"+review+"/2",Map.of("key",UUID.randomUUID(),"reason","새 근거"),200),"reportId");
        assertFalse(first.equals(second));
        assertEquals(first,id(post(author,root+"REVIEW/"+review+"/"+review+"/1",one,200),"reportId"));
    }

    @Test void productReportsSerializeReplaysSeparateMembersAndKeepQueueMinimal() throws Exception {
        String slug=product("report-first");
        String path="/api/products/"+slug+"/reports";
        var author=member("c-reporter@example.com");
        var other=member("c-other@example.com");
        var guest=authBrowser();
        var first=Map.of("key",UUID.randomUUID(),"reason","비공개 신고 사유");
        assertEquals(401,accountRequest(guest,"POST",path,first,csrfToken(guest)).statusCode());
        assertEquals(403,accountRequest(author,"POST",path,first,null).statusCode());
        String csrf=csrfToken(author);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();return accountRequest(author,"POST",path,first,csrf);});
            var b=pool.submit(()->{start.await();return accountRequest(author,"POST",path,first,csrf);});
            start.countDown();
            var receipt=a.get();var replay=b.get();
            assertEquals(200,receipt.statusCode(),receipt.body());assertEquals(200,replay.statusCode(),replay.body());
            assertEquals(receipt.body(),replay.body());
            String id=authMapper.readTree(receipt.body()).path("reportId").asText();
            var added=accountRequest(author,"POST",path,Map.of("key",UUID.randomUUID(),"reason","추가 근거"),csrf);
            assertEquals(200,added.statusCode(),added.body());
            assertEquals(id,authMapper.readTree(added.body()).path("reportId").asText());
            assertEquals("EVIDENCE_ADDED",authMapper.readTree(added.body()).path("outcome").asText());
            assertEquals(409,accountRequest(author,"POST",path,Map.of("key",first.get("key"),"reason","변조"),csrf).statusCode());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_report_evidence WHERE report_id=:id",Map.of("id",UUID.fromString(id)),Integer.class));
            assertEquals("[]",accountRequest(other,"GET","/api/reports/mine",null,null).body());
            assertFalse(accountRequest(other,"POST",path,first,csrfToken(other)).body().contains(id));
            var reviewer=supportFixture("c-review","CONTENT");
            var queue=accountRequest(reviewer.browser(),"GET","/api/admin/content/reports",null,null);
            assertEquals(200,queue.statusCode(),queue.body());assertTrue(queue.body().contains(id));
            assertFalse(queue.body().contains("비공개 신고 사유"));assertFalse(queue.body().contains(slug));
            assertTrue(get(reviewer.browser(),"/api/admin/content/reports?state=RECEIVED",200).contains(id));
            assertFalse(get(reviewer.browser(),"/api/admin/content/reports?state=CLOSED",200).contains(id));
            assertFalse(get(reviewer.browser(),"/api/admin/content/reports?page=1",200).contains(id));
            get(reviewer.browser(),"/api/admin/content/reports?state=INVALID",400);
            var support=supportFixture("c-no-content","SUPPORT");
            assertEquals(403,accountRequest(support.browser(),"GET","/api/admin/content/reports",null,null).statusCode());
            assertEquals(200,accountRequest(guest,"GET","/api/browse/products/"+slug,null,null).statusCode());
        }
    }

    @Test void rollingQuotaCountsOnlyNewCasesAndReleasesExpiredEntries() throws Exception {
        var author=member("c-quota@example.com");
        String csrf=csrfToken(author);
        String first=null,last=null;
        for(int i=0;i<=10;i++){
            String path="/api/products/"+product("c-quota-"+i)+"/reports";
            var response=accountRequest(author,"POST",path,Map.of("key",UUID.randomUUID(),"reason","사유"),csrf);
            assertEquals(i<10?200:429,response.statusCode(),response.body());
            if(i==0){first=path;}
            last=path;
        }
        assertEquals(200,accountRequest(author,"POST",first,Map.of("key",UUID.randomUUID(),"reason","추가 근거"),csrf).statusCode());
        assertEquals(404,accountRequest(author,"POST",first,Map.of("key",UUID.randomUUID(),"reason","잘못된 첨부","assetId",UUID.randomUUID()),csrf).statusCode());
        jdbc.update("UPDATE marketplace.operations_report SET created_at=clock_timestamp()-interval '24 hours' WHERE id=(SELECT id FROM marketplace.operations_report WHERE reporter_id=(SELECT id FROM marketplace.identity_user WHERE email=:email) ORDER BY created_at LIMIT 1)",Map.of("email","c-quota@example.com"));
        assertEquals(200,accountRequest(author,"POST",last,Map.of("key",UUID.randomUUID(),"reason","새 접수"),csrf).statusCode());
    }
    @Test void intakeExpiryDuringCaseLockAddsNeitherEvidenceNorQuota() throws Exception {
        var user=supportFixture("c-intake-expiry","CONTENT");
        var a=user.authority();
        String slug=product("c-wait");
        String id=id(post(user.browser(),"/api/products/"+slug+"/reports",Map.of("key",UUID.randomUUID(),"reason","최초"),200),"reportId");
        var clock=new JobClock();
        clock.now=java.time.Instant.now();
        long expires=jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:id",Map.of("id",a.browser()),Long.class);
        var service=new com.example.marketplace.operations.infrastructure.ProductReports(jdbc,
                context.getBean(com.example.marketplace.catalog.api.Browse.class),
                new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock),
                context.getBeanProvider(com.example.marketplace.media.api.Media.class),
                context.getBean(com.example.marketplace.identity.api.AccessPolicy.class),
                context.getBean(com.example.marketplace.operations.api.ReportTargets.class));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                var blocker=context.getBean(javax.sql.DataSource.class).getConnection()){
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM marketplace.operations_report WHERE id=? FOR UPDATE")){
                lock.setObject(1,UUID.fromString(id));lock.executeQuery().close();
            }
            var result=pool.submit(()->org.junit.jupiter.api.Assertions.assertThrows(com.example.marketplace.order.api.OrderRejected.class,
                    ()->tx.execute(t->service.submit(new com.example.marketplace.operations.infrastructure.ProductReports.Actor(a.member(),a.browser(),a.version()),
                            slug,new com.example.marketplace.operations.infrastructure.ProductReports.Submission(UUID.randomUUID(),"만료 후 근거",null)))).status());
            try{
                awaitCouponLock("operations_report");
                clock.now=java.time.Instant.ofEpochMilli(expires);
                blocker.commit();assertEquals(403,result.get(10,java.util.concurrent.TimeUnit.SECONDS));
            }finally{blocker.rollback();}
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_report_evidence WHERE report_id=:id",Map.of("id",UUID.fromString(id)),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_report WHERE reporter_id=:member",Map.of("member",a.member()),Integer.class));
    }
}
