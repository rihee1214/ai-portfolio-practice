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
                assertEquals(200,accountRequest(seller,"POST",detail+"/answer",answer,csrfToken(seller)).statusCode());
                attachmentLimits(author,base,bytes);
            }
            assertEquals(200,accountRequest(author,"GET",detail,null,null).statusCode());
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
