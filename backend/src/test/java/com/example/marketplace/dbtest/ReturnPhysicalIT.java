package com.example.marketplace.dbtest;


import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnPhysicalIT extends PostgresContractFixture {
    @Test void returnSellerApprovalPreservesAcceptedReceiptAndUsesCurrentOwnSeller() throws Exception {
        var f=returnParties("approve");var command=returnRequest(f.buyer(),f.shipment(),"d-start-approve");
        var accepted=context.getBean(com.example.marketplace.claim.api.Returns.class).request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),command);
        var body=Map.of("key","approve-1","expectedRevision",0,"reason","판매자 반품 승인");String path="/api/seller/returns/"+accepted.returnId()+"/approve";
        var before=returnApplicationParticipants();assertEquals(404,accountRequest(f.buyer().browser(),"POST",path,body,csrfToken(f.buyer().browser())).statusCode());assertEquals(before,returnApplicationParticipants());
        var response=accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser()));
        assertEquals(200,response.statusCode(),"current own-store seller must approve an accepted return: "+response.body());
        var progress=authMapper.readTree(response.body());assertEquals("APPROVED",progress.path("state").asText());assertEquals(1,progress.path("version").asLong());assertEquals("REQUESTED",progress.path("accepted").path("state").asText());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        var replay=accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser()));assertEquals(200,replay.statusCode());assertEquals(progress,authMapper.readTree(replay.body()));
    }

    @Test void returnSellerRejectionReleasesHoldAndKeepsOriginalApplication() throws Exception {
        var f=returnParties("reject");var accepted=context.getBean(com.example.marketplace.claim.api.Returns.class).request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"d-start-reject"));
        var body=Map.of("key","reject-1","expectedRevision",0,"reason","반려 사유 원문 보존");String path="/api/seller/returns/"+accepted.returnId()+"/reject";
        var response=accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser()));assertEquals(200,response.statusCode(),"current seller rejection must atomically release the return hold: "+response.body());
        var result=authMapper.readTree(response.body());assertEquals("REJECTED",result.path("state").asText());assertEquals("REQUESTED",result.path("accepted").path("state").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_hold WHERE id=:id AND state='RELEASED' AND outcome='REJECTED'",Map.of("id",accepted.holdId()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        var stable=returnApplicationParticipants();assertEquals(200,accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser())).statusCode());assertEquals(stable,returnApplicationParticipants());
    }

    @Test void returnOwnerWithdrawsOnlyAnUnapprovedRequest() throws Exception {
        var f=returnParties("withdraw");var accepted=context.getBean(com.example.marketplace.claim.api.Returns.class).request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"d-start-withdraw"));
        var body=Map.of("key","withdraw-1","expectedRevision",0,"reason","회수 전 본인 신청 철회");String path="/api/orders/"+f.buyer().order().orderNumber()+"/returns/"+accepted.returnId()+"/withdraw";
        var response=accountRequest(f.buyer().browser(),"POST",path,body,csrfToken(f.buyer().browser()));assertEquals(200,response.statusCode(),"actual current owner must withdraw an unapproved return: "+response.body());
        var result=authMapper.readTree(response.body());assertEquals("WITHDRAWN",result.path("state").asText());assertEquals("REQUESTED",result.path("accepted").path("state").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_hold WHERE id=:id AND state='RELEASED' AND outcome='WITHDRAWN'",Map.of("id",accepted.holdId()),Integer.class));
        var stable=returnApplicationParticipants();assertEquals(200,accountRequest(f.buyer().browser(),"POST",path,body,csrfToken(f.buyer().browser())).statusCode());assertEquals(stable,returnApplicationParticipants());
    }

    @Test void returnCommandGetConflictsAndApprovedWithdrawalRemainPrivate() throws Exception {
        var f=returnParties("command-boundaries");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"command-boundary"));
        var command=new com.example.marketplace.claim.api.Returns.Command("approve-boundary",0L,"검수 전 승인");
        var result=api.approve(accepted.returnId(),shipmentAuthority(f.seller()),command);assertEquals(accepted,result.accepted());
        assertEquals(result,api.get(f.buyer().order().orderNumber(),accepted.returnId(),shipmentAuthority(f.buyer())));
        var before=returnApplicationParticipants();
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command(command.key(),0L,"변경 사유"))).status());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.withdraw(f.buyer().order().orderNumber(),accepted.returnId(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Returns.Command("withdraw-approved",1L,"승인 후 철회 금지"))).status());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.reject(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("reject-approved",1L,"승인 후 반려 금지"))).status());
        String path="/api/orders/"+f.buyer().order().orderNumber()+"/returns/"+accepted.returnId();
        var current=accountRequest(f.buyer().browser(),"GET",path,null,null);assertEquals(200,current.statusCode());assertEquals(result,authMapper.readValue(current.body(),com.example.marketplace.claim.api.Returns.Progress.class));
        assertEquals(404,accountRequest(f.seller().browser(),"GET",path,null,null).statusCode());assertEquals(404,accountRequest(authBrowser(),"GET",path,null,null).statusCode());
        assertEquals(before,returnApplicationParticipants());assertEquals(result,api.approve(accepted.returnId(),shipmentAuthority(f.seller()),command));
    }

    @Test void returnActorCommandsRollBackEverySqlStageAndResumeExactlyOnce() throws Exception {
        for(String action:List.of("APPROVED","REJECTED","WITHDRAWN")) {
            var f=returnParties("atomic-"+action.toLowerCase(java.util.Locale.ROOT));var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
            var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"atomic-"+action));
            var command=new com.example.marketplace.claim.api.Returns.Command("atomic-command",0L,"각 SQL 경계 원자성");var before=returnApplicationParticipants();
            for(String table:List.of("claim_return_request","claim_return_history","claim_return_command","job","outbox_event")) {
                String condition=table.equals("job")?"IF NEW.dedupe_key NOT LIKE 'return-command:%' THEN RETURN NEW;END IF;":table.equals("outbox_event")?"IF NOT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=NEW.job_id AND j.dedupe_key LIKE 'return-command:%') THEN RETURN NEW;END IF;":"";
                jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.d_return_command_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "+condition+"RAISE EXCEPTION 'ACTUAL_D_COMMAND_"+table+"' USING ERRCODE='23514';END $$");
                jdbc.getJdbcTemplate().execute("CREATE TRIGGER d_return_command_reject BEFORE "+(table.equals("claim_return_request")?"UPDATE":"INSERT")+" ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.d_return_command_failure()");
                try {
                    var failure=assertThrows(RuntimeException.class,()->executeReturnCommand(api,f,accepted.returnId(),command,action));Throwable root=failure;while(root.getCause()!=null) { root=root.getCause(); }
                    assertTrue(root instanceof java.sql.SQLException);assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertTrue(root.getMessage().contains("ACTUAL_D_COMMAND_"+table),root.toString());
                    assertEquals(before,returnApplicationParticipants(),action+" full rollback at "+table);
                } finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER d_return_command_reject ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.d_return_command_failure()"); }
            }
            var result=executeReturnCommand(api,f,accepted.returnId(),command,action);assertEquals(action,result.state());assertEquals(1,result.version());
            var stable=returnApplicationParticipants();assertEquals(result,executeReturnCommand(api,f,accepted.returnId(),command,action));assertEquals(stable,returnApplicationParticipants());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_command WHERE return_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        }
    }

    @Test void returnReleasedReceiptWithoutTerminalStateCannotCommit() throws Exception {
        var f=returnParties("receipt-only");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"receipt-only"));var before=returnApplicationParticipants();
        var failure=assertThrows(RuntimeException.class,()->new TransactionTemplate(transactionManager).execute(t->{
            context.getBean(com.example.marketplace.order.api.OrderQueries.class).lockOwned(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()));
            UUID cause=jdbc.queryForObject("SELECT cause_id FROM marketplace.order_return_application WHERE id=:id",Map.of("id",accepted.returnId()),UUID.class);
            context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class).release(f.buyer().order().id(),accepted.holdId(),cause,"WITHDRAWN");
            var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var history=List.of(new com.example.marketplace.claim.api.Returns.History("REQUESTED",0,accepted.reason(),accepted.requestedAt()),new com.example.marketplace.claim.api.Returns.History("WITHDRAWN",1,"receipt-only",at));
            var progress=new com.example.marketplace.claim.api.Returns.Progress(accepted,"WITHDRAWN",1,history);
            var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",accepted.returnId()).addValue("at",java.sql.Timestamp.from(at)).addValue("event",UUID.randomUUID()).addValue("receipt",authMapper.writeValueAsString(progress));
            jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,'WITHDRAWN',1,'GAS','receipt-only',:at)",p);
            jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,'receipt-only',repeat('0',64),'WITHDRAWN',1,CAST(:receipt AS jsonb))",p);
            String key="return-command:"+accepted.returnId()+":1";context.getBean(com.example.marketplace.platform.api.Outbox.class).append(new com.example.marketplace.platform.api.Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new com.example.marketplace.platform.api.Jobs.Command("RETURN_RECORDED.v1",key,accepted.returnId().toString(),accepted.returnId().toString(),at)));
            return null;
        }));Throwable root=failure;while(root.getCause()!=null) { root=root.getCause(); }
        assertTrue(root instanceof java.sql.SQLException);assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertEquals(before,returnApplicationParticipants());
        assertEquals("WITHDRAWN",api.withdraw(f.buyer().order().orderNumber(),accepted.returnId(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Returns.Command("normal-withdraw",0L,"정상 철회")).state());
    }

    @Test void returnAcceptedBeforeDueContinuesAfterDueWhileNewRequestIsRejected() throws Exception {
        var f=returnParties("after-due");var initial=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var accepted=initial.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"accepted-before-due"));
        var unit=accepted.units().getFirst();var due=jdbc.queryForObject("SELECT d.occurred_at+p.claim_window_seconds*interval '1 second' FROM marketplace.order_initial_delivery_unit u JOIN marketplace.order_initial_delivery d ON d.event_id=u.event_id JOIN marketplace.order_aftercare_policy p ON p.order_id=u.order_id WHERE u.item_id=:id AND u.ordinal=:o",Map.of("id",unit.itemId(),"o",unit.ordinal()),java.sql.Timestamp.class).toInstant();
        var clock=new JobClock();clock.now=due.plusNanos(1000);var api=returnsAtClock(clock);var other=f.shipment().units().stream().filter(u->!u.orderItemId().equals(unit.itemId())||u.ordinal()!=unit.ordinal()).findFirst().orElseThrow();
        var command=new com.example.marketplace.claim.api.Returns.Request("new-after-due",confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(other.orderItemId(),other.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"새 신청은 기한 경과");
        var before=returnApplicationParticipants();assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),command)).status());assertEquals(before,returnApplicationParticipants());
        var progress=api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("approve-after-due",0L,"유효 접수 후 진행"));
        assertEquals("APPROVED",progress.state());assertEquals(accepted,progress.accepted());assertTrue(accepted.requestedAt().isBefore(due));
        assertTrue(!progress.history().getLast().occurredAt().isBefore(clock.instant()),"command time must use the same post-deadline business clock as new-request eligibility");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
    }

    @Test void returnActorAuthorityExpiresAfterFinalClaimLockWithoutEffects() throws Exception {
        for(String action:List.of("APPROVED","REJECTED","WITHDRAWN")) {
            var f=returnParties("auth-"+action.toLowerCase(java.util.Locale.ROOT));var initial=context.getBean(com.example.marketplace.claim.api.Returns.class);
            var accepted=initial.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"auth-before-wait"));
            var actor=action.equals("WITHDRAWN")?f.buyer():f.seller();long expires=jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:id",Map.of("id",actor.authority().browser()),Long.class);
            var clock=new JobClock();clock.now=Instant.now();Instant start=clock.instant();assertTrue(clock.millis()<expires);
            var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);
            var applications=new com.example.marketplace.order.infrastructure.JdbcReturnApplications(jdbc,queries,context.getBean(com.example.marketplace.order.api.ReturnSources.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),clock);
            var api=new com.example.marketplace.claim.infrastructure.JdbcReturns(queries,confirmations(),applications,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),jdbc,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,new com.example.marketplace.claim.infrastructure.ReturnCommands(jdbc,queries,applications,context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,clock,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class)));
            var command=new com.example.marketplace.claim.api.Returns.Command("expired-after-final-wait",0L,"마지막 claim 잠금 후 만료");var before=returnApplicationParticipants();
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM marketplace.claim_return_request WHERE id=? FOR UPDATE")) { lock.setObject(1,accepted.returnId());lock.executeQuery().close(); }
                var call=pool.submit(()->assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->executeReturnCommand(api,f,accepted.returnId(),command,action)).status());
                awaitCouponLock("claim_return_request");clock.now=Instant.ofEpochMilli(expires).plusNanos(1000);assertTrue(clock.instant().isAfter(Instant.ofEpochMilli(expires)));connection.commit();assertEquals(404,call.get(10,TimeUnit.SECONDS));
            }
            assertEquals(before,returnApplicationParticipants(),"actual expired actor must roll back every command participant: "+action);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-command-auth-"+action+".json"),authMapper.writeValueAsString(Map.of("action",action,"actor",action.equals("WITHDRAWN")?"CURRENT_BUYER":"CURRENT_OWN_SELLER","storedSessionDeadline",Instant.ofEpochMilli(expires).toString(),"afterActualFinalClaimWait",clock.instant().toString(),"actualStatus",404,"participantsUnchanged",true)));
            clock.now=start;assertEquals(action,executeReturnCommand(api,f,accepted.returnId(),command,action).state());
        }
    }

    @Test void returnApprovalAndRejectionRaceHasOneRevisionAndOneOutcome() throws Exception {
        var f=returnParties("approve-reject-race");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"race-request"));
        var approve=new com.example.marketplace.claim.api.Returns.Command("race-approve",0L,"실제 승인 경합");var reject=new com.example.marketplace.claim.api.Returns.Command("race-reject",0L,"실제 반려 경합");
        String a,b;try(var pool=Executors.newFixedThreadPool(2);var connection=context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { lock.setObject(1,f.buyer().order().id());lock.executeQuery().close(); }
            var first=pool.submit(()->{try { return api.approve(accepted.returnId(),shipmentAuthority(f.seller()),approve).state(); }catch(com.example.marketplace.order.api.OrderRejected e) { return Integer.toString(e.status()); }});
            var second=pool.submit(()->{try { return api.reject(accepted.returnId(),shipmentAuthority(f.seller()),reject).state(); }catch(com.example.marketplace.order.api.OrderRejected e) { return Integer.toString(e.status()); }});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);List<Map<String,Object>> waits=List.of();
            while(System.nanoTime()<end) { jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");waits=jdbc.queryForList("SELECT pid,query,pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Map.of());if(waits.size()>=2) { break; }Thread.sleep(20); }
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-approve-reject-lock-chain.json"),authMapper.writeValueAsString(waits));
            assertTrue(waits.stream().anyMatch(w->w.get("query").toString().contains("purchase_order")),"one actual seller command must wait on the parent order");
            var parentWait=waits.stream().filter(w->w.get("query").toString().contains("purchase_order")).findFirst().orElseThrow();
            assertTrue(waits.stream().anyMatch(w->!w.get("pid").equals(parentWait.get("pid"))&&java.util.Arrays.asList(w.get("blockers").toString().replace("{","").replace("}","").split(",")).contains(parentWait.get("pid").toString())),"the other actual transaction must name the first transaction as its blocker; its saved SQL identifies the real preceding lock");
            assertTrue(waits.size()>=2);assertTrue(waits.stream().allMatch(w->!w.get("blockers").toString().equals("{}")));
            connection.commit();a=first.get(10,TimeUnit.SECONDS);b=second.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,List.of(a,b).stream().filter("409"::equals).count());String outcome=a.equals("409")?b:a;assertTrue(List.of("APPROVED","REJECTED").contains(outcome));
        var progress=api.get(f.buyer().order().orderNumber(),accepted.returnId(),shipmentAuthority(f.buyer()));assertEquals(outcome,progress.state());assertEquals(1,progress.version());assertEquals(2,progress.history().size());assertEquals(accepted,progress.accepted());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_command WHERE return_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        assertEquals(outcome.equals("APPROVED")?"ACTIVE":"RELEASED",jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",accepted.holdId()),String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        var before=returnApplicationParticipants();assertEquals(progress,executeReturnCommand(api,f,accepted.returnId(),outcome.equals("APPROVED")?approve:reject,outcome));assertEquals(before,returnApplicationParticipants());
    }

    @Test void returnCollectionRecordsOriginalUnitsWithoutFinancialOrInventoryEffects() throws Exception {
        var f=returnParties("collection");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var units=f.shipment().units().stream().limit(3).map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList();assertEquals(3,units.size());
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Returns.Request("collect-request",confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),units,"원단위 세 개 실제 회수"));
        api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("collect-approve",0L,"회수 승인"));
        var before=returnApplicationParticipants();var selected=units.subList(0,2).stream().map(u->Map.of("itemId",u.itemId(),"ordinal",u.ordinal(),"status","RECEIVED")).toList();
        String path="/api/seller/returns/"+accepted.returnId()+"/receive";var body=Map.of("key","collect-two","expectedRevision",1,"units",selected,"reason","실제 두 원단위 수령");
        var response=accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser()));assertEquals(200,response.statusCode(),"actual collection scan is missing: "+response.body());
        var progress=authMapper.readTree(response.body());assertEquals("COLLECTING",progress.path("state").asText());assertEquals(2,progress.path("version").asLong());assertEquals(3,progress.path("physical").size());
        int received=0,pending=0;for(var u:progress.path("physical")) { if(u.path("collection").asText().equals("RECEIVED")) { received++; }if(u.path("collection").asText().equals("PENDING")) { pending++; }assertEquals("NOT_INSPECTED",u.path("inspection").asText()); }
        assertEquals(2,received);assertEquals(1,pending);assertEquals(before.get("inventory_balance"),returnApplicationParticipants().get("inventory_balance"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_fee_assessment WHERE intent_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        var stable=returnApplicationParticipants();assertEquals(response.body(),accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser())).body());assertEquals(stable,returnApplicationParticipants());
    }

    @Test void returnInspectionRejectsUnreceivedUnitsAndClientQuantityOverrides() throws Exception {
        var f=returnParties("inspect-missing");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"inspect-request"));
        api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("inspect-approve",0L,"검수 전 실제 회수 필요"));
        var unit=accepted.units().getFirst();var target=Map.of("itemId",unit.itemId(),"ordinal",unit.ordinal(),"condition","SALEABLE","responsibility","SELLER");
        String path="/api/seller/returns/"+accepted.returnId()+"/inspect";var body=Map.of("key","inspect-unreceived","expectedRevision",1,"units",List.of(target),"reason","회수 없는 검수 금지");var before=returnApplicationParticipants();
        var response=accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser()));assertEquals(409,response.statusCode(),"inspection must reject unreceived units: "+response.body());assertEquals(before,returnApplicationParticipants());
        var forged=new java.util.LinkedHashMap<String,Object>(body);forged.put("restockQuantity",999);assertEquals(400,accountRequest(f.seller().browser(),"POST",path,forged,csrfToken(f.seller().browser())).statusCode());assertEquals(before,returnApplicationParticipants());
    }

    @Test void acceptedReturnOrderJdbcNeverReadsClaimStorage() throws Exception {
        var f=returnParties("sql-ownership");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"ownership-request"));
        var statements=new java.util.concurrent.CopyOnWriteArrayList<Map<String,String>>();var phase=new java.util.concurrent.atomic.AtomicReference<String>();
        var transactional=new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(context.getBean(DataSource.class));
        var recorded=new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public java.sql.Connection getConnection(String username,String password) throws java.sql.SQLException { throw new java.sql.SQLFeatureNotSupportedException("This test recorder uses the existing configured datasource"); }
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var actual=transactional.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                    if((method.getName().equals("prepareStatement")||method.getName().equals("prepareCall"))&&args!=null&&args.length>0&&args[0] instanceof String sql) { statements.add(Map.of("entryPoint",phase.get(),"sql",sql)); }
                    try { return method.invoke(actual,args); }catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
        var queries=context.getBean(com.example.marketplace.order.api.OrderQueries.class);
        var applications=new com.example.marketplace.order.infrastructure.JdbcReturnApplications(new NamedParameterJdbcTemplate(recorded),queries,context.getBean(com.example.marketplace.order.api.ReturnSources.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class));
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{phase.set("lockOwner");assertEquals(accepted.returnId(),applications.lockOwner(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),accepted.returnId()).id());});
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{phase.set("lockSeller");assertEquals(accepted.returnId(),applications.lockSeller(shipmentAuthority(f.seller()),accepted.returnId()).id());});
        var other=returnParties("sql-foreign");var foreign=api.request(other.buyer().order().orderNumber(),shipmentAuthority(other.buyer()),returnRequest(other.buyer(),other.shipment(),"foreign-request"));var stable=returnApplicationParticipants();
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{phase.set("foreignOwner");assertEquals(404,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->applications.lockOwner(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),foreign.returnId())).status());});
        new TransactionTemplate(transactionManager).executeWithoutResult(t->{phase.set("foreignSeller");assertEquals(404,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->applications.lockSeller(shipmentAuthority(f.seller()),foreign.returnId())).status());});
        assertEquals(stable,returnApplicationParticipants());
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-order-jdbc-ownership.json"),authMapper.writeValueAsString(statements));
        for(String entry:List.of("lockOwner","lockSeller")) { assertTrue(statements.stream().anyMatch(s->s.get("entryPoint").equals(entry)&&s.get("sql").contains("order_return_application")),"each actual entry point must execute recorded order JDBC"); }
        var forbidden=java.util.regex.Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+(?:marketplace\\.)?claim_[a-z0-9_]+\\b");
        assertTrue(statements.stream().noneMatch(s->forbidden.matcher(s.get("sql")).find()),"order accepted-source JDBC must not read/write claim-owned relations: "+statements);
    }

    @Test void returnCollectionRejectsForgedUnitsAndTracksLostThenActualRecovery() throws Exception {
        var f=returnParties("collection-negative");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var collections=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class);
        var requested=returnRequest(f.buyer(),f.shipment(),"collection-negative");var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),requested);var unit=requested.units().getFirst();
        var received=new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED);
        var before=returnApplicationParticipants();assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("before-approve",0L,List.of(received),"승인 전 회수 금지"))).status());assertEquals(before,returnApplicationParticipants());
        api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("negative-approve",0L,"실제 승인"));
        String path="/api/seller/returns/"+accepted.returnId()+"/receive";var body=Map.of("key","negative-receive","expectedRevision",1,"units",List.of(Map.of("itemId",unit.itemId(),"ordinal",unit.ordinal(),"status","RECEIVED")),"reason","잘못된 입력 차단");before=returnApplicationParticipants();
        assertEquals(404,accountRequest(f.buyer().browser(),"POST",path,body,csrfToken(f.buyer().browser())).statusCode());assertEquals(403,accountRequest(f.seller().browser(),"POST",path,body,null).statusCode());
        var forged=new java.util.LinkedHashMap<String,Object>(body);forged.put("restockQuantity",99);assertEquals(400,accountRequest(f.seller().browser(),"POST",path,forged,csrfToken(f.seller().browser())).statusCode());
        assertEquals(400,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("duplicate",1L,List.of(received,received),"중복 원단위"))).status());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("outside",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.itemId(),99,received.status())),"미신청 원단위"))).status());assertEquals(before,returnApplicationParticipants());
        var lost=new com.example.marketplace.claim.api.ReturnCollections.Command("actual-lost",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.LOST_REPORTED)),"미회수 분실 주장");
        var pending=collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),lost);assertEquals("REVIEW_REQUIRED",pending.state());assertEquals("LOST_REPORTED",pending.physical().getFirst().collection());assertEquals("NOT_INSPECTED",pending.physical().getFirst().inspection());
        var stable=returnApplicationParticipants();assertEquals(pending,collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),lost));assertEquals(stable,returnApplicationParticipants());
        assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command(lost.key(),1L,List.of(received),lost.reason()))).status());assertEquals(stable,returnApplicationParticipants());
        var recovery=collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("actual-found",2L,List.of(received),"나중에 실제 수령"));assertEquals("COLLECTING",recovery.state());assertEquals(3,recovery.version());assertEquals("RECEIVED",recovery.physical().getFirst().collection());assertEquals("NOT_INSPECTED",recovery.physical().getFirst().inspection());
        assertEquals(before.get("inventory_balance"),returnApplicationParticipants().get("inventory_balance"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_collection WHERE return_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM marketplace.claim_return_collection WHERE return_id=:id",Map.of("id",accepted.returnId())));assertEquals(recovery,api.get(f.buyer().order().orderNumber(),accepted.returnId(),shipmentAuthority(f.buyer())));
    }

    @Test void returnCollectionEverySqlCheckpointRollsBackAndResumesOnce() throws Exception {
        var f=returnParties("collection-rollback");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var collections=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class);
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"collection-rollback"));api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("rollback-approve",0L,"실제 승인"));var unit=accepted.units().getFirst();
        var command=new com.example.marketplace.claim.api.ReturnCollections.Command("rollback-receive",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)),"회수 기록 원자성");var before=returnApplicationParticipants();
        for(String table:List.of("claim_return_collection","claim_return_request","claim_return_history","claim_return_command","job","outbox_event")) {
            jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.d_collection_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'ACTUAL_D_COLLECTION_"+table+"' USING ERRCODE='23514';END $$");
            jdbc.getJdbcTemplate().execute("CREATE TRIGGER d_collection_reject BEFORE "+(table.equals("claim_return_request")?"UPDATE":"INSERT")+" ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.d_collection_failure()");
            try { var failure=assertThrows(RuntimeException.class,()->collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),command));Throwable root=failure;while(root.getCause()!=null) { root=root.getCause(); }assertTrue(root instanceof java.sql.SQLException);assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertTrue(root.getMessage().contains("ACTUAL_D_COLLECTION_"+table),root.toString());assertEquals(before,returnApplicationParticipants()); }
            finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER d_collection_reject ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.d_collection_failure()"); }
        }
        var result=collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),command);var stable=returnApplicationParticipants();assertEquals(result,collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),command));assertEquals(stable,returnApplicationParticipants());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_collection WHERE return_id=:id",Map.of("id",accepted.returnId()),Integer.class));
    }

    @Test void returnInspectionRestoresOnlySaleableOriginalUnitsAndKeepsCommittedEvidence() throws Exception {
        var f=returnParties("inspection-stock");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var collections=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class);
        var units=f.shipment().units().stream().limit(3).map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList();assertEquals(3,units.size());
        var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),new com.example.marketplace.claim.api.Returns.Request("inspect-stock",confirmations().get(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer())).revision(),units,"판매가능2 불량1"));api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("stock-approve",0L,"실제 승인"));
        collections.receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("stock-receive",1L,units.stream().map(u->new com.example.marketplace.claim.api.ReturnCollections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)).toList(),"실제 세 원단위 수령"));
        var stock=new java.util.LinkedHashMap<UUID,Long>();var skuByItem=new java.util.HashMap<UUID,UUID>();for(var unit:units) { UUID sku=jdbc.queryForObject("SELECT sku_id FROM marketplace.order_item WHERE id=:id",Map.of("id",unit.itemId()),UUID.class);skuByItem.put(unit.itemId(),sku);stock.put(sku,jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",sku),Long.class)); }
        var inventory=context.getBean(com.example.marketplace.inventory.api.Reservations.class);var committed=inventory.inspect(f.buyer().order().checkoutId(),orders().reservations(f.buyer().order().orderNumber()).inventory());assertTrue(committed.stream().allMatch(r->r.status().equals("COMMITTED")));
        var targets=new java.util.ArrayList<Map<String,Object>>();for(int i=0;i<units.size();i++) { var unit=units.get(i);targets.add(Map.of("itemId",unit.itemId(),"ordinal",unit.ordinal(),"condition",i<2?"SALEABLE":"DEFECTIVE","responsibility","SELLER")); }
        var body=Map.of("key","inspect-three","expectedRevision",2,"units",targets,"reason","실물 검수 근거: 재판매가능2 불량1");String path="/api/seller/returns/"+accepted.returnId()+"/inspect";var response=accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser()));assertEquals(200,response.statusCode(),"actual original-unit inspection is missing: "+response.body());
        var result=authMapper.readTree(response.body());assertEquals("AWAITING_CONSENT",result.path("state").asText());assertEquals(3,result.path("version").asLong());int saleable=0,defective=0;for(var u:result.path("physical")) { assertEquals("RECEIVED",u.path("collection").asText());assertTrue(!u.path("inspectionId").isNull());if(u.path("inspection").asText().equals("SALEABLE")) { saleable++;assertTrue(!u.path("restitutionId").isNull()); }else if(u.path("inspection").asText().equals("DEFECTIVE")) { defective++;assertTrue(u.path("restitutionId").isNull()); } }
        assertEquals(2,saleable);assertEquals(1,defective);for(var entry:stock.entrySet()) { long added=units.subList(0,2).stream().filter(u->skuByItem.get(u.itemId()).equals(entry.getKey())).count();assertEquals(entry.getValue()+added,jdbc.queryForObject("SELECT on_hand FROM marketplace.inventory_balance WHERE sku_id=:id",Map.of("id",entry.getKey()),Long.class)); }
        assertEquals(committed,inventory.inspect(f.buyer().order().checkoutId(),orders().reservations(f.buyer().order().orderNumber()).inventory()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",accepted.returnId()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_fee_assessment WHERE intent_id=:id",Map.of("id",accepted.returnId()),Integer.class));
        var stable=returnApplicationParticipants();assertEquals(response.body(),accountRequest(f.seller().browser(),"POST",path,body,csrfToken(f.seller().browser())).body());assertEquals(stable,returnApplicationParticipants());
        var original=units.getFirst();var reservation=committed.stream().filter(r->r.orderItemId().equals(original.itemId())).findFirst().orElseThrow();var restitutions=context.getBean(com.example.marketplace.inventory.api.Restitutions.class);
        assertEquals(409,assertThrows(com.example.marketplace.inventory.api.InventoryException.class,()->new TransactionTemplate(transactionManager).execute(t->restitutions.restoreCancellation(new com.example.marketplace.inventory.api.Restitutions.Request(UUID.randomUUID(),f.buyer().order().checkoutId(),List.of(new com.example.marketplace.inventory.api.Restitutions.Unit(reservation.id(),original.itemId(),original.ordinal(),reservation.skuId())))))).status());assertEquals(stable,returnApplicationParticipants());
    }

    @Test void returnInboundWithFictionalInspectionCannotCommit() throws Exception {
        var f=returnParties("fictional-inspection");var unit=f.shipment().units().getFirst();var inventory=context.getBean(com.example.marketplace.inventory.api.Reservations.class);
        var stocks=inventory.inspect(f.buyer().order().checkoutId(),orders().reservations(f.buyer().order().orderNumber()).inventory());var reservation=stocks.stream().filter(r->r.orderItemId().equals(unit.orderItemId())).findFirst().orElseThrow();assertEquals("COMMITTED",reservation.status());
        UUID inspection=UUID.randomUUID(),returnId=UUID.randomUUID();var before=returnApplicationParticipants();
        assertReturnGraphCommitRejected("fictional-inspection",before,()->context.getBean(com.example.marketplace.inventory.api.Restitutions.class).restoreReturn(new com.example.marketplace.inventory.api.Restitutions.ReturnRequest(inspection,returnId,f.buyer().order().checkoutId(),List.of(new com.example.marketplace.inventory.api.Restitutions.Unit(reservation.id(),unit.orderItemId(),unit.ordinal(),reservation.skuId())))));
        assertEquals(stocks,inventory.inspect(f.buyer().order().checkoutId(),orders().reservations(f.buyer().order().orderNumber()).inventory()));
    }

    @Test void returnSaleableInspectionWithoutActualInboundReceiptCannotCommit() throws Exception {
        var f=returnParties("missing-inbound");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);var accepted=api.request(f.buyer().order().orderNumber(),shipmentAuthority(f.buyer()),returnRequest(f.buyer(),f.shipment(),"missing-inbound"));
        api.approve(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.Returns.Command("missing-inbound-approve",0L,"실제 승인"));var unit=accepted.units().getFirst();
        var collected=context.getBean(com.example.marketplace.claim.api.ReturnCollections.class).receive(accepted.returnId(),shipmentAuthority(f.seller()),new com.example.marketplace.claim.api.ReturnCollections.Command("missing-inbound-receive",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)),"실제 수령"));
        UUID collection=collected.physical().getFirst().collectionId(),inspection=UUID.randomUUID();var before=returnApplicationParticipants();
        assertReturnGraphCommitRejected("inspection-without-inbound",before,()->{
            context.getBean(com.example.marketplace.order.api.ReturnApplications.class).lockSeller(shipmentAuthority(f.seller()),accepted.returnId());jdbc.queryForMap("SELECT id FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",accepted.returnId()));context.getBean(com.example.marketplace.claim.api.FinancialIntents.class).lock(accepted.returnId());
            var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);String reason="入고 영수증 없는 실제 판매가능 검수";
            var p=new MapSqlParameterSource("inspection",inspection).addValue("id",accepted.returnId()).addValue("order",f.buyer().order().id()).addValue("seller",f.buyer().order().sellers().getFirst().id()).addValue("checkout",f.buyer().order().checkoutId()).addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("collection",collection).addValue("reason",reason).addValue("actor","MEMBER:"+f.seller().authority().member()).addValue("at",java.sql.Timestamp.from(at));
            jdbc.update("INSERT INTO marketplace.claim_return_inspection(id,return_id,order_id,seller_order_id,checkout_id,version,reason,actor,occurred_at) VALUES(:inspection,:id,:order,:seller,:checkout,3,:reason,:actor,:at)",p);
            jdbc.update("INSERT INTO marketplace.claim_return_inspection_unit(inspection_id,return_id,item_id,ordinal,collection_id,condition,responsibility,restitution_id) VALUES(:inspection,:id,:item,:ordinal,:collection,'SALEABLE','SELLER',:inspection)",p);
            jdbc.update("UPDATE marketplace.claim_return_request SET state='AWAITING_CONSENT',version=3 WHERE id=:id",p);
            jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,'AWAITING_CONSENT',3,:actor,:reason,:at)",p.addValue("event",UUID.randomUUID()));
            var history=new java.util.ArrayList<>(collected.history());history.add(new com.example.marketplace.claim.api.Returns.History("AWAITING_CONSENT",3,reason,at));var physical=List.of(new com.example.marketplace.claim.api.Returns.Physical(unit.itemId(),unit.ordinal(),"RECEIVED","SALEABLE",com.example.marketplace.claim.api.Returns.Responsibility.SELLER,collection,inspection,inspection));
            var receipt=new com.example.marketplace.claim.api.Returns.Progress(accepted,"AWAITING_CONSENT",3,history,physical);
            jdbc.update("INSERT INTO marketplace.claim_return_command(return_id,request_key,fingerprint,action,version,receipt) VALUES(:id,'missing-inbound',repeat('0',64),'AWAITING_CONSENT',3,CAST(:receipt AS jsonb))",p.addValue("receipt",authMapper.writeValueAsString(receipt)));
            String key="return-command:"+accepted.returnId()+":3";context.getBean(com.example.marketplace.platform.api.Outbox.class).append(new com.example.marketplace.platform.api.Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new com.example.marketplace.platform.api.Jobs.Command("RETURN_RECORDED.v1",key,accepted.returnId().toString(),accepted.returnId().toString(),at)));
        });
    }

    @Test void returnInspectionEverySqlAndStockCheckpointRollsBackAndResumes() throws Exception {
        var f=physicalReturn("inspection-rollback",2,true);var inspections=context.getBean(com.example.marketplace.claim.api.ReturnInspections.class);var first=f.accepted().units().getFirst();var second=f.accepted().units().getLast();
        var command=new com.example.marketplace.claim.api.ReturnInspections.Command("inspection-atomic",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(first.itemId(),first.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER),new com.example.marketplace.claim.api.ReturnInspections.Unit(second.itemId(),second.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.DEFECTIVE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"판매가능 입고와 검수/감사 원자성");
        var before=returnApplicationParticipants();var reached=new java.util.ArrayList<String>();
        for(String table:List.of("claim_return_inspection","claim_return_inspection_unit","inventory_restitution","inventory_restitution_unit","inventory_balance","inventory_restitution_movement","inventory_return_restitution","claim_return_request","claim_return_history","claim_return_command","job","outbox_event")) {
            jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.d_inspection_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'ACTUAL_D_INSPECTION_"+table+"' USING ERRCODE='23514';END $$");
            jdbc.getJdbcTemplate().execute("CREATE TRIGGER d_inspection_reject BEFORE "+(List.of("inventory_balance","claim_return_request").contains(table)?"UPDATE":"INSERT")+" ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.d_inspection_failure()");
            try { var failure=assertThrows(RuntimeException.class,()->inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),command));Throwable root=failure;while(root.getCause()!=null) { root=root.getCause(); }assertTrue(root instanceof java.sql.SQLException);assertEquals("23514",((java.sql.SQLException)root).getSQLState());assertTrue(root.getMessage().contains("ACTUAL_D_INSPECTION_"+table),root.toString());assertEquals(before,returnApplicationParticipants(),"all stock/inspection/original/history/outbox participants roll back at "+table);reached.add(table); }
            finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER d_inspection_reject ON marketplace."+table);jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.d_inspection_failure()"); }
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-inspection-rollback-checkpoints.json"),authMapper.writeValueAsString(Map.of("actualSqlCheckpoints",reached,"everyParticipantRolledBack",true)));
        var result=inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),command);assertEquals("AWAITING_CONSENT",result.state());assertEquals(3,result.version());assertEquals(before.get("inventory_reservation"),returnApplicationParticipants().get("inventory_reservation"));
        var stable=returnApplicationParticipants();assertEquals(result,inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),command));assertEquals(stable,returnApplicationParticipants());
    }

    @Test void returnPartialAndDefectiveInspectionsPreserveHistoryAndNeverAutoRefund() throws Exception {
        for(boolean defectiveOnly:List.of(false,true)) {
            var f=physicalReturn("inspection-partial-"+defectiveOnly,2,true);var inspections=context.getBean(com.example.marketplace.claim.api.ReturnInspections.class);var first=f.accepted().units().getFirst();var second=f.accepted().units().getLast();var before=returnApplicationParticipants();
            if(defectiveOnly) {
                var units=f.accepted().units().stream().map(u->new com.example.marketplace.claim.api.ReturnInspections.Unit(u.itemId(),u.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.DEFECTIVE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)).toList();
                var command=new com.example.marketplace.claim.api.ReturnInspections.Command("defective-all",2L,units,"전량 불량은 입고하지 않음");var result=inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),command);assertEquals("AWAITING_CONSENT",result.state());assertTrue(result.physical().stream().allMatch(u->u.inspection().equals("DEFECTIVE")&&u.restitutionId()==null));assertEquals(before.get("inventory_balance"),returnApplicationParticipants().get("inventory_balance"));
                var original=context.getBean(com.example.marketplace.inventory.api.Reservations.class).inspect(f.parties().buyer().order().checkoutId(),orders().reservations(f.parties().buyer().order().orderNumber()).inventory()).stream().filter(r->r.orderItemId().equals(first.itemId())).findFirst().orElseThrow();var stable=returnApplicationParticipants();UUID inspection=result.physical().getFirst().inspectionId();
                assertReturnGraphCommitRejected("defective-inbound",stable,()->context.getBean(com.example.marketplace.inventory.api.Restitutions.class).restoreReturn(new com.example.marketplace.inventory.api.Restitutions.ReturnRequest(inspection,f.accepted().returnId(),f.parties().buyer().order().checkoutId(),List.of(new com.example.marketplace.inventory.api.Restitutions.Unit(original.id(),first.itemId(),first.ordinal(),original.skuId())))));
            } else {
                var one=new com.example.marketplace.claim.api.ReturnInspections.Command("partial-saleable",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(first.itemId(),first.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"첫 원단위만 판매가능 검수");
                var partial=inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),one);assertEquals("COLLECTING",partial.state());assertEquals(3,partial.version());assertEquals(1,partial.physical().stream().filter(u->u.inspection().equals("SALEABLE")).count());assertEquals(1,partial.physical().stream().filter(u->u.inspection().equals("NOT_INSPECTED")).count());
                var afterFirst=returnApplicationParticipants();var two=new com.example.marketplace.claim.api.ReturnInspections.Command("partial-defective",3L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(second.itemId(),second.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.DEFECTIVE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"나머지 원단위 불량 검수");
                var complete=inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),two);assertEquals("AWAITING_CONSENT",complete.state());assertEquals(4,complete.version());assertEquals(afterFirst.get("inventory_balance"),returnApplicationParticipants().get("inventory_balance"));var stable=returnApplicationParticipants();assertEquals(partial,inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),one));assertEquals(stable,returnApplicationParticipants());
                assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),new com.example.marketplace.claim.api.ReturnInspections.Command("inspect-again",4L,one.units(),"다른key 중복 검수 금지"))).status());assertEquals(stable,returnApplicationParticipants());
            }
            assertEquals(before.get("inventory_reservation"),returnApplicationParticipants().get("inventory_reservation"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt WHERE claim_id=:id",Map.of("id",f.accepted().returnId()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_fee_assessment WHERE intent_id=:id",Map.of("id",f.accepted().returnId()),Integer.class));
        }
    }

    @Test void returnPhysicalAuthorityExpiresAfterFinalIntentOrStockWaitWithoutEffects() throws Exception {
        for(boolean inspection:List.of(false,true)) {
            var f=physicalReturn("physical-auth-"+inspection,1,inspection);var seller=shipmentAuthority(f.parties().seller());long expires=jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:id",Map.of("id",seller.browser()),Long.class);var clock=new JobClock();clock.now=Instant.now();Instant start=clock.instant();assertTrue(clock.millis()<expires);
            var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),context.getBean(com.example.marketplace.guest.api.GuestAccessSessions.class),new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,clock),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);
            var applications=new com.example.marketplace.order.infrastructure.JdbcReturnApplications(jdbc,queries,context.getBean(com.example.marketplace.order.api.ReturnSources.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),clock);
            var collections=new com.example.marketplace.claim.infrastructure.ReturnCollectionCommands(jdbc,queries,applications,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,clock);
            var inspections=new com.example.marketplace.claim.infrastructure.ReturnInspectionCommands(jdbc,queries,applications,context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),orders(),context.getBean(com.example.marketplace.inventory.api.Reservations.class),context.getBean(com.example.marketplace.inventory.api.Restitutions.class),context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager,clock);
            var unit=f.accepted().units().getFirst();var receive=new com.example.marketplace.claim.api.ReturnCollections.Command("expired-physical",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)),"최종 intent 잠금 후 만료");var inspect=new com.example.marketplace.claim.api.ReturnInspections.Command("expired-physical",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"최종 재고 잠금 후 만료");
            java.util.concurrent.Callable<com.example.marketplace.claim.api.Returns.Progress> invoke=()->inspection?inspections.inspect(f.accepted().returnId(),seller,inspect):collections.receive(f.accepted().returnId(),seller,receive);var before=returnApplicationParticipants();String table=inspection?"inventory_balance":"claim_financial_intent";
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);String sql=inspection?"SELECT sku_id FROM marketplace.inventory_balance WHERE sku_id=? FOR UPDATE":"SELECT id FROM marketplace.claim_financial_intent WHERE id=? FOR UPDATE";UUID target=inspection?jdbc.queryForObject("SELECT sku_id FROM marketplace.order_item WHERE id=:id",Map.of("id",unit.itemId()),UUID.class):f.accepted().returnId();
                try(var lock=connection.prepareStatement(sql)) { lock.setObject(1,target);lock.executeQuery().close(); }var result=pool.submit(()->assertThrows(com.example.marketplace.order.api.OrderRejected.class,invoke::call).status());awaitCouponLock(table);clock.now=Instant.ofEpochMilli(expires).plusNanos(1000);assertTrue(clock.instant().isAfter(Instant.ofEpochMilli(expires)));connection.commit();assertEquals(404,result.get(10,TimeUnit.SECONDS));
            }
            assertEquals(before,returnApplicationParticipants());java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-physical-authority-"+inspection+".json"),authMapper.writeValueAsString(Map.of("inspection",inspection,"actualFinalLock",table,"storedDeadline",Instant.ofEpochMilli(expires).toString(),"clockAfterActualWait",clock.instant().toString(),"status",404,"participantsUnchanged",true)));clock.now=start;assertEquals(inspection?"AWAITING_CONSENT":"COLLECTING",invoke.call().state());
        }
    }

    @Test void returnInboundReceiptWithoutActualStockEffectRollsBackTheInspection() throws Exception {
        var f=physicalReturn("marker-no-balance",1,true);var unit=f.accepted().units().getFirst();UUID inspection=UUID.randomUUID();var before=returnApplicationParticipants();
        assertReturnGraphCommitRejected("inbound-without-balance",before,()->{
            context.getBean(com.example.marketplace.order.api.ReturnApplications.class).lockSeller(shipmentAuthority(f.parties().seller()),f.accepted().returnId());jdbc.queryForMap("SELECT id FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",f.accepted().returnId()));context.getBean(com.example.marketplace.claim.api.FinancialIntents.class).lock(f.accepted().returnId());
            var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var p=new MapSqlParameterSource("inspection",inspection).addValue("id",f.accepted().returnId()).addValue("order",f.parties().buyer().order().id()).addValue("seller",f.parties().buyer().order().sellers().getFirst().id()).addValue("checkout",f.parties().buyer().order().checkoutId()).addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("collection",f.collected().physical().getFirst().collectionId()).addValue("at",java.sql.Timestamp.from(at));
            jdbc.update("INSERT INTO marketplace.claim_return_inspection(id,return_id,order_id,seller_order_id,checkout_id,version,reason,actor,occurred_at) VALUES(:inspection,:id,:order,:seller,:checkout,3,'입고 ledger만 위조','test-marker',:at)",p);
            jdbc.update("INSERT INTO marketplace.claim_return_inspection_unit(inspection_id,return_id,item_id,ordinal,collection_id,condition,responsibility,restitution_id) VALUES(:inspection,:id,:item,:ordinal,:collection,'SALEABLE','SELLER',:inspection)",p);
            var reservation=context.getBean(com.example.marketplace.inventory.api.Reservations.class).inspect(f.parties().buyer().order().checkoutId(),orders().reservations(f.parties().buyer().order().orderNumber()).inventory()).stream().filter(r->r.orderItemId().equals(unit.itemId())).findFirst().orElseThrow();assertEquals("COMMITTED",reservation.status());
            var balance=jdbc.queryForMap("SELECT on_hand,reserved,version FROM marketplace.inventory_balance WHERE sku_id=:id FOR UPDATE",Map.of("id",reservation.skuId()));p.addValue("reservation",reservation.id()).addValue("sku",reservation.skuId()).addValue("before",balance.get("on_hand")).addValue("reserved",balance.get("reserved")).addValue("version",balance.get("version"));
            jdbc.update("INSERT INTO marketplace.inventory_restitution(cause_id,checkout_id,fingerprint,kind) VALUES(:inspection,:checkout,repeat('0',64),'RETURN')",p);
            jdbc.update("INSERT INTO marketplace.inventory_restitution_unit(cause_id,reservation_id,item_id,ordinal,sku_id,checkout_id) VALUES(:inspection,:reservation,:item,:ordinal,:sku,:checkout)",p);
            jdbc.update("INSERT INTO marketplace.inventory_restitution_movement(cause_id,sku_id,quantity,before_on_hand,after_on_hand,reserved,before_version,version) VALUES(:inspection,:sku,1,:before,:before+1,:reserved,:version,:version+1)",p);
            // Legal coordinator prefix at marker insertion, except the real balance update is deliberately absent.
            jdbc.update("INSERT INTO marketplace.inventory_return_restitution(inspection_id,return_id,checkout_id,cause_id) VALUES(:inspection,:id,:checkout,:inspection)",p);
        });
    }

    @Test void returnInboundJdbcUsesOnlyInventoryOwnedStorage() throws Exception {
        var f=physicalReturn("inventory-jdbc",1,true);var statements=new java.util.concurrent.CopyOnWriteArrayList<String>();var transactional=new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(context.getBean(DataSource.class));
        var recorded=new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public java.sql.Connection getConnection(String username,String password) throws java.sql.SQLException { throw new java.sql.SQLFeatureNotSupportedException("Existing configured datasource only"); }
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException { var actual=transactional.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{if((method.getName().equals("prepareStatement")||method.getName().equals("prepareCall"))&&args!=null&&args.length>0&&args[0] instanceof String sql) { statements.add(sql); }try { return method.invoke(actual,args); }catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }}); }
        };
        var restitutions=new com.example.marketplace.inventory.infrastructure.JdbcRestitutions(new NamedParameterJdbcTemplate(recorded));
        var inspections=new com.example.marketplace.claim.infrastructure.ReturnInspectionCommands(jdbc,context.getBean(com.example.marketplace.order.api.OrderQueries.class),context.getBean(com.example.marketplace.order.api.ReturnApplications.class),context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),context.getBean(com.example.marketplace.claim.infrastructure.ReturnCommands.class),orders(),context.getBean(com.example.marketplace.inventory.api.Reservations.class),restitutions,context.getBean(com.example.marketplace.platform.api.Outbox.class),transactionManager);
        var unit=f.accepted().units().getFirst();var result=inspections.inspect(f.accepted().returnId(),shipmentAuthority(f.parties().seller()),new com.example.marketplace.claim.api.ReturnInspections.Command("actual-inventory-jdbc",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(unit.itemId(),unit.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"실제 입고 adapter 소유권"));assertEquals("AWAITING_CONSENT",result.state());assertTrue(result.physical().getFirst().restitutionId()!=null);
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-inventory-jdbc-ownership.json"),authMapper.writeValueAsString(statements));
        assertTrue(statements.stream().anyMatch(s->s.startsWith("UPDATE marketplace.inventory_balance")));assertTrue(statements.stream().anyMatch(s->s.startsWith("INSERT INTO marketplace.inventory_return_restitution")));
        var relations=java.util.regex.Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+(?:marketplace\\.)?([a-z_][a-z0-9_]*)");for(String statement:statements) { var matcher=relations.matcher(statement);while(matcher.find()) { assertTrue(matcher.group(1).toLowerCase(java.util.Locale.ROOT).startsWith("inventory_"),"inventory adapter must not read another module's storage: "+statement); } }
    }

    @Test void returnReadFinalAuthorityAfterActualClaimWaitDeniesExpiredMemberGasSellerAndSupport() throws Exception {
        var evidence=new java.util.ArrayList<Map<String,Object>>();
        for(String actor:List.of("MEMBER","GAS","SELLER","SUPPORT")){var f=returnReadActorFixture(actor);Instant initial=f.clock().instant(),guestInitial=f.guest()==null?null:f.guest().clock.instant();var sessions=new com.example.marketplace.identity.infrastructure.JdbcPurchaseSessions(jdbc,f.clock());var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),f.gas(),sessions,context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var applications=new com.example.marketplace.order.infrastructure.JdbcReturnApplications(jdbc,queries,context.getBean(com.example.marketplace.order.api.ReturnSources.class),context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class),context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class),f.clock());var support=new com.example.marketplace.claim.infrastructure.CurrentSupport(sessions,context.getBean(com.example.marketplace.identity.api.AccessPolicy.class));
            for(String operation:List.of("LIST","DETAIL")){for(String state:actor.equals("SUPPORT")?List.of("VALID","EXPIRED","REVOKED"):List.of("VALID","EXPIRED")){
                f.clock().now=initial;if(f.guest()!=null){f.guest().clock.now=guestInitial;}var deadline=new java.util.concurrent.atomic.AtomicReference<Instant>();var statements=new java.util.concurrent.CopyOnWriteArrayList<String>();var storage=claimStorageRecorder(statements,sql->{if(sql.contains("claim_return_request")&&sql.contains("FOR UPDATE")){Instant saved=f.guest()==null?Instant.ofEpochMilli(jdbc.queryForObject("SELECT expiry_time FROM marketplace.spring_session WHERE session_id=:id",Map.of("id",f.actor().browser()),Long.class)):jdbc.queryForObject("SELECT idle_expires_at FROM marketplace.guest_access_session WHERE token_hash=:token AND revoked_at IS NULL",Map.of("token",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(f.actor().guestToken())),java.sql.Timestamp.class).toInstant();deadline.set(saved);}});var reads=returnReadsWith(storage,queries,applications,support);java.util.concurrent.Callable<Object> invoke=()->{boolean list=operation.equals("LIST");return switch(actor){case "SELLER"->list?reads.seller(f.parties().shipment().sellerOrderId(),f.actor(),20,null):reads.seller(f.parties().shipment().sellerOrderId(),f.accepted().returnId(),f.actor());case "SUPPORT"->list?reads.support(f.parties().buyer().order().orderNumber(),f.actor(),20,null):reads.support(f.parties().buyer().order().orderNumber(),f.accepted().returnId(),f.actor());default->list?reads.owner(f.parties().buyer().order().orderNumber(),f.actor(),20,null):reads.owner(f.parties().buyer().order().orderNumber(),f.accepted().returnId(),f.actor());};};var before=returnApplicationParticipants();var permissionRemoved=new AtomicBoolean();
                try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()){connection.setAutoCommit(false);int blocker;try(var sql=connection.createStatement();var result=sql.executeQuery("SELECT pg_backend_pid()")){assertTrue(result.next());blocker=result.getInt(1);}try(var lock=connection.prepareStatement("SELECT id FROM marketplace.claim_return_request WHERE id=? FOR UPDATE")){lock.setObject(1,f.accepted().returnId());lock.executeQuery().close();}var call=pool.submit(()->{try{return new ReadOutcome(200,invoke.call());}catch(com.example.marketplace.order.api.OrderRejected denied){return new ReadOutcome(denied.status(),null);}});var waits=new java.util.concurrent.atomic.AtomicReference<List<Map<String,Object>>>();awaitJobCondition(()->{jdbc.getJdbcTemplate().execute("SELECT pg_stat_clear_snapshot()");var actual=jdbc.queryForList("SELECT pid,query,wait_event_type,pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity WHERE datname=current_database() AND :pid=ANY(pg_blocking_pids(pid))",Map.of("pid",blocker));waits.set(actual);try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-read-wait-"+actor+"-"+operation+"-"+state+".json"),authMapper.writeValueAsString(Map.of("actor",actor,"operation",operation,"scenario",state,"blocker",blocker,"actualWaits",actual,"future",recoveryFutureState(call))));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}return !actual.isEmpty()||call.isDone();},"actual read must reach its final claim row lock");assertFalse(call.isDone(),recoveryFutureState(call));assertTrue(waits.get().stream().anyMatch(row->row.get("query").toString().contains("claim_return_request")));assertTrue(deadline.get()!=null);
                    if(state.equals("EXPIRED")){if(f.guest()==null){f.clock().now=deadline.get().plusNanos(1000);}else{f.guest().clock.now=deadline.get().plusNanos(1000);}assertTrue((f.guest()==null?f.clock().instant():f.guest().clock.instant()).isAfter(deadline.get()));}else if(state.equals("REVOKED")){assertEquals(1,jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id AND permission='SUPPORT'",Map.of("id",f.actor().member())));permissionRemoved.set(true);}connection.commit();var result=call.get(10,TimeUnit.SECONDS);int expected=state.equals("VALID")?200:state.equals("REVOKED")?403:actor.equals("SUPPORT")?401:404;assertEquals(expected,result.status(),actor+operation+state);if(expected!=200){assertNull(result.body());}else{assertTrue(result.body() instanceof com.example.marketplace.claim.api.ReturnQueries.Page||result.body() instanceof com.example.marketplace.claim.api.ReturnQueries.Detail);if(actor.equals("SUPPORT")){var projected=authMapper.readTree(authMapper.writeValueAsString(result.body()));var shipping=operation.equals("LIST")?projected.path("shipping").get(0):projected.path("shipping");assertEquals("RETURN_SHIPPING",shipping.path("supportContract").asText());assertEquals(f.accepted().returnId().toString(),shipping.path("originIntentId").asText());assertEquals("FAILED",shipping.path("resultState").asText());}}if(state.equals("VALID")&&actor.equals("GAS")){assertGasReadHasOnlySlidingCredentialEffect(before,returnApplicationParticipants(),com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(f.actor().guestToken()),false);}else{assertEquals(before,returnApplicationParticipants());}evidence.add(Map.of("actor",actor,"operation",operation,"scenario",state,"savedDeadlineObservedInsideActualReadTransaction",deadline.get(),"afterWaitClock",f.guest()==null?f.clock().instant():f.guest().clock.instant(),"actualStatus",result.status(),"privateBodyReturned",result.body()!=null,"allBusinessParticipantsUnchanged",true));
                }finally{if(permissionRemoved.get()){jdbc.update("INSERT INTO marketplace.admin_permission(user_id,permission) VALUES(:id,'SUPPORT')",Map.of("id",f.actor().member()));}}
                for(String sql:statements){assertFalse(sql.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(?:from|join|update|into)\\s+(?:marketplace\\.)?(?:order_|purchase_order|seller_order|payment_|coupon_|inventory_)[a-z_]*.*"),sql);}java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-read-actual-claim-owned-jdbc-"+actor+"-"+operation+"-"+state+".json"),authMapper.writeValueAsString(statements));
            }}
        }assertEquals(18,evidence.size());java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-read-final-authority-actual.json"),authMapper.writeValueAsString(evidence));
    }

    @Test void actualGasReturnReadHttpUsesCurrentCookieOwnershipCsrfAndNoPrivateCache() throws Exception {
        var c=feeAuthorityCase("d-read-http-gas",true);var f=c.fixture();var seller=asSeller(f,f.order().sellers().getFirst().id());var shipment=shipments().seller(seller.order().sellers().getFirst().id(),shipmentAuthority(seller)).getFirst();var selected=shipment.units().getFirst();var command=new com.example.marketplace.claim.api.Returns.Request("d-http-gas-read",confirmations().get(f.order().orderNumber(),c.authority()).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(selected.orderItemId(),selected.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"현재 GAS 실제 조회 준비");var accepted=context.getBean(com.example.marketplace.claim.api.Returns.class).request(f.order().orderNumber(),c.authority(),command);var browser=HttpClient.newBuilder().localAddress(new java.net.InetSocketAddress("127.0.0.6",0).getAddress()).cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();assertEquals(200,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",f.order().orderNumber(),"phone",c.guest().phone,"password",GUEST_PASSWORD),csrfToken(browser)).statusCode());String currentGasHash=com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(((java.net.CookieManager)browser.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("GUEST_ACCESS")).findFirst().orElseThrow().getValue());String list="/api/orders/"+f.order().orderNumber()+"/returns",view=list+"/"+accepted.returnId()+"/view";var before=returnApplicationParticipants();var response=accountRequest(browser,"GET",list+"?size=1",null,null);assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));assertEquals(accepted.returnId().toString(),authMapper.readTree(response.body()).path("returns").get(0).path("returnId").asText());var detail=accountRequest(browser,"GET",view,null,null);assertEquals(200,detail.statusCode());var body=authMapper.readTree(detail.body());assertEquals("REQUESTED",body.path("progress").path("state").asText());assertEquals(1,body.path("originalUnits").size());assertTrue(body.path("originalUnits").get(0).path("net").isTextual());assertEquals("NONE",body.path("item").path("state").asText());assertGasReadHasOnlySlidingCredentialEffect(before,returnApplicationParticipants(),currentGasHash,false);before=returnApplicationParticipants();
        assertEquals(403,accountRequest(browser,"POST",list,Map.of(),null).statusCode());assertEquals(403,accountRequest(browser,"POST",list+"/"+accepted.returnId()+"/withdraw",Map.of(),null).statusCode());assertEquals(403,accountRequest(seller.browser(),"POST","/api/seller/returns/"+accepted.returnId()+"/approve",Map.of(),null).statusCode());var operator=supportFixture("d-read-http-support","SUPPORT");assertEquals(200,accountRequest(operator.browser(),"GET","/api/admin/support/orders/"+f.order().orderNumber()+"/returns/"+accepted.returnId(),null,null).statusCode());before=returnApplicationParticipants();assertEquals(403,accountRequest(operator.browser(),"POST","/api/admin/support/orders/"+f.order().orderNumber()+"/returns/"+accepted.returnId()+"/refunds/ITEM/query",Map.of(),null).statusCode());assertEquals(before,returnApplicationParticipants());
        var other=cancellationGuest("d-read-http-other");assertEquals(200,accountRequest(browser,"POST","/api/guest/access/login",Map.of("orderNumber",other.order().orderNumber(),"phone",other.fixture().phone,"password",GUEST_PASSWORD),csrfToken(browser)).statusCode());before=returnApplicationParticipants();assertEquals(404,accountRequest(browser,"GET",list,null,null).statusCode());assertEquals(404,accountRequest(browser,"GET",view,null,null).statusCode());assertEquals(before,returnApplicationParticipants());String logoutHash=com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(((java.net.CookieManager)browser.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("GUEST_ACCESS")).findFirst().orElseThrow().getValue());assertEquals(200,accountRequest(browser,"POST","/api/guest/access/logout",Map.of(),csrfToken(browser)).statusCode());assertGasReadHasOnlySlidingCredentialEffect(before,returnApplicationParticipants(),logoutHash,true);before=returnApplicationParticipants();assertEquals(404,accountRequest(browser,"GET",list,null,null).statusCode());assertEquals(404,accountRequest(browser,"GET",view,null,null).statusCode());assertEquals(before,returnApplicationParticipants());java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"d-read-actual-gas-http.json"),authMapper.writeValueAsString(Map.of("actualBoundSource","127.0.0.6","accountLogin",false,"currentGasList",200,"currentGasDetail",200,"foreignOrAnonymousOwnerRead",404,"missingCsrfAllActorPost",403,"getAllBusinessEffects",0,"originalNetHttpType","string","actualRequestGraph",accepted.returnId())));
    }

    @Test void returnApplicationReplayCanonicalizesUnitsAndRejectsChangedContent() throws Exception {
        var f=returnFeeFixture("d-replay",false);var shipment=paidShipment(f);completeShipment(f,shipment,Instant.now());
        var selected=shipment.units().subList(0,2).stream().map(u->new com.example.marketplace.claim.api.Returns.Unit(u.orderItemId(),u.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)).toList();
        long revision=confirmations().get(f.order().orderNumber(),shipmentAuthority(f)).revision();
        var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var command=new com.example.marketplace.claim.api.Returns.Request("d-canonical",revision,selected,"원단위 접수 재생");
        var receipt=api.request(f.order().orderNumber(),shipmentAuthority(f),command);
        var state=returnApplicationParticipants();
        assertEquals(receipt,api.request(f.order().orderNumber(),shipmentAuthority(f),new com.example.marketplace.claim.api.Returns.Request(command.key(),revision,selected.reversed(),command.reason())));
        for(var changed:List.of(new com.example.marketplace.claim.api.Returns.Request(command.key(),revision,selected,"다른 접수 사유"),new com.example.marketplace.claim.api.Returns.Request(command.key(),revision+1,selected,command.reason()),new com.example.marketplace.claim.api.Returns.Request(command.key(),revision,List.of(selected.getFirst()),command.reason()))) {
            assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->api.request(f.order().orderNumber(),shipmentAuthority(f),changed)).status());
            assertEquals(state,returnApplicationParticipants());
        }
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=:id",Map.of("id",receipt.returnId()),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",receipt.returnId()),Integer.class));
    }

    @Test void returnApplicationHttpRejectsForeignAnonymousCsrfAndTrustedFlags() throws Exception {
        var f=returnFeeFixture("d-http",false);var shipment=paidShipment(f);completeShipment(f,shipment,Instant.now());
        var command=returnRequest(f,shipment,"d-http");String path="/api/orders/"+f.order().orderNumber()+"/returns";
        var unit=command.units().getFirst();var body=new java.util.HashMap<String,Object>(Map.of("key",command.key(),"expectedRevision",command.expectedRevision(),"reason",command.reason(),"units",List.of(Map.of("itemId",unit.itemId(),"ordinal",unit.ordinal(),"responsibility",unit.responsibility().name()))));
        var before=returnApplicationParticipants();
        assertEquals(403,accountRequest(f.browser(),"POST",path,body,null).statusCode());assertEquals(before,returnApplicationParticipants());
        for(String forbidden:List.of("full","confirmed","amount","restockQuantity")) {
            var altered=new java.util.HashMap<String,Object>(body);altered.put(forbidden,true);
            assertEquals(400,accountRequest(f.browser(),"POST",path,altered,csrfToken(f.browser())).statusCode());assertEquals(before,returnApplicationParticipants());
        }
        var anonymous=authBrowser();assertEquals(404,accountRequest(anonymous,"POST",path,body,csrfToken(anonymous)).statusCode());assertEquals(before,returnApplicationParticipants());
        activeMember("d-t1-foreign@example.com");var foreign=authBrowser();assertEquals(200,authRequest(foreign,"/api/auth/login",loginForm("d-t1-foreign@example.com",SIGNUP_PASSWORD),csrfToken(foreign)).statusCode());
        assertEquals(404,accountRequest(foreign,"POST",path,body,csrfToken(foreign)).statusCode());assertEquals(before,returnApplicationParticipants());
        assertEquals(202,accountRequest(f.browser(),"POST",path,body,csrfToken(f.browser())).statusCode());
    }

    @Test void returnApplicationEverySqlStageRollsBackAndResumesOnce() throws Exception {
        var f=returnFeeFixture("d-rollback",false);var shipment=paidShipment(f);completeShipment(f,shipment,Instant.now());
        var command=returnRequest(f,shipment,"d-rollback");var api=context.getBean(com.example.marketplace.claim.api.Returns.class);
        var before=returnApplicationParticipants();
        String function="CREATE FUNCTION marketplace.d_return_t1_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                +"IF TG_TABLE_NAME='job' THEN IF NEW.type<>'RETURN_RECORDED.v1' THEN RETURN NEW; END IF; END IF; "
                +"IF TG_TABLE_NAME='outbox_event' THEN IF NOT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=NEW.job_id AND j.type='RETURN_RECORDED.v1') THEN RETURN NEW; END IF; END IF; "
                +"RAISE EXCEPTION 'ACTUAL_D_T1_%',TG_TABLE_NAME USING ERRCODE='23514'; END $$";
        for(String table:List.of("order_unit_hold","order_return_application","claim_financial_intent","claim_return_request","claim_return_unit","claim_return_history","job","outbox_event")) {
            jdbc.getJdbcTemplate().execute(function);
            jdbc.getJdbcTemplate().execute("CREATE TRIGGER d_return_t1_reject BEFORE INSERT ON marketplace."+table+" FOR EACH ROW EXECUTE FUNCTION marketplace.d_return_t1_failure()");
            try {
                var failure=assertThrows(RuntimeException.class,()->api.request(f.order().orderNumber(),shipmentAuthority(f),command));
                Throwable root=failure;while(root.getCause()!=null) { root=root.getCause(); }
                assertTrue(root instanceof java.sql.SQLException,"actual SQL failure checkpoint required: "+table);
                assertTrue(root.getMessage().contains("ACTUAL_D_T1_"+table),"actual checkpoint must be reached: "+root);
                assertEquals("23514",((java.sql.SQLException)root).getSQLState());
                assertEquals(before,returnApplicationParticipants(),"every T1 participant must roll back at "+table);
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-t1-rollback-"+table+".json"),authMapper.writeValueAsString(Map.of("stage",table,"sqlState","23514","checkpointReached",true,"participantsUnchanged",true)));
            } finally {
                jdbc.getJdbcTemplate().execute("DROP TRIGGER d_return_t1_reject ON marketplace."+table);
                jdbc.getJdbcTemplate().execute("DROP FUNCTION marketplace.d_return_t1_failure()");
            }
        }
        var accepted=api.request(f.order().orderNumber(),shipmentAuthority(f),command);
        assertEquals("REQUESTED",accepted.state());assertEquals(accepted,api.request(f.order().orderNumber(),shipmentAuthority(f),command));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",accepted.returnId()),Integer.class));
    }

    @Test void acceptedGuestReturnAfterDeadlineUsesFreshCurrentGasThroughInspectionConsentAndSettlement() throws Exception {
        var guest=cancellationGuest("d-gd");var paid=new PaymentFixture(guest.order(),authBrowser(),new com.example.marketplace.payment.api.Payments.Authority(guest.fixture().browser,null,null,guest.token()));var seller=asSeller(paid,guest.order().sellers().getFirst().id());var shipment=shipments().seller(seller.order().sellers().getFirst().id(),shipmentAuthority(seller)).getFirst();completeShipment(seller,shipment,Instant.now());var clock=new JobClock();clock.now=Instant.now();guest.fixture().clock.now=clock.instant();var queries=new com.example.marketplace.order.infrastructure.JdbcOrderQueries(jdbc,orders(),guest.access(),context.getBean(com.example.marketplace.identity.api.HistorySessions.class),context.getBean(com.example.marketplace.seller.api.Stores.class),transactionManager);var holds=context.getBean(com.example.marketplace.order.api.OrderUnitHolds.class);var lifecycle=context.getBean(com.example.marketplace.order.infrastructure.AftercareLifecycle.class);var sources=new com.example.marketplace.order.infrastructure.JdbcReturnSources(jdbc,queries,orders(),holds,lifecycle);var applications=new com.example.marketplace.order.infrastructure.JdbcReturnApplications(jdbc,queries,sources,holds,lifecycle,clock);var refunds=context.getBean(com.example.marketplace.payment.api.Refunds.class);var intents=new com.example.marketplace.claim.infrastructure.JdbcFinancialIntents(jdbc,context.getBean(com.example.marketplace.order.api.CancellationOrders.class),holds,refunds,queries,sources);var outbox=context.getBean(com.example.marketplace.platform.api.Outbox.class);var commands=new com.example.marketplace.claim.infrastructure.ReturnCommands(jdbc,queries,applications,holds,outbox,transactionManager,clock,intents);var confirmation=confirmationsWith(clock,stage->{},queries);var returns=new com.example.marketplace.claim.infrastructure.JdbcReturns(queries,confirmation,applications,intents,jdbc,outbox,transactionManager,commands);String number=paid.order().orderNumber();var authority=new com.example.marketplace.order.api.OrderQueries.Authority("d-gas-postdue",null,null,guest.token());var selected=shipment.units().getFirst();var accepted=returns.request(number,authority,new com.example.marketplace.claim.api.Returns.Request("gas-before-due",confirmation.get(number,authority).revision(),List.of(new com.example.marketplace.claim.api.Returns.Unit(selected.orderItemId(),selected.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"기한 내 현재 GAS 실제 접수"));Instant due=jdbc.queryForObject("SELECT d.occurred_at+p.claim_window_seconds*interval '1 second' FROM marketplace.order_initial_delivery d JOIN marketplace.order_aftercare_policy p ON p.order_id=d.order_id WHERE d.order_id=:id",Map.of("id",paid.order().id()),java.sql.Timestamp.class).toInstant(),oldMaximum=jdbc.queryForObject("SELECT expires_at FROM marketplace.guest_access_session WHERE token_hash=:token",Map.of("token",com.example.marketplace.guest.infrastructure.JdbcGuestPurchases.hash(guest.token())),java.sql.Timestamp.class).toInstant();assertTrue(accepted.requestedAt().isBefore(due));assertTrue(oldMaximum.isBefore(due));clock.now=due.plusNanos(1000);guest.fixture().clock.now=clock.instant();assertThrows(com.example.marketplace.guest.api.GuestRejected.class,()->new TransactionTemplate(transactionManager).execute(t->guest.access().requireCurrent(guest.token())));String currentToken=guest.access().login(number,guest.fixture().phone,GUEST_PASSWORD,guest.fixture().source,null);assertFalse(currentToken.equals(guest.token()));var current=new com.example.marketplace.order.api.OrderQueries.Authority("d-gas-postdue-current",null,null,currentToken);var other=shipment.units().getLast();long revision=confirmation.get(number,current).revision();var before=returnApplicationParticipants();assertEquals(409,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->returns.request(number,current,new com.example.marketplace.claim.api.Returns.Request("gas-new-after-due",revision,List.of(new com.example.marketplace.claim.api.Returns.Unit(other.orderItemId(),other.ordinal(),com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"새 신청은 원 7일 경과"))).status());assertEquals(before,returnApplicationParticipants());var approved=returns.approve(accepted.returnId(),shipmentAuthority(seller),new com.example.marketplace.claim.api.Returns.Command("gas-after-approve",0L,"기한 내 접수의 후기한 승인"));assertEquals(accepted,approved.accepted());assertFalse(approved.history().getLast().occurredAt().isBefore(due));var collection=new com.example.marketplace.claim.infrastructure.ReturnCollectionCommands(jdbc,queries,applications,intents,commands,outbox,transactionManager,clock);collection.receive(accepted.returnId(),shipmentAuthority(seller),new com.example.marketplace.claim.api.ReturnCollections.Command("gas-after-receive",1L,List.of(new com.example.marketplace.claim.api.ReturnCollections.Unit(selected.orderItemId(),selected.ordinal(),com.example.marketplace.claim.api.ReturnCollections.Status.RECEIVED)),"후기한 실제 수령"));var inspection=new com.example.marketplace.claim.infrastructure.ReturnInspectionCommands(jdbc,queries,applications,intents,commands,orders(),reservations(),context.getBean(com.example.marketplace.inventory.api.Restitutions.class),outbox,transactionManager,clock);var inspected=inspection.inspect(accepted.returnId(),shipmentAuthority(seller),new com.example.marketplace.claim.api.ReturnInspections.Command("gas-after-inspect",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(selected.orderItemId(),selected.ordinal(),com.example.marketplace.claim.api.ReturnInspections.Condition.DEFECTIVE,com.example.marketplace.claim.api.Returns.Responsibility.SELLER)),"후기한 불량 실제 검수"));assertEquals("AWAITING_CONSENT",inspected.state());assertFalse(inspected.history().getLast().occurredAt().isBefore(due));var quoter=new com.example.marketplace.claim.infrastructure.InspectedReturnQuotes(jdbc,queries,applications,confirmation,intents,orders(),transactionManager);var fees=new com.example.marketplace.claim.infrastructure.JdbcReturnFees(jdbc,sources,queries,orders(),intents,refunds,outbox,holds,context.getBean(com.example.marketplace.platform.api.JobLeases.class),context.getBean(com.example.marketplace.claim.infrastructure.CurrentSupport.class),transactionManager,quoter);var financial=new com.example.marketplace.claim.infrastructure.JdbcReturnFinances(fees,jdbc,queries,quoter,refunds,commands,outbox,transactionManager);var quote=financial.quote(number,accepted.returnId(),current,3).feeQuote();assertEquals("0",quote.increment());before=returnApplicationParticipants();assertEquals(404,assertThrows(com.example.marketplace.order.api.OrderRejected.class,()->financial.quote(number,accepted.returnId(),authority,3)).status());assertEquals(before,returnApplicationParticipants());var consent=financial.consent(number,accepted.returnId(),current,new com.example.marketplace.claim.api.ReturnFinances.Consent("gas-after-consent",3L,quote.id(),quote.fingerprint(),true,false));runCancellationJob("REFUND_SUBMIT.v1",consent.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",consent.refundAttemptId());runCancellationJob("RETURN_FINANCE_RESUME.v1",accepted.returnId());runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1",shipment.sellerOrderId());assertEquals("COMPLETED",jdbc.queryForObject("SELECT state FROM marketplace.claim_return_request WHERE id=:id",Map.of("id",accepted.returnId()),String.class));assertEquals("REFUNDED",jdbc.queryForObject("SELECT state FROM marketplace.order_unit_hold WHERE id=:id",Map.of("id",accepted.holdId()),String.class));assertEquals("PAID",orders().find(number).state());assertEquals(1,jdbc.queryForObject("SELECT effects FROM marketplace.payment_refund_mock WHERE attempt_id=:id",Map.of("id",consent.refundAttemptId()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.inventory_return_restitution WHERE return_id=:id",Map.of("id",accepted.returnId()),Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.coupon_restoration WHERE seller_order_ref=:id",Map.of("id",shipment.sellerOrderId()),Integer.class));java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"d-gas-accepted-postdue-actual.json"),authMapper.writeValueAsString(Map.of("accepted",accepted,"originalDue",due,"originalGasMaximum",oldMaximum,"controlledBusinessAndGasClock",clock.instant(),"oldGasRejected",true,"freshGasAuthenticated",true,"newRequest409AllParticipantsUnchanged",true,"defectivePhysicalAndFinalConsentCompleted",true,"providerEffects",1,"naturalSevenDaysElapsedClaimed",false)));
    }

    @Test void returnApplicationReadsOriginalWindowAfterActualOrderLockWait() throws Exception {
        for(boolean expired:List.of(false,true)) {
            var f=returnFeeFixture("d-window-"+expired,false);var shipment=paidShipment(f);completeShipment(f,shipment,Instant.now());
            var command=returnRequest(f,shipment,"d-window");var unit=command.units().getFirst();
            var delivered=jdbc.queryForObject("SELECT d.occurred_at FROM marketplace.order_initial_delivery_unit u JOIN marketplace.order_initial_delivery d ON d.event_id=u.event_id WHERE u.item_id=:item AND u.ordinal=:ordinal",Map.of("item",unit.itemId(),"ordinal",unit.ordinal()),java.sql.Timestamp.class).toInstant();
            long seconds=jdbc.queryForObject("SELECT claim_window_seconds FROM marketplace.order_aftercare_policy WHERE order_id=:id",Map.of("id",f.order().id()),Long.class);
            var due=delivered.plusSeconds(seconds);var clock=new JobClock();clock.now=due.minusSeconds(1);var api=returnsAtClock(clock);
            var before=returnApplicationParticipants();String actual;
            try(var pool=Executors.newSingleThreadExecutor();var connection=context.getBean(DataSource.class).getConnection()) {
                connection.setAutoCommit(false);
                try(var lock=connection.prepareStatement("SELECT id FROM marketplace.purchase_order WHERE id=? FOR UPDATE")) { lock.setObject(1,f.order().id());lock.executeQuery().close(); }
                var request=pool.submit(()->{try { api.request(f.order().orderNumber(),shipmentAuthority(f),command);return "accepted"; }catch(com.example.marketplace.order.api.OrderRejected rejected) { return Integer.toString(rejected.status()); }});
                awaitCouponLock("purchase_order");clock.now=expired?due:due.minusNanos(1000);
                assertEquals(expired,!clock.instant().isBefore(due),"the controlled clock must be on the intended side of the saved microsecond deadline");
                connection.commit();actual=request.get(10,TimeUnit.SECONDS);
            }
            assertEquals(expired?"409":"accepted",actual);
            if(expired) { assertEquals(before,returnApplicationParticipants()); }
            else { assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_return_request WHERE order_id=:id",Map.of("id",f.order().id()),Integer.class)); }
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"return-window-"+expired+".json"),authMapper.writeValueAsString(Map.of("storedDeliveredAt",delivered.toString(),"storedDue",due.toString(),"controlledClockAfterActualWait",clock.instant().toString(),"actualLockObserved",true,"deadlineReached",expired,"outcome",actual)));
        }
    }

    @Test void returnEligibilityReceiptOnlyCannotCommitWithoutApplicationGraph() throws Exception {
        var fixture=returnFeeFixture("d-proof-only",false);
        var shipment=paidShipment(fixture);completeShipment(fixture,shipment,Instant.now());
        var selected=shipment.units().getFirst();
        var units=List.of(new com.example.marketplace.order.api.OrderConfirmations.Unit(selected.orderItemId(),selected.ordinal()));
        var before=returnApplicationParticipants();
        var failure=assertThrows(RuntimeException.class,()->new TransactionTemplate(transactionManager).execute(status->
                context.getBean(com.example.marketplace.order.api.ReturnApplications.class).begin(fixture.order().orderNumber(),shipmentAuthority(fixture),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),units)),
                "a trusted coordinator must not commit eligibility+hold without the matching accepted request and financial intent");
        Throwable root=failure;while(root.getCause()!=null) { root=root.getCause(); }
        assertTrue(root instanceof java.sql.SQLException,"actual database constraint rejection is required: "+root);
        assertEquals("23514",((java.sql.SQLException)root).getSQLState());
        assertEquals(before,returnApplicationParticipants(),"eligibility/hold/unit/history/outbox and every prior participant must roll back");
    }

    @Test void returnRequestAcquiresOriginalUnitsWithoutFinancialReservation() throws Exception {
        var fixture=returnFeeFixture("d-request",false);
        var shipment=paidShipment(fixture);
        completeShipment(fixture,shipment,Instant.now());
        var selected=shipment.units().getFirst();
        long revision=confirmations().get(fixture.order().orderNumber(),shipmentAuthority(fixture)).revision();
        var selectedUnits=List.of(Map.of("itemId",selected.orderItemId(),"ordinal",selected.ordinal(),"responsibility","BUYER"));
        var body=Map.of("key","d-request-1","expectedRevision",revision,"units",selectedUnits,"reason","원래 상품 반품 신청");
        var beforeStock=jdbc.queryForList("SELECT * FROM marketplace.inventory_balance ORDER BY sku_id",Map.of());
        long beforeAttempts=jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt",Map.of(),Long.class);
        long beforeAssessments=jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_fee_assessment",Map.of(),Long.class);
        String path="/api/orders/"+fixture.order().orderNumber()+"/returns";
        var response=accountRequest(fixture.browser(),"POST",path,body,csrfToken(fixture.browser()));
        assertEquals(202,response.statusCode(),"actual delivered-unit return application must be accepted: "+response.body());
        var receipt=authMapper.readTree(response.body());
        UUID returnId=UUID.fromString(receipt.get("returnId").asText());
        UUID holdId=UUID.fromString(receipt.get("holdId").asText());
        assertEquals(returnId.toString(),receipt.get("intentId").asText());
        assertEquals("REQUESTED",receipt.get("state").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_hold WHERE id=:hold AND order_id=:order AND kind='RETURN' AND state='ACTIVE'",Map.of("hold",holdId,"order",fixture.order().id()),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_financial_intent WHERE id=:id AND order_id=:order AND hold_id=:hold AND kind='RETURN'",Map.of("id",returnId,"order",fixture.order().id(),"hold",holdId),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE item_id=:item AND ordinal=:ordinal AND state='HELD' AND active_hold_id=:hold",Map.of("item",selected.orderItemId(),"ordinal",selected.ordinal(),"hold",holdId),Integer.class));
        assertEquals(beforeAttempts,jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_attempt",Map.of(),Long.class));
        assertEquals(beforeAssessments,jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_fee_assessment",Map.of(),Long.class));
        assertEquals(beforeStock,jdbc.queryForList("SELECT * FROM marketplace.inventory_balance ORDER BY sku_id",Map.of()));
        assertEquals("PAID",orders().find(fixture.order().orderNumber()).state());
        var replay=accountRequest(fixture.browser(),"POST",path,body,csrfToken(fixture.browser()));
        assertEquals(202,replay.statusCode(),replay.body());
        assertEquals(receipt,authMapper.readTree(replay.body()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_hold WHERE order_id=:order AND kind='RETURN'",Map.of("order",fixture.order().id()),Integer.class));
    }
}
