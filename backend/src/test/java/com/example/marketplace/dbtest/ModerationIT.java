package com.example.marketplace.dbtest;

import com.example.marketplace.catalog.api.Products;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationIT extends PostgresContractFixture {
    private String post(HttpClient user,String path,Object data,int status)throws Exception{
        var r=accountRequest(user,"POST",path,data,csrfToken(user));assertEquals(status,r.statusCode(),r.body());return r.body();
    }
    private String get(HttpClient user,String path,int status)throws Exception{
        var r=accountRequest(user,"GET",path,null,null);assertEquals(status,r.statusCode(),r.body());return r.body();
    }
    private String adjudicate(HttpClient reporter,SupportFixture op,String target) throws Exception {
        String report=authMapper.readTree(post(reporter,target,Map.of("key",UUID.randomUUID(),"reason","신고 근거"),200)).get("reportId").asText();
        String base="/api/admin/content/reports/"+report;
        post(op.browser(),base+"/commands",Map.of("action","ASSIGN","operator",op.authority().member(),"key",UUID.randomUUID(),"version",0),200);
        post(op.browser(),base+"/commands",Map.of("action","START","key",UUID.randomUUID(),"version",1),200);
        post(op.browser(),base+"/commands",Map.of("action","CLOSE","decision","VIOLATION","reason","위반 확인","key",UUID.randomUUID(),"version",2),200);
        return base+"/moderation";
    }
    private Map<String,Object> command(HttpClient op,String path,boolean hide) throws Exception {
        var view=authMapper.readTree(get(op,path,200));
        var data=new java.util.HashMap<String,Object>();
        data.put("key",UUID.randomUUID());data.put("hide",hide);data.put("reason",hide?"위반으로 숨김":"다시 확인해 복원");
        for(String prop:java.util.List.of("caseVersion","closeVersion","targetVersion","edition")){
            data.put(prop,view.get(prop).isNull()?null:view.get(prop).asLong());
        }
        data.put("sourceHide",view.get("activeHide").asText(null));
        return data;
    }
    @Test void reviewFamilyRestoresOnlyCurrentVisibleChildrenAndScores() throws Exception {
        var buyer=paymentFixture("d-review-family",false);
        var shipment=paidShipment(buyer);var sold=shipment.units().getFirst();
        completeShipment(buyer,shipment,java.time.Instant.now().minusSeconds(10));
        String number=buyer.order().orderNumber();var auth=shipmentAuthority(buyer);
        confirmations().confirm(number,auth,new com.example.marketplace.order.api.OrderConfirmations.Command("d-review-confirm",
                confirmations().get(number,auth).revision(),java.util.List.of(new com.example.marketplace.order.api.OrderConfirmations.Unit(sold.orderItemId(),sold.ordinal()))));
        String edit="/api/orders/"+number+"/items/"+sold.orderItemId()+"/review";
        String id=authMapper.readTree(post(buyer.browser(),edit,Map.of("key",UUID.randomUUID(),"action","CREATE","expectedVersion",0,"body","원래 본문","rating",5),200)).get("reviewId").asText();
        var slug=jdbc.queryForObject("SELECT p.slug FROM marketplace.content_review r JOIN marketplace.catalog_product p ON p.id=r.product_id WHERE r.id=:id",
                Map.of("id",UUID.fromString(id)),String.class);
        var visitor=authBrowser();String detail="/api/reviews/"+id,list="/api/products/"+slug+"/reviews";
        var image=upload(buyer.browser(),edit+"/images/0?key="+UUID.randomUUID()+"&version=1","a.png",mediaPng(),csrfToken(buyer.browser()));
        assertEquals(200,image.statusCode(),image.body());
        String photo=detail+"/images/"+authMapper.readTree(get(visitor,detail,200)).get("images").get(0).get("id").asText();
        var op=supportFixture("d-family-op","CONTENT");
        String reviewCase=adjudicate(buyer.browser(),op,"/api/reports/content/REVIEW/"+id+"/"+id+"/1");
        var children=new java.util.ArrayList<String>();
        for(String kind:java.util.List.of("COMMENT","SELLER_REPLY")){
            String child=authMapper.readTree(post(buyer.browser(),detail+"/responses",Map.of("key",UUID.randomUUID(),"kind",kind,"action","CREATE",
                    "expectedVersion",0,"edition",1,"body",kind+" 본문"),200)).get("id").asText();
            children.add(adjudicate(buyer.browser(),op,"/api/reports/content/"+kind+"/"+id+"/"+child+"/1"));
        }
        for(String childCase:children){
            var expected=authMapper.readTree(get(visitor,list,200));
            String childId=authMapper.readTree(get(op.browser(),childCase,200)).path("targetId").asText();
            var changed=java.util.stream.StreamSupport.stream(expected.get("reviews").get(0).get("responses").spliterator(),false)
                    .filter(node->childId.equals(node.get("id").asText())).findFirst().orElseThrow();
            ((tools.jackson.databind.node.ObjectNode)changed).put("version",changed.path("version").asLong()+2);
            post(op.browser(),childCase,command(op.browser(),childCase,true),200);
            var partial=authMapper.readTree(get(visitor,list,200));
            assertEquals(1,partial.get("count").asInt());assertEquals(5,partial.get("ratingSum").asInt());
            assertEquals(1,partial.get("reviews").get(0).get("responses").size());get(visitor,photo,200);
            org.junit.jupiter.api.Assertions.assertNotEquals(childId,partial.get("reviews").get(0).get("responses").get(0).get("id").asText());
            post(op.browser(),childCase,command(op.browser(),childCase,false),200);
            assertEquals(authMapper.readTree(expected.toString()),authMapper.readTree(get(visitor,list,200)));
        }
        String childCase=children.getFirst();
        var hideChild=command(op.browser(),childCase,true);
        String childReceipt=post(op.browser(),childCase,hideChild,200);
        var hideReview=command(op.browser(),reviewCase,true);
        post(op.browser(),reviewCase,hideReview,200);
        var restoreChild=command(op.browser(),childCase,false);
        String restoredChild=post(op.browser(),childCase,restoreChild,200);
        get(visitor,detail,404);get(visitor,photo,404);
        assertEquals(0,authMapper.readTree(get(visitor,list,200)).get("count").asInt());
        assertEquals(0,authMapper.readTree(get(visitor,list,200)).get("ratingSum").asInt());
        var hidden=authMapper.readTree(get(op.browser(),reviewCase,200));
        var delete=authMapper.readTree(post(buyer.browser(),edit,Map.of("key",UUID.randomUUID(),"action","DELETE","expectedVersion",hidden.get("targetVersion").asLong()),200));
        // A restore on an author-deleted review cannot republish it.
        post(op.browser(),reviewCase,command(op.browser(),reviewCase,false),200);
        get(visitor,detail,404);get(visitor,photo,404);
        var hiddenAgain=command(op.browser(),reviewCase,true);post(op.browser(),reviewCase,hiddenAgain,200);
        long version=delete.get("version").asLong()+2;
        post(buyer.browser(),edit,Map.of("key",UUID.randomUUID(),"action","REWRITE","expectedVersion",version,"body","새 본문","rating",2),200);
        get(visitor,detail,404);get(visitor,photo,404);
        var restore=command(op.browser(),reviewCase,false);
        assertEquals(2L,restore.get("edition"));
        var wrong=new java.util.HashMap<>(restore);wrong.put("edition",1);post(op.browser(),reviewCase,wrong,409);
        String productCase=adjudicate(buyer.browser(),op,"/api/products/"+slug+"/reports");
        post(op.browser(),productCase,command(op.browser(),productCase,true),200);
        post(op.browser(),reviewCase,restore,200);
        get(visitor,list,404);get(visitor,detail,404);get(visitor,photo,404);
        post(op.browser(),productCase,command(op.browser(),productCase,false),200);
        var rewritten=authMapper.readTree(get(visitor,list,200));assertEquals(2,rewritten.get("ratingSum").asInt());
        assertEquals(1,rewritten.get("count").asInt());assertEquals(0,rewritten.get("reviews").get(0).get("responses").size());
        get(visitor,photo,404);assertEquals(0,rewritten.get("reviews").get(0).get("images").size());
        assertEquals(childReceipt,post(op.browser(),childCase,hideChild,200));
        assertEquals(restoredChild,post(op.browser(),childCase,restoreChild,200));
        for(String oldCase:children){
            var archived=authMapper.readTree(get(op.browser(),oldCase,200));
            assertTrue(archived.get("targetVersion").isNull());assertTrue(archived.get("hidden").isNull());
            assertTrue(!archived.get("canHide").asBoolean()&&!archived.get("canRestore").asBoolean());
            assertTrue(archived.get("history").size()>=2);
            var event=archived.get("history").get(0);
            assertTrue(event.hasNonNull("id")&&event.hasNonNull("occurredAt")&&event.hasNonNull("actor"));
            assertEquals("위반으로 숨김",event.path("reason").asText());
            var staleCommand=new java.util.HashMap<>(hideChild);staleCommand.put("key",UUID.randomUUID());
            post(op.browser(),oldCase,staleCommand,409);
        }
        post(op.browser(),reviewCase,command(op.browser(),reviewCase,true),409);
        for(boolean submitRewriteFirst:java.util.List.of(true,false)){
            long edition=authMapper.readTree(get(visitor,detail,200)).get("edition").asLong();
            String currentCase=adjudicate(buyer.browser(),op,"/api/reports/content/REVIEW/"+id+"/"+id+"/"+edition);
            post(op.browser(),currentCase,command(op.browser(),currentCase,true),200);
            long v=authMapper.readTree(get(op.browser(),currentCase,200)).get("targetVersion").asLong();
            post(buyer.browser(),edit,Map.of("key",UUID.randomUUID(),"action","DELETE","expectedVersion",v),200);
            raceAuthorRewriteAndRestore(buyer.browser(),op.browser(),edit,currentCase,id,list,edition,submitRewriteFirst);
        }
    }
    private void raceAuthorRewriteAndRestore(HttpClient author,HttpClient op,String edit,String moderation,String id,String list,
            long edition,boolean rewriteFirst)throws Exception{
        var restore=command(op,moderation,false);
        long version=(Long)restore.get("targetVersion");
        var rewrite=Map.of("key",UUID.randomUUID(),"action","REWRITE","expectedVersion",version,"body","경합 후 새 본문","rating",4);
        var key=Map.of("id",UUID.fromString(id));
        String count="SELECT count(*) FROM marketplace.operations_moderation_action WHERE target_id=:id";
        int before=jdbc.queryForObject(count,key,Integer.class);
        String authorCsrf=csrfToken(author),adminCsrf=csrfToken(op);
        java.util.concurrent.Callable<Integer> authorWrite=()->accountRequest(author,"POST",edit,rewrite,authorCsrf).statusCode();
        java.util.concurrent.Callable<Integer> adminWrite=()->accountRequest(op,"POST",moderation,restore,adminCsrf).statusCode();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();var blocker=context.getBean(javax.sql.DataSource.class).getConnection()){
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM marketplace.content_review WHERE id=? FOR UPDATE")){
                lock.setObject(1,UUID.fromString(id));lock.executeQuery().close();
            }
            try{
                var first=pool.submit(rewriteFirst?authorWrite:adminWrite);
                waitForReviewLock(1);
                var second=pool.submit(rewriteFirst?adminWrite:authorWrite);
                waitForReviewLock(2);blocker.commit();
                assertEquals(200,first.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(409,second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            }finally{blocker.rollback();}
        }
        var row=jdbc.queryForMap("SELECT edition,version,author_deleted,hidden_by_admin FROM marketplace.content_review WHERE id=:id",key);
        assertEquals(version+1,row.get("version"));
        assertEquals(rewriteFirst?edition+1:edition,row.get("edition"));
        assertEquals(!rewriteFirst,row.get("author_deleted"));assertEquals(rewriteFirst,row.get("hidden_by_admin"));
        assertEquals(before+(rewriteFirst?0:1),jdbc.queryForObject(count,key,Integer.class));
        var invisible=authMapper.readTree(get(authBrowser(),list,200));
        assertEquals(0,invisible.get("count").asInt());assertEquals(0,invisible.get("ratingSum").asInt());
        if(rewriteFirst){post(op,moderation,command(op,moderation,false),200);}
        else{post(author,edit,Map.of("key",UUID.randomUUID(),"action","REWRITE","expectedVersion",version+1,"body","다시 작성","rating",4),200);}
        var visible=authMapper.readTree(get(authBrowser(),list,200));
        assertEquals(1,visible.get("count").asInt());assertEquals(4,visible.get("ratingSum").asInt());
    }
    private void waitForReviewLock(int expected) throws Exception {
        awaitJobCondition(()->jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                AND query LIKE '%FROM marketplace.content_review%' AND query LIKE '%FOR UPDATE%'
                """,Map.of(),Integer.class)>=expected,"review commands waiting for the row: "+expected);
    }
    @Test void twoCasesCompeteOnceAndExistingTradeSurvivesHiddenProduct() throws Exception {
        var buyer=paymentFixture("d-trade",false);var shipment=paidShipment(buyer);var unit=shipment.units().getFirst();
        var sellerOrder=buyer.order().sellers().stream().filter(s->s.items().stream().anyMatch(i->i.id().equals(unit.orderItemId()))).findFirst().orElseThrow();
        var item=sellerOrder.items().stream().filter(i->i.id().equals(unit.orderItemId())).findFirst().orElseThrow();
        var product=jdbc.queryForMap("""
                SELECT p.id,p.slug,p.title,u.email FROM marketplace.catalog_product p JOIN marketplace.catalog_sku k ON k.product_id=p.id
                JOIN marketplace.seller_store s ON s.id=p.store_id JOIN marketplace.identity_user u ON u.id=s.owner_id WHERE k.id=:sku
                """,Map.of("sku",item.skuId()));
        String slug=(String)product.get("slug"),target="/api/products/"+slug+"/reports";
        var a=supportFixture("d-compete-a","CONTENT");var b=supportFixture("d-compete-b","CONTENT");
        String one=adjudicate(buyer.browser(),a,target),two=adjudicate(b.browser(),b,target);
        var seller=authBrowser();
        assertEquals(200,authRequest(seller,"/api/auth/login",loginForm((String)product.get("email"),SIGNUP_PASSWORD),csrfToken(seller)).statusCode());
        var uploaded=mediaUpload(seller,"/api/media/products/"+product.get("id")+"/0",mediaPng(),"item.png",0,"상품 사진",true);
        assertEquals(200,uploaded.statusCode(),uploaded.body());
        String asset=authMapper.readTree(uploaded.body()).get("assetId").asText(),binary="/api/media/"+asset+"/content",preview="/api/media/owner/"+asset+"/preview";
        var visitor=authBrowser();get(visitor,binary,200);
        String order="/api/orders/"+buyer.order().orderNumber();
        String snapshot=get(buyer.browser(),order,200),shipping=get(buyer.browser(),order+"/shipments",200);
        String search="/api/browse/products?q="+java.net.URLEncoder.encode(product.get("title").toString(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(get(visitor,search,200).contains(slug));
        var quote=Map.of("lines",java.util.List.of(Map.of("skuId",item.skuId(),"quantity",1)),"coupons",java.util.List.of());
        post(buyer.browser(),"/api/quotes",quote,200);
        var first=command(a.browser(),one,true);var second=command(b.browser(),two,true);
        String tokenA=csrfToken(a.browser()),tokenB=csrfToken(b.browser());
        boolean firstWon;
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();var blocker=context.getBean(javax.sql.DataSource.class).getConnection()){
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM marketplace.catalog_product WHERE id=? FOR UPDATE")){
                lock.setObject(1,product.get("id"));lock.executeQuery().close();
            }
            var left=pool.submit(()->accountRequest(a.browser(),"POST",one,first,tokenA));
            var right=pool.submit(()->accountRequest(b.browser(),"POST",two,second,tokenB));
            try{
                awaitJobCondition(()->jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                        AND query LIKE 'SELECT version,is_hidden_by_admin FROM marketplace.catalog_product%FOR UPDATE%'
                        """,Map.of(),Integer.class)>=2,"both moderation cases wait for the same product");
                blocker.commit();
                int l=left.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode(),r=right.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode();
                assertEquals(java.util.Set.of(200,409),java.util.Set.of(l,r));firstWon=l==200;
            }finally{blocker.rollback();}
        }
        var winner=firstWon?a:b;String winningPath=firstWon?one:two;
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_moderation_action WHERE target_id=:id",product,Integer.class));
        get(visitor,"/api/browse/products/"+slug,404);get(visitor,"/api/products/"+slug+"/reviews",404);
        get(visitor,binary,404);get(seller,binary,404);get(seller,preview,200);get(b.browser(),preview,404);
        org.junit.jupiter.api.Assertions.assertFalse(get(visitor,search,200).contains(slug));
        post(buyer.browser(),"/api/quotes",quote,409);
        org.junit.jupiter.api.Assertions.assertNull(context.getBean(com.example.marketplace.catalog.api.Browse.class).quoteOffer(item.skuId()));
        assertEquals(snapshot,get(buyer.browser(),order,200));assertEquals(shipping,get(buyer.browser(),order+"/shipments",200));
        String inquiry=post(buyer.browser(),"/api/support/cases",Map.of("key",UUID.randomUUID(),"body","기존 주문 문의","orderNumber",buyer.order().orderNumber(),"sellerOrderId",sellerOrder.id()),200);
        assertTrue(authMapper.readTree(inquiry).hasNonNull("id"));
        var claim=acceptCancellation(buyer,java.util.List.of(Map.of("itemId",item.id(),"ordinal",unit.ordinal())),"d-hidden-existing");
        assertTrue(claim.hasNonNull("claimId"));
        post(winner.browser(),winningPath,command(winner.browser(),winningPath,false),200);
        get(visitor,binary,200);get(visitor,"/api/browse/products/"+slug,200);
        assertTrue(get(visitor,search,200).contains(slug));post(buyer.browser(),"/api/quotes",quote,200);
    }
    @Test void migratedHideRestoresOnceWithSeparateAudit() throws Exception {
        var product=(Products.Product)browseFixture("d-legacy",1200,2).get("product");
        var operator=supportFixture("d-legacy-op","CONTENT");
        var ds=freshLegacyDatabase("d-legacy-product");
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds);
        var migrations=org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("marketplace").defaultSchema("marketplace").locations("classpath:db/migration");
        migrations.target("115").load().migrate();
        var p=Map.of("id",product.id(),"actor",operator.authority().member());
        copyLegacyRows(named,"identity_user","SELECT u.* FROM marketplace.identity_user u WHERE u.id=:actor OR u.id IN (SELECT s.owner_id FROM marketplace.seller_store s JOIN marketplace.catalog_product p ON p.store_id=s.id WHERE p.id=:id)",p);
        copyLegacyRows(named,"seller_store","SELECT s.* FROM marketplace.seller_store s JOIN marketplace.catalog_product p ON p.store_id=s.id WHERE p.id=:id",p);
        copyLegacyRows(named,"catalog_category","SELECT c.* FROM marketplace.catalog_category c JOIN marketplace.catalog_product p ON c.id=p.category_id WHERE p.id=:id",p);
        copyLegacyRows(named,"catalog_product","SELECT * FROM marketplace.catalog_product WHERE id=:id",p);
        named.update("UPDATE marketplace.catalog_product SET is_hidden_by_admin=true WHERE id=:id",p);
        var upgrade=org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("marketplace").defaultSchema("marketplace").locations("classpath:db/migration").load();
        assertPlannedCurrentUpgrade(upgrade,115);
        var session=org.mockito.Mockito.mock(com.example.marketplace.identity.api.HistorySessions.class);
        var access=org.mockito.Mockito.mock(com.example.marketplace.identity.api.AccessPolicy.class);
        var authority=operator.authority();
        org.mockito.Mockito.when(session.lockReadable(authority.browser(),authority.member(),authority.version())).thenReturn(true);
        org.mockito.Mockito.when(access.allowed(authority.member(),com.example.marketplace.identity.api.AccessArea.CONTENT)).thenReturn(true);
        var reports=new com.example.marketplace.operations.infrastructure.ReportInvestigations(named,session,access,null,null,null,null);
        var actions=new com.example.marketplace.operations.infrastructure.ModerationActions(named,reports,new com.example.marketplace.catalog.infrastructure.JdbcProductVisibility(named),null);
        var actor=new com.example.marketplace.operations.infrastructure.ProductReports.Actor(authority.member(),authority.browser(),authority.version());
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(ds));
        var before=tx.execute(t->actions.view(actor,null,product.id()));assertTrue(before.canRestore());
        var restore=new com.example.marketplace.operations.infrastructure.ModerationActions.Command(UUID.randomUUID(),false,null,null,0L,before.targetVersion(),null,"기존 숨김 복원");
        var receipt=tx.execute(t->actions.apply(actor,null,product.id(),restore));
        assertEquals(receipt,tx.execute(t->actions.apply(actor,null,product.id(),restore)));
        var after=tx.execute(t->actions.view(actor,null,product.id()));org.junit.jupiter.api.Assertions.assertFalse(after.hidden());
        assertEquals(before.targetVersion()+1,after.targetVersion());
        assertEquals(1,named.queryForObject("SELECT count(*) FROM marketplace.operations_moderation_action WHERE target_id=:id AND report_id IS NULL AND before_hidden AND NOT after_hidden AND reason='기존 숨김 복원'",p,Integer.class));
    }
    @Test void productActionUsesCurrentClosureAndRollsBackWithItsAudit()throws Exception{
        var product=(Products.Product)browseFixture("d-action",1300,3).get("product");
        var investigator=supportFixture("d-action-op","CONTENT");
        var reporter=supportFixture("d-action-member","SUPPORT");
        var op=investigator.browser();
        var receipt=authMapper.readTree(post(reporter.browser(),"/api/products/"+product.slug()+"/reports",
                Map.of("key",UUID.randomUUID(),"reason","신고"),200));
        String report=receipt.get("reportId").asText(),path="/api/admin/content/reports/"+report;
        String actions=path+"/moderation",commands=path+"/commands",published="/api/browse/products/"+product.slug();
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","ASSIGN","operator",investigator.authority().member(),"version",0),200);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","START","version",1),200);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","판단","version",2),200);
        var view=authMapper.readTree(get(op,actions,200));
        long version=view.get("targetVersion").asLong();
        assertTrue(view.get("canHide").asBoolean());
        var hide=Map.of("key",UUID.randomUUID(),"hide",true,"caseVersion",3,"closeVersion",3,"edition",0,"targetVersion",version,"reason","조치 근거");
        post(reporter.browser(),actions,hide,403);
        var other=supportFixture("d-action-foreign","CONTENT");
        post(other.browser(),actions,hide,404);get(other.browser(),actions,404);
        for(String property:java.util.List.of("caseVersion","closeVersion","edition","targetVersion")){
            var stale=new java.util.HashMap<String,Object>(hide);stale.put(property,9999);post(op,actions,stale,409);
        }
        var ref=Map.of("id",UUID.fromString(report));
        jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.operations_moderation_action ADD CONSTRAINT fail_test_hide CHECK(NOT after_hidden)");
        try{post(op,actions,hide,500);get(authBrowser(),published,200);assertEquals(version,authMapper.readTree(get(op,actions,200)).get("targetVersion").asLong());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_moderation_action WHERE report_id=:id",ref,Integer.class));}
        finally{jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.operations_moderation_action DROP CONSTRAINT fail_test_hide");}
        String first=post(op,actions,hide,200);
        assertEquals(first,post(op,actions,hide,200));get(authBrowser(),published,404);
        var changed=new java.util.HashMap<String,Object>(hide);changed.put("reason","다른 사유");post(op,actions,changed,409);
        String action=authMapper.readTree(first).get("id").asText();
        // A migrated product may subsequently receive a new case-based hide.
        jdbc.update("INSERT INTO marketplace.operations_legacy_product_hide(product_id) VALUES(:id)",Map.of("id",product.id()));
        var legacy=authMapper.readTree(get(op,"/api/admin/content/moderation/legacy-products/"+product.id(),200));
        assertTrue(legacy.get("hidden").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(legacy.get("canRestore").asBoolean());
        post(op,"/api/admin/content/moderation/legacy-products/"+product.id(),
                Map.of("key",UUID.randomUUID(),"hide",false,"edition",0,"targetVersion",version+1,"reason","기존 경로 시도"),409);
        var duplicate=new java.util.HashMap<String,Object>(hide);duplicate.put("key",UUID.randomUUID());duplicate.put("targetVersion",version+1);
        post(op,actions,duplicate,409);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","REOPEN","version",3,"reason","재검토","reopenBasis","JUDGMENT_ERROR"),200);
        get(authBrowser(),published,404);
        var restore=Map.of("key",UUID.randomUUID(),"hide",false,"caseVersion",4,"closeVersion",3,"edition",0,"targetVersion",version+1,"sourceHide",action,"reason","복원 근거");
        String restored=post(op,actions,restore,200);assertEquals(restored,post(op,actions,restore,200));
        get(authBrowser(),published,200);
        duplicate.put("targetVersion",version+2);duplicate.put("caseVersion",4);post(op,actions,duplicate,409);
        post(op,commands,Map.of("key",UUID.randomUUID(),"action","ASSIGN","operator",other.authority().member(),"version",4),200);
        post(op,actions,hide,404);get(op,actions,404);
        var newRequest=new java.util.HashMap<String,Object>(hide);
        newRequest.put("key",UUID.randomUUID());newRequest.put("caseVersion",5);newRequest.put("targetVersion",version+2);
        post(op,actions,newRequest,404);
        assertEquals(version+2,authMapper.readTree(get(other.browser(),actions,200)).get("targetVersion").asLong());
        get(authBrowser(),published,200);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_moderation_action WHERE report_id=:id",ref,Integer.class));
        post(other.browser(),commands,Map.of("key",UUID.randomUUID(),"action","CLOSE","decision","VIOLATION","reason","새 판단","version",5),200);
        newRequest.put("caseVersion",6);
        post(other.browser(),actions,newRequest,409);
        assertEquals(version+2,authMapper.readTree(get(other.browser(),actions,200)).get("targetVersion").asLong());
        get(authBrowser(),published,200);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.operations_moderation_action WHERE report_id=:id",Map.of("id",UUID.fromString(report)),Integer.class));
    }
}
