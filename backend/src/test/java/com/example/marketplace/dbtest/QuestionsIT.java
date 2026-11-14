package com.example.marketplace.dbtest;

import com.example.marketplace.catalog.api.Products;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionsIT extends PostgresContractFixture {
    private HttpClient login(String email) throws Exception {
        var browser=authBrowser();
        assertEquals(200,authRequest(browser,"/api/auth/login",loginForm(email,SIGNUP_PASSWORD),csrfToken(browser)).statusCode());
        return browser;
    }
    @Test void publicAndPrivateQuestionBodiesAndAnswersRespectCurrentReaders() throws Exception {
        var fixture=browseFixture("b-question",1500,3);
        var product=(Products.Product)fixture.get("product");
        String sellerEmail=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",
                Map.of("id",fixture.get("owner")),String.class);
        activeMember("question-author@example.com");
        activeMember("question-other@example.com");
        var author=login("question-author@example.com");
        var other=login("question-other@example.com");
        var seller=login(sellerEmail);
        var visitor=authBrowser();
        String path="/api/products/"+product.slug()+"/questions";
        var foreignProduct=browseFixture("b-question-foreign",1200,2);
        var foreignSeller=login(jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",foreignProduct.get("owner")),String.class));
        var gas=cancellationGuest("question-reader");
        var guest=authBrowser();
        assertEquals(200,accountRequest(guest,"POST","/api/guest/access/login",Map.of("orderNumber",gas.order().orderNumber(),"phone",gas.fixture().phone,"password",GUEST_PASSWORD),csrfToken(guest)).statusCode());
        for(var reader:java.util.List.of(visitor,guest)){
            int denied=accountRequest(reader,"POST",path,Map.of("key",UUID.randomUUID(),"visibility","PUBLIC","body","권한 없는 작성"),csrfToken(reader)).statusCode();
            assertTrue(denied==401 || denied==403);
        }
        for(String visibility:java.util.List.of("PUBLIC","PRIVATE")){
            var data=Map.of("key",UUID.randomUUID(),"visibility",visibility,"body","질문 "+visibility);
            assertEquals(403,accountRequest(author,"POST",path,data,null).statusCode());
            var created=accountRequest(author,"POST",path,data,csrfToken(author));
            assertEquals(200,created.statusCode(),created.body());
            var id=authMapper.readTree(created.body()).get("id").asText();
            var attempted=accountRequest(author,"PUT","/api/questions/"+id,Map.of("visibility",visibility.equals("PRIVATE")?"PUBLIC":"PRIVATE"),csrfToken(author));
            assertEquals(405,attempted.statusCode());
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
                jdbc.update("UPDATE marketplace.content_product_question SET visibility=:other WHERE id=:id",
                    Map.of("id",UUID.fromString(id),"other",visibility.equals("PRIVATE")?"PUBLIC":"PRIVATE")));
            String base="/api/questions/"+id+"/messages/"+id+"/images";
            var encoded=new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",encoded);
            byte[] bytes=encoded.toByteArray();
            String csrf=csrfToken(author);
            String slot=base+"/0?key="+UUID.randomUUID()+"&version=0";
            var file=upload(author,slot,"safe.png",bytes,csrf);
            assertEquals(200,file.statusCode(),file.body());
            assertEquals(file.body(),upload(author,slot,"safe.png",bytes,csrf).body());
            var asset=authMapper.readTree(file.body()).path("asset").asText();
            assertEquals(visibility.equals("PUBLIC")?200:404,accountRequest(visitor,"GET",base+"/"+asset,null,null).statusCode());
            assertEquals(404,accountRequest(author,"GET","/api/media/"+asset+"/content",null,null).statusCode());
            assertEquals(409,upload(author,slot,"other.png",bytes,csrf).statusCode());
            assertEquals(400,upload(author,base+"/1?key="+UUID.randomUUID()+"&version=1","spoof.webp",bytes,csrf).statusCode());
            assertEquals(400,upload(author,base+"/3?key="+UUID.randomUUID()+"&version=1","safe.png",bytes,csrf).statusCode());
            var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            var images=context.getBean(com.example.marketplace.media.api.MessageImages.class);
            var message=new com.example.marketplace.media.api.MessageImages.Target(com.example.marketplace.media.api.MessageImages.Kind.PRODUCT_QUESTION,UUID.fromString(id));
            UUID owner=jdbc.queryForObject("SELECT author_id FROM marketplace.content_product_question WHERE id=:id",Map.of("id",UUID.fromString(id)),UUID.class);
            UUID rollbackKey=UUID.randomUUID();
            UUID rolledBack=tx.execute(t->{
                var image=images.changeMessageImage(message,owner,null,rollbackKey,1,0,"rollback.png",bytes);
                t.setRollbackOnly();return image.asset();
            });
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_message_receipt WHERE request_key=:key",Map.of("key",rollbackKey),Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_asset WHERE id=:id",Map.of("id",rolledBack),Integer.class));
            assertEquals(404,accountRequest(author,"GET",base+"/"+rolledBack,null,null).statusCode());
            var unchanged=authMapper.readTree(accountRequest(author,"GET",base,null,null).body());
            assertEquals(1,unchanged.path("version").asInt());
            assertEquals(asset,unchanged.path("images").get(0).path("asset").asText());
            assertEquals(200,accountRequest(author,"GET",base+"/"+asset,null,null).statusCode());
            assertEquals(200,upload(author,base+"/0?key="+UUID.randomUUID()+"&version=1","next.png",bytes,csrf).statusCode());
            assertEquals(404,accountRequest(author,"GET",base+"/"+asset,null,null).statusCode());
            assertEquals(created.body(),accountRequest(author,"POST",path,data,csrfToken(author)).body());
            assertEquals(409,accountRequest(author,"POST",path,Map.of("key",data.get("key"),"visibility",visibility,"body","같은 키 다른 본문"),csrfToken(author)).statusCode());
            var answer=Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","답변 "+visibility);
            String detail="/api/questions/"+id;
            var existing=authMapper.readTree(accountRequest(author,"GET",base,null,null).body()).path("images").get(0).path("asset").asText();
            if("PRIVATE".equals(visibility)){
                raceAnswerAndRead(seller,detail,base+"/"+existing,answer);
                assignedFiles(seller,author,detail,base,existing,bytes);
            }else{
                String block="CHECK(kind<>'PRODUCT_ANSWER' OR target_id<>'"+UUID.fromString(id)+"'::uuid)";
                jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.notification_event ADD CONSTRAINT test_block_notification "+block);
                try {
                    assertTrue(accountRequest(seller,"POST",detail+"/answer",answer,csrfToken(seller)).statusCode()>=400);
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_product_answer WHERE question_id=:id",
                            Map.of("id",UUID.fromString(id)),Integer.class));
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.notification_event WHERE target_id=:id",
                            Map.of("id",UUID.fromString(id)),Integer.class));
                } finally {
                    jdbc.getJdbcTemplate().execute("ALTER TABLE marketplace.notification_event DROP CONSTRAINT test_block_notification");
                }
                assertEquals(200,accountRequest(seller,"POST",detail+"/answer",answer,csrfToken(seller)).statusCode());
                attachmentLimits(author,base,bytes);
            }
            var answered=accountRequest(author,"GET",detail,null,null);
            assertEquals(200,answered.statusCode());
            var answerId=UUID.fromString(authMapper.readTree(answered.body()).path("answerId").asText());
            assertEquals(1,jdbc.queryForObject("""
                    SELECT count(*) FROM marketplace.notification_event e
                    JOIN marketplace.outbox_event o ON o.id=e.id
                    JOIN marketplace.job j ON j.id=o.job_id
                    WHERE e.kind='PRODUCT_ANSWER' AND e.cause_id=:answer AND e.target_id=:question
                      AND e.recipient_id=(SELECT author_id FROM marketplace.content_product_question WHERE id=:question)
                      AND j.type='NOTIFICATION_DELIVER.v1'
                    """,Map.of("answer",answerId,"question",UUID.fromString(id)),Integer.class));
            var delivery=jdbc.queryForMap("""
                    SELECT e.id,o.job_id FROM marketplace.notification_event e JOIN marketplace.outbox_event o ON o.id=e.id
                    WHERE e.kind='PRODUCT_ANSWER' AND e.cause_id=:answer
                    """,Map.of("answer",answerId));
            UUID deliveryJob=(UUID)delivery.get("job_id");
            if(visibility.equals("PRIVATE")) { loseAcknowledgement(deliveryJob); }
            else { restartNotificationWorker(deliveryJob,(UUID)delivery.get("id")); }
            String inbox="/api/account/notifications";
            var notices=accountRequest(author,"GET",inbox,null,null);
            assertEquals(200,notices.statusCode(),notices.body());
            String event=delivery.get("id").toString();
            var mine=authMapper.readTree(notices.body()).path("items").valueStream()
                    .filter(item->event.equals(item.path("id").asText())).findFirst().orElseThrow();
            assertEquals("PRODUCT_ANSWER",mine.path("kind").asText());
            assertEquals("/products/"+product.slug(),mine.path("destination").asText());
            assertTrue(mine.path("readAt").isNull());
            assertFalse(notices.body().contains("답변 "+visibility));
            var foreign=accountRequest(other,"GET",inbox,null,null);
            assertEquals(200,foreign.statusCode());assertFalse(foreign.body().contains(event));
            String readPath=inbox+"/"+event+"/read";
            assertEquals(404,accountRequest(other,"POST",readPath,Map.of(),csrfToken(other)).statusCode());
            var marked=accountRequest(author,"POST",readPath,Map.of(),csrfToken(author));
            assertEquals(200,marked.statusCode(),marked.body());assertTrue(authMapper.readTree(marked.body()).path("readAt").isTextual());
            assertEquals(marked.body(),accountRequest(author,"POST",readPath,Map.of(),csrfToken(author)).body());
            if(visibility.equals("PRIVATE")) { recoverNotification(author,(UUID)delivery.get("id"),answerId,marked.body()); }
            assertEquals(200,accountRequest(seller,"GET",detail,null,null).statusCode());
            for(var reader:java.util.List.of(visitor,other,guest,foreignSeller)){
                var result=accountRequest(reader,"GET",detail,null,null);
                var list=accountRequest(reader,"GET",path,null,null);
                assertEquals(200,list.statusCode());
                if(visibility.equals("PUBLIC")){
                    assertEquals(200,result.statusCode());
                    assertTrue(list.body().contains("답변 PUBLIC"));
                } else {
                    assertEquals(404,result.statusCode());
                    assertFalse(list.body().contains(id));
                    assertFalse(list.body().contains("질문 PRIVATE"));
                    assertFalse(list.body().contains("답변 PRIVATE"));
                    var search=accountRequest(reader,"GET","/api/browse/products?q=PRIVATE",null,null);
                    assertEquals(200,search.statusCode());assertFalse(search.body().contains("질문 PRIVATE"));
                }
            }
        }
        var own=accountRequest(seller,"POST",path,Map.of("key",UUID.randomUUID(),"visibility","PUBLIC","body","판매자가 작성한 질문"),csrfToken(seller));
        assertEquals(200,own.statusCode(),own.body());
        String ownId=authMapper.readTree(own.body()).path("id").asText();
        String replyPath="/api/questions/"+ownId+"/answer";
        var selfReply=Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","같은 회원의 답변");
        var posted=accountRequest(seller,"POST",replyPath,selfReply,csrfToken(seller));
        assertEquals(200,posted.statusCode(),posted.body());
        var replay=accountRequest(seller,"POST",replyPath,selfReply,csrfToken(seller));
        assertEquals(200,replay.statusCode());assertEquals(posted.body(),replay.body());
        var ownParams=Map.of("question",UUID.fromString(ownId),"answer",UUID.fromString(authMapper.readTree(posted.body()).path("id").asText()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_product_answer a JOIN marketplace.content_product_question q ON q.id=a.question_id WHERE a.id=:answer AND q.id=:question AND a.author_id=q.author_id",ownParams,Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.notification_event WHERE kind='PRODUCT_ANSWER' AND (cause_id=:answer OR target_id=:question)",ownParams,Integer.class));
    }

    private void restartNotificationWorker(UUID job, UUID event) throws Exception {
        var jobs=context.getBean(com.example.marketplace.platform.api.Jobs.class);
        var folder=java.nio.file.Files.createDirectory(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"))
                .resolve("notification-restart-"+event));
        Process first=confirmationProcess(folder,"first",jobs.find(job).orElseThrow().nextRunAt(),job,"ACK");
        Process second=null;
        try {
            var marker=folder.resolve("first-effect");
            awaitJobCondition(()->java.nio.file.Files.exists(marker)||!first.isAlive(),"notification ACK boundary");
            assertTrue(java.nio.file.Files.exists(marker),"inspect first.log");
            var owned=jobs.find(job).orElseThrow();
            assertEquals("RUNNING",owned.status());
            assertEquals(1,owned.attempts());
            var params=Map.of("event",event);
            String sql="SELECT to_jsonb(i)::text FROM marketplace.notification_inbox i WHERE event_id=:event";
            var committed=jdbc.queryForObject(sql,params,String.class);
            first.destroyForcibly();
            assertTrue(ContractTiming.awaitProcess(first,10,java.util.concurrent.TimeUnit.SECONDS));
            second=confirmationProcess(folder,"second",owned.leaseUntil().plusNanos(1000),job,"NONE");
            assertTrue(ContractTiming.awaitProcess(second,40,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,second.exitValue(),"inspect second.log");
            assertEquals("SUCCEEDED",jobs.find(job).orElseThrow().status());
            assertEquals(2,jobs.find(job).orElseThrow().attempts());
            assertEquals(committed,jdbc.queryForObject(sql,params,String.class));
            assertFalse(jobs.complete(job,owned.ownerToken()));
        } finally {
            for(var process:new Process[]{first,second}) {
                if(process!=null&&process.isAlive()) {
                    process.destroyForcibly();
                    ContractTiming.awaitProcess(process,10,java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
    }
    private void loseAcknowledgement(UUID job) {
        var platform=context.getBean(com.example.marketplace.platform.api.Jobs.class);
        var saved=platform.find(job).orElseThrow();
        var now=java.time.Instant.now();
        var start=saved.nextRunAt().isAfter(now)?saved.nextRunAt():now;
        var clock=new JobClock();clock.now=start.plusSeconds(1);
        var jobs=new com.example.marketplace.platform.infrastructure.JdbcJobs(new OneJobJdbc(context.getBean(javax.sql.DataSource.class),job),transactionManager,clock);
        var owned=jobs.claim(java.util.Set.of("NOTIFICATION_DELIVER.v1")).orElseThrow();
        var handler=context.getBean(com.example.marketplace.notification.infrastructure.NotificationDelivery.class);
        assertEquals(com.example.marketplace.platform.api.JobHandler.Result.COMPLETE,handler.handle(owned));
        // An owner dies after its effect and before acknowledgement; the next claimant sees the same stored delivery.
        for(int attempt=2;attempt<=5;attempt++) {
            clock.now=owned.leaseUntil().plusNanos(1000);
            var prior=owned;
            owned=jobs.claim(java.util.Set.of("NOTIFICATION_DELIVER.v1")).orElseThrow();
            assertEquals(attempt,owned.attempts());assertFalse(jobs.complete(job,prior.ownerToken()));
        }
        clock.now=owned.leaseUntil().plusNanos(1000);
        assertTrue(jobs.claim(java.util.Set.of("NOTIFICATION_DELIVER.v1")).isEmpty());
        assertEquals("FAILED",jobs.find(job).orElseThrow().status());
    }
    private void recoverNotification(HttpClient author,UUID event,UUID answer,String marked) throws Exception {
        String readPath="/api/account/notifications/"+event+"/read";
        var params=Map.of("event",event,"answer",answer);
        UUID job=jdbc.queryForObject("SELECT job_id FROM marketplace.outbox_event WHERE id=:event",params,UUID.class);
        var platform=context.getBean(com.example.marketplace.platform.api.Jobs.class);
        assertEquals("FAILED",platform.find(job).orElseThrow().status());
        var system=supportFixture("notice-sys","SYSTEM");
        var support=supportFixture("notice-supp","SUPPORT");
        var content=supportFixture("notice-content","CONTENT");
        String path="/api/admin/system/notifications/"+event+"/retry";
        var cmd=Map.of("key",UUID.randomUUID(),"failedJob",job,"reason","전달 확인");
        for(var denied:java.util.List.of(support,content)) {
            assertEquals(403,accountRequest(denied.browser(),"POST",path,cmd,csrfToken(denied.browser())).statusCode());
        }
        String csrf=csrfToken(system.browser());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->accountRequest(system.browser(),"POST",path,cmd,csrf));
            var second=pool.submit(()->accountRequest(system.browser(),"POST",path,cmd,csrf));
            var one=first.get(10,java.util.concurrent.TimeUnit.SECONDS);var two=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200,one.statusCode(),one.body());assertEquals(200,two.statusCode(),two.body());
            assertEquals(one.body(),two.body());
            UUID next=UUID.fromString(authMapper.readTree(one.body()).path("job").asText());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.notification_retry WHERE event_id=:event",params,Integer.class));
            var changed=Map.of("key",cmd.get("key"),"failedJob",job,"reason","다른 사유");
            assertEquals(409,accountRequest(system.browser(),"POST",path,changed,csrf).statusCode());
            try(var gate=context.getBean(javax.sql.DataSource.class).getConnection()) {
                gate.setAutoCommit(false);
                try(var lock=gate.prepareStatement("SELECT id FROM marketplace.notification_event WHERE id=? FOR UPDATE")) {
                    lock.setObject(1,event);lock.executeQuery().close();
                }
                var replay=pool.submit(()->accountRequest(system.browser(),"POST",path,cmd,csrf));
                awaitCouponLock("notification_event");
                try(var revoke=gate.prepareStatement("DELETE FROM marketplace.admin_permission WHERE user_id=? AND permission='SYSTEM'")) {
                    revoke.setObject(1,system.authority().member());assertEquals(1,revoke.executeUpdate());
                }
                gate.commit();assertEquals(403,replay.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode());
            }
            runStoredJob("NOTIFICATION_DELIVER.v1",next);
            assertEquals("FAILED",platform.find(job).orElseThrow().status());
        }
        assertEquals(marked,accountRequest(author,"POST",readPath,Map.of(),csrfToken(author)).body());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_product_answer WHERE id=:answer",params,Integer.class));
    }
    private void raceAnswerAndRead(HttpClient seller,String detail,String file,Map<String,?> answer) throws Exception {
        String csrf=csrfToken(seller);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                var blocker=context.getBean(javax.sql.DataSource.class).getConnection()){
            blocker.setAutoCommit(false);
            try(var q=blocker.prepareStatement("SELECT id FROM marketplace.content_product_question WHERE id=? FOR SHARE")){
                q.setObject(1,UUID.fromString(detail.substring(detail.lastIndexOf('/')+1)));q.executeQuery().close();
            }
            var write=pool.submit(()->accountRequest(seller,"POST",detail+"/answer",answer,csrf));
            try {
                awaitCouponLock("content_product_question");
                var read=pool.submit(()->accountRequest(seller,"GET",file,null,null));
                awaitHistoryLock("identity_user");
                var second=pool.submit(()->accountRequest(seller,"POST",detail+"/answer",
                        Map.of("key",UUID.randomUUID(),"expectedVersion",1,"body","경합 답변"),csrf));
                blocker.commit();
                assertEquals(200,write.get().statusCode());
                assertEquals(200,read.get().statusCode());
                assertEquals(409,second.get().statusCode());
            } finally {blocker.rollback();}
        }
    }

    private void assignedFiles(HttpClient seller,HttpClient author,String detail,String base,String asset,byte[] bytes) throws Exception {
        var first=supportFixture("question-one","SUPPORT");
        var second=supportFixture("question-two","SUPPORT");
        var question=authMapper.readTree(accountRequest(author,"GET",detail,null,null).body());
        String answerBase=detail+"/messages/"+question.path("answerId").asText()+"/images";
        var image=upload(seller,answerBase+"/0?key="+UUID.randomUUID()+"&version=0","answer.png",bytes,csrfToken(seller));
        assertEquals(200,image.statusCode(),image.body());
        var paths=java.util.List.of(detail,base+"/"+asset,answerBase+"/"+authMapper.readTree(image.body()).path("asset").asText());
        for(String path:paths){assertEquals(404,accountRequest(first.browser(),"GET",path,null,null).statusCode());}
        String assign="/api/admin/support/assignments/"+question.path("id").asText();
        assertEquals(200,accountRequest(first.browser(),"POST",assign,Map.of("key",UUID.randomUUID(),"operator",first.authority().member(),"expectedVersion",0),csrfToken(first.browser())).statusCode());
        for(String path:paths){assertEquals(200,accountRequest(first.browser(),"GET",path,null,null).statusCode());}
        assertEquals(200,accountRequest(first.browser(),"POST",assign,Map.of("key",UUID.randomUUID(),"operator",second.authority().member(),"expectedVersion",1),csrfToken(first.browser())).statusCode());
        for(String path:paths){
            assertEquals(404,accountRequest(first.browser(),"GET",path,null,null).statusCode());
            assertEquals(200,accountRequest(second.browser(),"GET",path,null,null).statusCode());
        }
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id",Map.of("id",second.authority().member()));
        for(String path:paths){assertEquals(404,accountRequest(second.browser(),"GET",path,null,null).statusCode());}
        assertEquals(200,accountRequest(first.browser(),"POST",assign,Map.of("key",UUID.randomUUID(),"operator",first.authority().member(),"expectedVersion",2),csrfToken(first.browser())).statusCode());
        for(String path:paths){assertEquals(200,accountRequest(first.browser(),"GET",path,null,null).statusCode());}
    }

    private void attachmentLimits(HttpClient author,String base,byte[] bytes) throws Exception {
        String csrf=csrfToken(author);
        byte[] max=java.util.Arrays.copyOf(bytes,5242880);
        var image=upload(author,base+"/1?key="+UUID.randomUUID()+"&version=2","max.png",max,csrf);
        assertEquals(200,image.statusCode(),image.body());
        String old=authMapper.readTree(image.body()).path("asset").asText();
        int large=upload(author,base+"/2?key="+UUID.randomUUID()+"&version=3","large.png",java.util.Arrays.copyOf(max,max.length+1),csrf).statusCode();
        assertTrue(large==400 || large==413);
        assertEquals(200,upload(author,base+"/2?key="+UUID.randomUUID()+"&version=3","three.png",bytes,csrf).statusCode());
        assertEquals(3,authMapper.readTree(accountRequest(author,"GET",base,null,null).body()).path("images").size());
        assertEquals(200,accountRequest(author,"DELETE",base+"/1?key="+UUID.randomUUID()+"&version=4",null,csrf).statusCode());
        assertEquals(404,accountRequest(author,"GET",base+"/"+old,null,null).statusCode());
        try(var resource=getClass().getResourceAsStream("/media/lossy.webp")){
            assertEquals(200,upload(author,base+"/1?key="+UUID.randomUUID()+"&version=5","valid.webp",resource.readAllBytes(),csrf).statusCode());
        }
        var jpeg=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",jpeg);
        assertEquals(200,upload(author,base+"/1?key="+UUID.randomUUID()+"&version=6","valid.jpg",jpeg.toByteArray(),csrf).statusCode());
    }
}
