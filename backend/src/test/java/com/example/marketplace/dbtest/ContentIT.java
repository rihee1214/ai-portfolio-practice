package com.example.marketplace.dbtest;

import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.content.infrastructure.ReviewCommands;
import com.example.marketplace.order.api.OrderRejected;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentIT extends PostgresContractFixture {
    @Test void partialRefundAndReplacementConfirmationKeepOriginalItemRights() throws Exception {
        var f=paymentFixture("a-review-mixed",false);
        var delivery=paidShipment(f);
        UUID item=delivery.units().stream().map(u->u.orderItemId())
                .filter(i->delivery.units().stream().filter(u->u.orderItemId().equals(i)).count()>1).findFirst().orElseThrow();
        var split=shipments().split(delivery.id(),shipmentAuthority(f),new com.example.marketplace.fulfillment.api.Shipments.Command(
                "review-split",delivery.version(),java.util.List.of(new com.example.marketplace.fulfillment.api.Shipments.Item(item,1))));
        var routes=shipments().seller(ownSeller(f),shipmentAuthority(f));
        var kept=routes.stream().filter(s->s.id().equals(split.target().id())).findFirst().orElseThrow().units().getFirst();
        var cancelled=routes.stream().filter(s->s.id().equals(delivery.id())).findFirst().orElseThrow().units().stream()
                .filter(u->u.orderItemId().equals(item)).findFirst().orElseThrow();
        var accepted=acceptCancellation(f,java.util.List.of(Map.of("itemId",item,"ordinal",cancelled.ordinal())),"mixed-cancel");
        UUID attempt=UUID.fromString(accepted.get("refundAttemptId").asText());
        runCancellationJob("REFUND_SUBMIT.v1",attempt);
        runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
        var current=shipments().seller(ownSeller(f),shipmentAuthority(f)).stream()
                .filter(s->s.units().stream().anyMatch(u->u.orderItemId().equals(item)&&u.ordinal()==kept.ordinal())).findFirst().orElseThrow();
        completeShipment(f,current,Instant.now());
        String number=f.order().orderNumber();
        var actor=shipmentAuthority(f);
        var view=confirmations().get(number,actor);
        var confirmed=confirmations().confirm(number,actor,new OrderConfirmations.Command("mixed-kept",view.revision(),
                java.util.List.of(new OrderConfirmations.Unit(item,kept.ordinal()))));
        var purchases=context.getBean(com.example.marketplace.order.api.ReviewPurchases.class);
        var facts=purchases.get(number,item,actor);
        var returned=facts.units().stream().filter(u->u.ordinal()==cancelled.ordinal()).findFirst().orElseThrow();
        var retained=facts.units().stream().filter(u->u.ordinal()==kept.ordinal()).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotNull(returned.refundCause());
        org.junit.jupiter.api.Assertions.assertNull(returned.confirmation());
        assertEquals(confirmed.receiptId(),retained.confirmation().id());
        org.junit.jupiter.api.Assertions.assertNull(retained.refundCause());
        org.junit.jupiter.api.Assertions.assertFalse(purchases.fullyRefunded(item));
        var service=context.getBean(ReviewCommands.class);
        assertEquals(1,service.apply(number,item,actor,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.CREATE,0L,5,"일부 구매 확정")).version());

        var exchange=deliveredExchange("review-replacement");
        var buyer=exchange.parties().buyer();
        String exchangedNumber=buyer.order().orderNumber();
        var exchangeActor=shipmentAuthority(buyer);
        var original=exchange.accepted().units().getFirst();
        var replacementView=confirmations().get(exchangedNumber,exchangeActor);
        confirmations().confirm(exchangedNumber,exchangeActor,new OrderConfirmations.Command("replacement-confirm",replacementView.revision(),
                java.util.List.of(new OrderConfirmations.Unit(original.itemId(),original.ordinal()))));
        var replacement=service.apply(exchangedNumber,original.itemId(),exchangeActor,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.CREATE,0L,4,"교환 후 구매 확정"));
        assertEquals(original.itemId(),jdbc.queryForObject("SELECT order_item_id FROM marketplace.content_review WHERE id=:id",Map.of("id",replacement.reviewId()),UUID.class));
        var duplicate=org.junit.jupiter.api.Assertions.assertThrows(OrderRejected.class,()->service.apply(exchangedNumber,original.itemId(),exchangeActor,
                new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.CREATE,0L,4,"중복")));
        assertEquals(409,duplicate.status());
    }
    @Test void concurrentCreationAndRewriteKeepOneReviewAndReplaysDoNotUndoDeletion() throws Exception {
        var f = paymentFixture("review-race",false);
        var shipment = paidShipment(f);
        completeShipment(f,shipment,Instant.now());
        String number = f.order().orderNumber();
        var actor = shipmentAuthority(f);
        var view = confirmations().get(number,actor);
        var unit = view.units().stream().filter(OrderConfirmations.Eligibility::eligible)
                .map(OrderConfirmations.Eligibility::unit).findFirst().orElseThrow();
        confirmations().confirm(number,actor,new OrderConfirmations.Command("race-confirm",view.revision(),java.util.List.of(unit)));
        var service = context.getBean(ReviewCommands.class);
        for (var action : java.util.List.of(ReviewCommands.Action.CREATE, ReviewCommands.Action.REWRITE)) {
            long version = action == ReviewCommands.Action.CREATE ? 0 : 2;
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var wins = new java.util.ArrayList<ReviewCommands.Command>();
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<ReviewCommands.Command>>();
                for (int i=0;i<2;i++) {
                    var command = new ReviewCommands.Command(UUID.randomUUID(),action,version,4,"race "+i);
                    tasks.add(pool.submit(() -> {
                        barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);
                        try { service.apply(number,unit.itemId(),actor,command); return command; }
                        catch (OrderRejected rejected) { assertEquals(409,rejected.status()); return null; }
                    }));
                }
                for (var task:tasks) {
                    var success=task.get(10,java.util.concurrent.TimeUnit.SECONDS);
                    if (success!=null) { wins.add(success); }
                }
            }
            assertEquals(1,wins.size());
            var key = Map.of("item",unit.itemId());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_review WHERE order_item_id=:item",key,Integer.class));
            assertEquals(4,jdbc.queryForObject("SELECT sum(rating) FROM marketplace.content_review WHERE order_item_id=:item AND NOT author_deleted AND NOT hidden_by_admin",key,Integer.class));
            if (action == ReviewCommands.Action.CREATE) {
                var receipt=service.apply(number,unit.itemId(),actor,wins.getFirst());
                service.apply(number,unit.itemId(),actor,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.DELETE,1L,null,null));
                assertEquals(receipt,service.apply(number,unit.itemId(),actor,wins.getFirst()));
                assertTrue(jdbc.queryForObject("SELECT author_deleted FROM marketplace.content_review WHERE id=:id",Map.of("id",receipt.reviewId()),Boolean.class));
            }
        }
    }

    @Test void reviewAndImageWritesRecheckSessionAfterContendedReviewLock() throws Exception {
        var f = paymentFixture("review-expire",false);
        var shipment = paidShipment(f);
        completeShipment(f,shipment,Instant.now());
        String number=f.order().orderNumber();
        var actor=shipmentAuthority(f);
        var v=confirmations().get(number,actor);
        var unit=v.units().stream().filter(OrderConfirmations.Eligibility::eligible).map(OrderConfirmations.Eligibility::unit).findFirst().orElseThrow();
        confirmations().confirm(number,actor,new OrderConfirmations.Command("wait-confirm",v.revision(),java.util.List.of(unit)));
        var receipt=context.getBean(ReviewCommands.class).apply(number,unit.itemId(),actor,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.CREATE,0L,4,"원본"));
        var clock=new JobClock();
        var sessions=new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock);
        var ordersView=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),sessions,context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);
        var api=new ReviewCommands(jdbc,new com.example.marketplace.order.infrastructure.JdbcReviewPurchases(ordersView,jdbc),
                context.getBeanProvider(com.example.marketplace.media.api.ReviewImages.class),sessions);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var expiry=jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:s",Map.of("s",actor.browser()),Long.class);
        for (boolean image:java.util.List.of(false,true)) {
            clock.now=Instant.now();
            try (var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var connection=context.getBean(javax.sql.DataSource.class).getConnection()) {
                connection.setAutoCommit(false);
                try (var lock=connection.prepareStatement("SELECT id FROM marketplace.content_review WHERE id=? FOR UPDATE")) {
                    lock.setObject(1,receipt.reviewId());lock.executeQuery().close();
                }
                var pending=pool.submit(() -> org.junit.jupiter.api.Assertions.assertThrows(OrderRejected.class,() ->
                    tx.executeWithoutResult(t -> {
                        if (image) { api.image(number,unit.itemId(),actor,UUID.randomUUID(),1,0,null,null); }
                        else { api.apply(number,unit.itemId(),actor,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.EDIT,1L,1,"변경")); }
                    })).status());
                try { awaitCouponLock("content_review");clock.now=Instant.ofEpochMilli(expiry); }
                finally {connection.commit();}
                assertEquals(404,pending.get(10,java.util.concurrent.TimeUnit.SECONDS));
            }
            var key=Map.of("id",receipt.reviewId());
            assertTrue(jdbc.queryForObject("SELECT version=1 AND body='원본' AND rating=4 FROM marketplace.content_review WHERE id=:id",key,Boolean.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_review_command WHERE review_id=:id",key,Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.media_review_slot WHERE review_id=:id",key,Integer.class));
        }
    }
    @Test void ownerConfirmationCreationReplayAndRewriteUseOneReview() throws Exception {
        var buyer = paymentFixture("review-owner", false);
        String number = buyer.order().orderNumber();
        var authority = shipmentAuthority(buyer);
        var api = confirmations();
        var shipment = paidShipment(buyer);
        var raw = shipment.units().getFirst();
        var unit = new OrderConfirmations.Unit(raw.orderItemId(), raw.ordinal());
        String path = "/api/orders/" + number + "/items/" + unit.itemId() + "/review";
        String csrf = csrfToken(buyer.browser());
        var create = Map.of("key", UUID.randomUUID(), "action", "CREATE", "expectedVersion", 0,
                "rating", 5, "body", "원 주문 항목 후기");
        assertEquals(409, accountRequest(buyer.browser(), "POST", path, create, csrf).statusCode());
        completeShipment(buyer, shipment, Instant.now().minusSeconds(10));
        var view = api.get(number, authority);
        api.confirm(number, authority, new OrderConfirmations.Command("review-confirm", view.revision(), java.util.List.of(unit)));
        var other = paymentFixture("review-other", false);
        assertEquals(404, accountRequest(other.browser(), "POST", path, create, csrfToken(other.browser())).statusCode());
        assertEquals(403, accountRequest(buyer.browser(), "POST", path, create, null).statusCode());
        var saved = accountRequest(buyer.browser(), "POST", path, create, csrf);
        assertEquals(200, saved.statusCode(), saved.body());
        for (Object invalid : java.util.List.of(0,6,1.5,"5")) {
            var input=new java.util.HashMap<String,Object>(create);
            input.put("key",UUID.randomUUID());input.put("action","EDIT");input.put("expectedVersion",1);input.put("rating",invalid);
            assertEquals(400,accountRequest(buyer.browser(),"POST",path,input,csrf).statusCode());
        }
        for(String invalid:java.util.List.of("","  ","가".repeat(2001))) {
            var input=new java.util.HashMap<String,Object>(create);
            input.put("key",UUID.randomUUID());input.put("action","EDIT");input.put("expectedVersion",1);input.put("body",invalid);
            assertEquals(400,accountRequest(buyer.browser(),"POST",path,input,csrf).statusCode());
        }
        var first = authMapper.readTree(saved.body());
        var id = UUID.fromString(first.get("reviewId").asText());
        String slug = jdbc.queryForObject("""
                SELECT p.slug FROM marketplace.catalog_product p JOIN marketplace.content_review r ON r.product_id=p.id
                WHERE r.id=:id
                """, Map.of("id", id), String.class);
        String published = "/api/products/" + slug + "/reviews";
        var anonymous = authBrowser();
        var visible = accountRequest(anonymous, "GET", published, null, null);
        assertEquals(200, visible.statusCode());
        assertEquals(5, authMapper.readTree(visible.body()).get("ratingSum").asInt());
        var detail="/api/reviews/"+id;
        assertEquals(200,accountRequest(anonymous,"GET",detail,null,null).statusCode());
        assertTrue(authMapper.readTree(accountRequest(buyer.browser(),"GET",detail,null,null).body()).get("canReply").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(authMapper.readTree(accountRequest(other.browser(),"GET",detail,null,null).body()).get("canReply").asBoolean());
        String responses = "/api/reviews/" + id + "/responses";
        var comment = Map.of("key",UUID.randomUUID(),"kind","COMMENT","action","CREATE","expectedVersion",0,
                "edition",1,"body","이전 본문의 댓글");
        var reply = Map.of("key",UUID.randomUUID(),"kind","SELLER_REPLY","action","CREATE","expectedVersion",0,
                "edition",1,"body","판매자 답변");
        var commentResult=accountRequest(other.browser(),"POST",responses,comment,csrfToken(other.browser()));
        assertEquals(200,commentResult.statusCode());
        String commentId=authMapper.readTree(commentResult.body()).get("id").asText();
        for(String action:java.util.List.of("EDIT","DELETE")) {
            var foreign=new java.util.HashMap<String,Object>(comment);
            foreign.put("key",UUID.randomUUID());foreign.put("action",action);foreign.put("responseId",commentId);
            foreign.put("expectedVersion",1);if(action.equals("DELETE")) {foreign.remove("body");}
            assertEquals(409,accountRequest(buyer.browser(),"POST",responses,foreign,csrf).statusCode());
        }
        assertEquals(403,accountRequest(other.browser(),"POST",responses,reply,csrfToken(other.browser())).statusCode());
        var replyApi=context.getBean(com.example.marketplace.content.infrastructure.ReviewResponses.class);
        var gate=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> sendReply=()->{
                gate.await(5,java.util.concurrent.TimeUnit.SECONDS);
                var cmd=new com.example.marketplace.content.infrastructure.ReviewResponses.Command(UUID.randomUUID(),
                        com.example.marketplace.content.infrastructure.ReviewResponses.Kind.SELLER_REPLY,
                        com.example.marketplace.content.infrastructure.ReviewResponses.Action.CREATE,null,0L,1L,"판매자 답변");
                try { replyApi.apply(id,authority,cmd);return 200; }
                catch(OrderRejected rejected) {return rejected.status();}
            };
            var one=pool.submit(sendReply);var two=pool.submit(sendReply);
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),
                    two.get(10,java.util.concurrent.TimeUnit.SECONDS)));
        }
        var before = authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body());
        assertEquals(2, before.get("reviews").get(0).get("responses").size());
        assertEquals(1, before.get("count").asInt());
        for(var child:before.get("reviews").get(0).get("responses")) {
            var childKey=Map.of("id",UUID.fromString(child.get("id").asText()));
            assertEquals(1,jdbc.update("UPDATE marketplace.content_review_response SET hidden_by_admin=true WHERE id=:id",childKey));
            var listing=authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body());
            var visibleChildren=listing.get("reviews").get(0).get("responses");
            assertEquals(1,visibleChildren.size());
            org.junit.jupiter.api.Assertions.assertNotEquals(child.get("id"),visibleChildren.get(0).get("id"));
            var publicDetail=authMapper.readTree(accountRequest(anonymous,"GET",detail,null,null).body());
            assertEquals(visibleChildren,publicDetail.get("responses"));
            assertEquals(1,listing.get("count").asInt());
            assertEquals(5,listing.get("ratingSum").asInt());
            assertEquals(1,jdbc.update("UPDATE marketplace.content_review_response SET hidden_by_admin=false WHERE id=:id",childKey));
            assertEquals(before,authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()));
        }
        assertEquals(first, authMapper.readTree(accountRequest(buyer.browser(), "POST", path, create, csrf).body()));
        var conflicting = new java.util.HashMap<String,Object>(create);
        conflicting.put("rating", 1);
        assertEquals(409, accountRequest(buyer.browser(), "POST", path, conflicting, csrf).statusCode());
        var delete = Map.of("key", UUID.randomUUID(), "action", "DELETE", "expectedVersion", 1);
        assertEquals(200, accountRequest(buyer.browser(), "POST", path, delete, csrf).statusCode());
        assertEquals(0, authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()).get("count").asInt());
        jdbc.update("UPDATE marketplace.content_review SET hidden_by_admin=true,version=version+1 WHERE id=:id", Map.of("id", id));
        var rewrite = Map.of("key", UUID.randomUUID(), "action", "REWRITE", "expectedVersion", 3, "rating", 1, "body", "새 본문");
        var rewritten = accountRequest(buyer.browser(), "POST", path, rewrite, csrf);
        assertEquals(200, rewritten.statusCode(), rewritten.body());
        assertEquals(id.toString(), authMapper.readTree(rewritten.body()).get("reviewId").asText());
        assertTrue(jdbc.queryForObject("SELECT hidden_by_admin AND NOT author_deleted AND edition=2 AND version=4 FROM marketplace.content_review WHERE id=:id", Map.of("id", id), Boolean.class));
        assertEquals(0, authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()).get("count").asInt());
        jdbc.update("UPDATE marketplace.content_review SET hidden_by_admin=false,version=version+1 WHERE id=:id",Map.of("id",id));
        var after = authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body());
        assertEquals(1, after.get("ratingSum").asInt());
        assertEquals(0, after.get("reviews").get(0).get("responses").size());
        var stale=new java.util.HashMap<String,Object>(comment);stale.put("key",UUID.randomUUID());
        assertEquals(409,accountRequest(other.browser(),"POST",responses,stale,csrfToken(other.browser())).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM marketplace.content_review_response WHERE review_id=:id AND edition=1",Map.of("id",id),Integer.class));
        var encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),"png",encoded);
        byte[] bytes = encoded.toByteArray();
        var service = context.getBean(com.example.marketplace.content.infrastructure.ReviewCommands.class);
        UUID key = UUID.randomUUID();
        var upload = service.image(number,unit.itemId(),authority,key,5,0,"후기.png",bytes);
        assertEquals(upload,service.image(number,unit.itemId(),authority,key,5,0,"후기.png",bytes));
        String firstAsset = authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body())
                .get("reviews").get(0).get("images").get(0).get("id").asText();
        String imagePath = "/api/reviews/" + id + "/images/";
        assertEquals(200,accountRequest(anonymous,"GET",imagePath+firstAsset,null,null).statusCode());
        assertEquals(404,accountRequest(buyer.browser(),"GET","/api/media/"+firstAsset+"/content",null,null).statusCode());
        service.image(number,unit.itemId(),authority,UUID.randomUUID(),6,0,"다른.png",bytes);
        assertEquals(404,accountRequest(buyer.browser(),"GET",imagePath+firstAsset,null,null).statusCode());
        String currentAsset = authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body())
                .get("reviews").get(0).get("images").get(0).get("id").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstAsset,currentAsset);
        var edit = Map.of("key",UUID.randomUUID(),"action","EDIT","expectedVersion",7,"rating",6,"body","경계");
        assertEquals(400,accountRequest(buyer.browser(),"POST",path,edit,csrf).statusCode());
        jdbc.update("UPDATE marketplace.content_review SET hidden_by_admin=true,version=version+1 WHERE id=:id",Map.of("id",id));
        assertEquals(404,accountRequest(buyer.browser(),"GET",imagePath+currentAsset,null,null).statusCode());
        assertEquals(0,authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()).get("count").asInt());
        jdbc.update("UPDATE marketplace.content_review SET hidden_by_admin=false,version=version+1 WHERE id=:id",Map.of("id",id));
        String photoPath=path+"/images/4?key="+UUID.randomUUID()+"&version=9";
        assertEquals(403,upload(buyer.browser(),photoPath,"safe.png",bytes,null).statusCode());
        assertEquals(400,upload(buyer.browser(),photoPath,"spoof.webp",bytes,csrf).statusCode());
        assertEquals(200,upload(buyer.browser(),photoPath,"safe.png",bytes,csrf).statusCode());
        assertEquals(400,upload(buyer.browser(),path+"/images/5?key="+UUID.randomUUID()+"&version=10","safe.png",bytes,csrf).statusCode());
        // The transaction includes file creation, attachment, retirement, version and receipt.
        var rollbackKey=UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(t->{
                service.image(number,unit.itemId(),authority,rollbackKey,10,0,"rollback.png",bytes);
                throw new IllegalStateException("deliberate rollback after replacement");
            }));
        assertEquals(200,accountRequest(anonymous,"GET",imagePath+currentAsset,null,null).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_review_command WHERE request_key=:key",Map.of("key",rollbackKey),Integer.class));
        var keys=Map.of("id",id);
        var productId=jdbc.queryForObject("SELECT product_id FROM marketplace.content_review WHERE id=:id",keys,UUID.class);
        var storeId=jdbc.queryForObject("SELECT store_id FROM marketplace.content_review WHERE id=:id",keys,UUID.class);
        for(boolean product:java.util.List.of(true,false)) {
            String update=product?"UPDATE marketplace.catalog_product SET is_hidden_by_admin=:hide WHERE id=:id":
                    "UPDATE marketplace.seller_store SET visible=NOT :hide WHERE id=:id";
            var parent=product?productId:storeId;
            jdbc.update(update,Map.of("id",parent,"hide",true));
            assertEquals(404,accountRequest(anonymous,"GET",published,null,null).statusCode());
            assertEquals(404,accountRequest(anonymous,"GET",detail,null,null).statusCode());
            assertEquals(404,accountRequest(buyer.browser(),"GET",imagePath+currentAsset,null,null).statusCode());
            jdbc.update(update,Map.of("id",parent,"hide",false));
            assertEquals(1,authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()).get("ratingSum").asInt());
        }
        service.apply(number,unit.itemId(),authority,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.DELETE,10L,null,null));
        service.apply(number,unit.itemId(),authority,new ReviewCommands.Command(UUID.randomUUID(),ReviewCommands.Action.REWRITE,11L,3,"이미지 없는 새 후기"));
        assertEquals(404,accountRequest(buyer.browser(),"GET",imagePath+currentAsset,null,null).statusCode());
        assertEquals(0,authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()).get("reviews").get(0).get("images").size());
        UUID rollbackReview=UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(t->{
                service.apply(number,unit.itemId(),authority,new ReviewCommands.Command(rollbackReview,ReviewCommands.Action.EDIT,12L,5,"되돌릴 본문"));
                throw new IllegalStateException("abort after receipt");
            }));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.content_review_command WHERE request_key=:key",Map.of("key",rollbackReview),Integer.class));
        assertEquals(3, authMapper.readTree(accountRequest(anonymous,"GET",published,null,null).body()).get("ratingSum").asInt());
        var maximum=Map.of("key",UUID.randomUUID(),"action","EDIT","expectedVersion",12,"rating",5,"body","😀".repeat(2000));
        assertEquals(200,accountRequest(buyer.browser(),"POST",path,maximum,csrf).statusCode());
        assertEquals(200,upload(buyer.browser(),path+"/images/0?key="+UUID.randomUUID()+"&version=13","max.png",
                java.util.Arrays.copyOf(bytes,5*1024*1024),csrf).statusCode());
        int oversized=upload(buyer.browser(),path+"/images/1?key="+UUID.randomUUID()+"&version=14","large.png",
                java.util.Arrays.copyOf(bytes,5*1024*1024+1),csrf).statusCode();
        assertTrue(oversized==400 || oversized==413,"oversize image should be rejected: "+oversized);
        assertEquals(14,jdbc.queryForObject("SELECT version FROM marketplace.content_review WHERE id=:id",Map.of("id",id),Integer.class));
        for (var kind : java.util.List.of("COMMENT","SELLER_REPLY")) {
            int limit=kind.equals("COMMENT")?500:1000;
            var request=new java.util.HashMap<String,Object>(Map.of("key",UUID.randomUUID(),"kind",kind,"action","CREATE","edition",3,"expectedVersion",0));
            for(String bad:java.util.List.of("","가".repeat(limit+1))) {
                request.put("body",bad); assertEquals(400,accountRequest(buyer.browser(),"POST",responses,request,csrf).statusCode());
            }
            request.put("body","가".repeat(limit));
            var created=accountRequest(buyer.browser(),"POST",responses,request,csrf);
            assertEquals(200,created.statusCode());
            String target=authMapper.readTree(created.body()).get("id").asText();
            request.put("responseId",target);request.put("expectedVersion",1);request.put("action","EDIT");
            request.put("body","가");request.put("key",UUID.randomUUID());
            assertEquals(200,accountRequest(buyer.browser(),"POST",responses,request,csrf).statusCode());
            request.put("expectedVersion",2);request.put("action","DELETE");request.remove("body");request.put("key",UUID.randomUUID());
            assertEquals(200,accountRequest(buyer.browser(),"POST",responses,request,csrf).statusCode());
        }
    }
}
