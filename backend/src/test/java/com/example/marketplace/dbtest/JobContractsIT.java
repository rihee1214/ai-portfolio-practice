package com.example.marketplace.dbtest;


import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class JobContractsIT extends PostgresContractFixture {
    @Test void jobWritesRecheckLeaseAfterRowLockWait() throws Exception {
        for(String operation:List.of("heartbeat","complete","fail")) {
            var clock=new JobClock();var jobs=jobs(clock);var type="wait-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var job=jobs.claim(java.util.Set.of(type)).orElseThrow();
            try(var lock=context.getBean(DataSource.class).getConnection()) {
                lock.setAutoCommit(false);try(var statement=lock.prepareStatement("SELECT id FROM marketplace.job WHERE id=? FOR UPDATE")) { statement.setObject(1,id);statement.executeQuery().close(); }
                var pool=Executors.newSingleThreadExecutor();
                try {
                    var result=pool.submit(()->switch(operation) { case "heartbeat" -> jobs.heartbeat(id,job.ownerToken());case "complete" -> jobs.complete(id,job.ownerToken());default -> jobs.fail(id,job.ownerToken(),false,"WAITED"); });
                    awaitJobCondition(()->jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%marketplace.job WHERE id=%'",Integer.class)>0,"owner write must actually wait for row lock");
                    clock.advance(30);lock.commit();assertFalse(result.get(10,TimeUnit.SECONDS));assertEquals("RUNNING",jobs.find(id).orElseThrow().status());assertEquals(job.ownerToken(),jobs.find(id).orElseThrow().ownerToken());
                } finally { lock.rollback();pool.shutdownNow(); }
            }
        }
    }

    @Test void workerHeartbeatProtectsLongHandler() throws Exception {
        var clock=new JobClock();var jobs=jobs(clock);String type="heartbeat-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var handler=new com.example.marketplace.platform.api.JobHandler() {
            public String type() { return type; }
            public Result handle(com.example.marketplace.platform.api.Jobs.Job job) throws Exception { entered.countDown();if(!release.await(25,TimeUnit.SECONDS)) { throw new IllegalStateException("test release absent"); }return Result.COMPLETE; }
        };
        try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) {
            worker.start();try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));clock.advance(15);
                awaitJobCondition(()->jobs.find(id).orElseThrow().leaseUntil().equals(clock.instant().plusSeconds(30)),"independent heartbeat must renew during handler");
                clock.advance(16);assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());release.countDown();awaitJobCondition(()->jobs.find(id).orElseThrow().status().equals("SUCCEEDED"),"long handler should complete with renewed lease");
            } finally { release.countDown(); }
        }
    }

    @Test void outboxConcurrentDedupeAndAtomicInsertFailure() throws Exception {
        var clock=new JobClock();var jobs=jobs(clock);var outbox=new com.example.marketplace.platform.infrastructure.JdbcOutbox(jdbc,transactionManager,jobs);var command=jobCommand("dedupe",clock.instant());var event=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),command);var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(0,1).stream().map(index->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return outbox.append(event);})).toList();assertEquals(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",command.dedupeKey()),Integer.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",event.id()),Integer.class));
        var conflict=jobCommand("conflict",clock.instant());var changed=new com.example.marketplace.platform.api.Jobs.Command(conflict.type(),conflict.dedupeKey(),conflict.causeKey(),"different",conflict.nextRunAt());var simultaneous=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(conflict,changed).stream().map(c->pool.submit(()->{simultaneous.await(10,TimeUnit.SECONDS);try { return jobs.enqueue(c); }catch(IllegalArgumentException rejected) { assertEquals("JOB_KEY_CONFLICT",rejected.getMessage());return null; }})).toList();
            int winners=0;for(var future:futures) { if(future.get(10,TimeUnit.SECONDS)!=null) { winners++; } }assertEquals(1,winners);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",conflict.dedupeKey()),Integer.class));
        var invisible=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("visibility",clock.instant()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            var id=outbox.append(invisible);
            try(var other=context.getBean(DataSource.class).getConnection();var query=other.prepareStatement("SELECT count(*) FROM marketplace.job WHERE id=?")) { query.setObject(1,id);try(var rs=query.executeQuery()) { rs.next();assertEquals(0,rs.getInt(1)); } }catch(java.sql.SQLException ex) { throw new IllegalStateException(ex); }
        });
        var broken=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("injected",clock.instant()));
        jdbc.getJdbcTemplate().execute("CREATE FUNCTION marketplace.reject_probe_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+broken.id()+"'::uuid THEN RAISE EXCEPTION 'test only insertion failure'; END IF; RETURN NEW; END $$");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER probe_outbox_failure BEFORE INSERT ON marketplace.outbox_event FOR EACH ROW EXECUTE FUNCTION marketplace.reject_probe_outbox()");
        assertThrows(org.springframework.dao.DataAccessException.class,()->outbox.append(broken));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",broken.command().dedupeKey()),Integer.class));
        UUID id=jobs.enqueue(jobCommand("constraints",clock.instant()));
        for(String update:List.of("attempts=6","status='RUNNING'","owner_token='"+UUID.randomUUID()+"'","status='UNKNOWN'")) { assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE marketplace.job SET "+update+" WHERE id=:id",Map.of("id",id))); }
    }

    @Test void workerFailureResultsAreDurableAndSanitized() {
        var clock=new JobClock();var jobs=jobs(clock);
        for(String mode:List.of("RETRY","TERMINAL","EXCEPTION")) {
            String type="result-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var handler=new com.example.marketplace.platform.api.JobHandler() {
                public String type() { return type; }
                public Result handle(com.example.marketplace.platform.api.Jobs.Job job) { if(mode.equals("EXCEPTION")) { throw new IllegalArgumentException("private provider credentials"); }return Result.valueOf(mode); }
            };
            try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) { assertTrue(worker.runOnce());assertEquals(mode.equals("TERMINAL")?"FAILED":"READY",jobs.find(id).orElseThrow().status());assertEquals("HANDLER_"+mode,jobs.find(id).orElseThrow().lastError());assertFalse(worker.runOnce());assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->worker.runOnce())); }
        }
    }

    @Test void workerDispatchesOutsideTransactionsAndRejectsDuplicateTypes() {
        var clock=new JobClock();var jobs=jobs(clock);var type="dispatch-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant()));var calls=new java.util.concurrent.atomic.AtomicInteger();
        var handler=new com.example.marketplace.platform.api.JobHandler() {
            public String type() { return type; }
            public Result handle(com.example.marketplace.platform.api.Jobs.Job job) {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals("RUNNING",jobs.find(id).orElseThrow().status());assertEquals(id,job.id());assertEquals(1,calls.incrementAndGet());return Result.COMPLETE;
            }
        };
        try(var worker=new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler))) { assertTrue(worker.runOnce());assertFalse(worker.runOnce()); }
        assertEquals("SUCCEEDED",jobs.find(id).orElseThrow().status());assertEquals(1,calls.get());
        assertThrows(IllegalArgumentException.class,()->new com.example.marketplace.platform.infrastructure.WorkerEngine(jobs,List.of(handler,handler)));
        assertTrue(context.getBeansOfType(com.example.marketplace.platform.infrastructure.WorkerEngine.class).isEmpty());assertEquals(java.util.Set.of("NOTIFICATION_DELIVER.v1","ORDER_EXPIRY","PAYMENT_QUERY","PAYMENT_COMPENSATE","PAYMENT_SETTLED","FULFILLMENT_PREPARE","FULFILLMENT_BACKFILL","SHIPMENT_CREATED","SHIPMENT_PROGRESS","INITIAL_DELIVERY_SCAN","INITIAL_DELIVERY_MIGRATE","ORDER_CONFIRM_DUE","ORDER_CONFIRM_REEVALUATE","ORDER_CONFIRM_SCAN","ORDER_CONFIRMATION_RECORDED","ORDER_UNIT_LIFECYCLE_RECORDED","REFUND_SUBMIT.v1","REFUND_QUERY_DUE.v1","REFUND_RESULT_RECORDED.v1","CLAIM_RECORDED.v1","RETURN_RECORDED.v1","EXCHANGE_RECORDED.v1","RETURN_FINANCE_RESUME.v1","SELLER_AFTERCARE_REEVALUATE.v1","RETURN_RECOVERY_SCAN.v1","ORDER_PHYSICAL_CONFIRM_DUE.v1","EXCHANGE_RESHIP.v1","EXCHANGE_SHIPMENT_PROGRESS.v1","EXCHANGE_DELIVERED.v1","EXCHANGE_RESERVATION_DUE.v1","EXCHANGE_REFUND_RESUME.v1","EXCHANGE_REFUND_RECOVERY_SCAN.v1"),context.getBeansOfType(com.example.marketplace.platform.api.JobHandler.class).values().stream().map(com.example.marketplace.platform.api.JobHandler::type).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void outboxJoinsBusinessTransaction() {
        var clock=new JobClock();var jobs=jobs(clock);var outbox=new com.example.marketplace.platform.infrastructure.JdbcOutbox(jdbc,transactionManager,jobs);
        var event=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("transaction",clock.instant()));
        UUID id=outbox.append(event);assertThat(id).isNotNull();assertEquals(id,outbox.append(event));assertEquals(id,jobs.enqueue(event.command()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE id=:id",Map.of("id",id),Integer.class));
        var other=new com.example.marketplace.platform.api.Jobs.Command("changed",event.command().dedupeKey(),event.command().causeKey(),"{}",clock.instant());
        assertThrows(IllegalArgumentException.class,()->jobs.enqueue(other));
        assertThrows(IllegalArgumentException.class,()->outbox.append(new com.example.marketplace.platform.api.Outbox.Event(event.id(),jobCommand("different",clock.instant()))));
        var rollback=new com.example.marketplace.platform.api.Outbox.Event(UUID.randomUUID(),jobCommand("rollback",clock.instant()));var draft=orderDraft("outbox-rollback");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{orders().save(draft);outbox.append(rollback);throw new IllegalStateException("rollback");}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",rollback.id()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",rollback.command().dedupeKey()),Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",draft.purchaseSessionId()),Integer.class));
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{orders().save(draft);outbox.append(rollback);});
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.outbox_event WHERE id=:id",Map.of("id",rollback.id()),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.job WHERE dedupe_key=:key",Map.of("key",rollback.command().dedupeKey()),Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM marketplace.order_checkout_attempt WHERE purchase_session_id=:id",Map.of("id",draft.purchaseSessionId()),Integer.class));
    }

    @Test void jobLeaseFencesStaleWorkers() throws Exception {
        var clock=new JobClock();String type="lease-"+UUID.randomUUID();var first=jobs(clock);var second=jobs(clock);UUID id=first.enqueue(jobCommand(type,clock.instant()));assertThat(id).isNotNull();var barrier=new java.util.concurrent.CyclicBarrier(2);
        com.example.marketplace.platform.api.Jobs.Job acquired;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=List.of(first,second).stream().map(api->pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return api.claim(java.util.Set.of(type));})).toList();
            var results=List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(java.util.Optional::isPresent).count());acquired=results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
        }
        assertEquals(1,acquired.attempts());clock.advance(29);assertTrue(second.claim(java.util.Set.of(type)).isEmpty());assertTrue(first.heartbeat(id,acquired.ownerToken()));assertEquals(clock.instant().plusSeconds(30),first.find(id).orElseThrow().leaseUntil());
        clock.advance(29);assertTrue(second.claim(java.util.Set.of(type)).isEmpty());clock.advance(1);assertFalse(first.heartbeat(id,acquired.ownerToken()));assertFalse(first.complete(id,acquired.ownerToken()));assertFalse(first.fail(id,acquired.ownerToken(),true,"STALE"));
        var replacement=second.claim(java.util.Set.of(type)).orElseThrow();assertThat(replacement.ownerToken()).isNotEqualTo(acquired.ownerToken());assertEquals(2,replacement.attempts());
        assertFalse(first.complete(id,acquired.ownerToken()));assertFalse(first.heartbeat(id,acquired.ownerToken()));assertFalse(first.fail(id,acquired.ownerToken(),true,"STALE"));assertTrue(second.complete(id,replacement.ownerToken()));assertEquals("SUCCEEDED",second.find(id).orElseThrow().status());assertFalse(second.complete(id,replacement.ownerToken()));
    }

    @Test void jobRetryAndDueBoundaries() {
        var clock=new JobClock();var jobs=jobs(clock);String type="retry-"+UUID.randomUUID();var id=jobs.enqueue(jobCommand(type,clock.instant().plusSeconds(1)));assertThat(id).isNotNull();assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());clock.advance(1);assertTrue(jobs.claim(java.util.Set.of("unsupported")).isEmpty());
        long[] delays={5,15,60,300};for(int attempt=1;attempt<=5;attempt++) {
            var job=jobs.claim(java.util.Set.of(type)).orElseThrow();assertEquals(attempt,job.attempts());assertTrue(jobs.fail(id,job.ownerToken(),false,"TRANSIENT"));
            if(attempt<5) { assertEquals(clock.instant().plusSeconds(delays[attempt-1]),jobs.find(id).orElseThrow().nextRunAt());clock.advance(delays[attempt-1]-1);assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());clock.advance(1); }
        }
        assertEquals("FAILED",jobs.find(id).orElseThrow().status());assertTrue(jobs.failed(100).stream().anyMatch(j->j.id().equals(id)&&j.attempts()==5&&"TRANSIENT".equals(j.lastError())));clock.advance(10000);assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());
        var permanent=jobs.enqueue(jobCommand(type,clock.instant()));var p=jobs.claim(java.util.Set.of(type)).orElseThrow();assertTrue(jobs.fail(permanent,p.ownerToken(),true,"INVALID"));assertEquals(1,jobs.find(permanent).orElseThrow().attempts());
        var crashes=jobs.enqueue(jobCommand(type,clock.instant()));for(int attempt=1;attempt<=5;attempt++) { assertEquals(attempt,jobs.claim(java.util.Set.of(type)).orElseThrow().attempts());clock.advance(30); }
        assertTrue(jobs.claim(java.util.Set.of(type)).isEmpty());assertEquals("FAILED",jobs.find(crashes).orElseThrow().status());assertEquals("LEASE_EXPIRED",jobs.find(crashes).orElseThrow().lastError());
    }
}
