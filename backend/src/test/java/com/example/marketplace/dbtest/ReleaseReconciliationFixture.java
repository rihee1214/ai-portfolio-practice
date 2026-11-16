package com.example.marketplace.dbtest;

import com.example.marketplace.MarketplaceApplication;
import com.example.marketplace.catalog.api.Categories;
import com.example.marketplace.catalog.api.Products;
import com.example.marketplace.catalog.api.Skus;
import com.example.marketplace.claim.api.ReturnFinances;
import com.example.marketplace.claim.infrastructure.SupportRefunds;
import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import com.example.marketplace.identity.infrastructure.LocalPasswordResetMailbox;
import com.example.marketplace.identity.infrastructure.LocalTestMailbox;
import com.example.marketplace.inventory.api.Inventory;
import com.example.marketplace.operations.api.SupportAssignments;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.infrastructure.JdbcJobs;
import com.example.marketplace.platform.infrastructure.WorkerEngine;
import com.example.marketplace.seller.api.Stores;
import com.example.marketplace.settlement.api.Settlements;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual command-produced P09/P11 original money, retained as source/restore UI consumers.
 * Only account authorization, DEMO fault settings and the approved synthetic P11 delivery/
 * confirmation clocks are fixture inputs. No financial, inventory, claim or job final state
 * is seeded by SQL. The source media writer is closed before an operational JAR can start. */
final class ReleaseReconciliationFixture extends SettlementContractFixture {
    private final List<String> createdAccounts = new ArrayList<>();
    private final List<Map<String,Object>> producerCommands = new ArrayList<>();
    private final ThreadLocal<Long> commandDeadline = new ThreadLocal<>();
    private java.util.function.Consumer<Map<String,Object>> commandObserver = event -> {};
    private Path producerEvidence;
    private UUID operator;

    static Map<String,Object> prepare(String jdbcUrl,String username,String password,Path media,
            Map<String,String> passwords,Path producerEvidencePath) throws Exception {
        return prepare(jdbcUrl,username,password,media,passwords,producerEvidencePath,configured -> {});
    }
    static Map<String,Object> prepare(String jdbcUrl,String username,String password,Path media,
            Map<String,String> passwords,Path producerEvidencePath,
            java.util.function.Consumer<ConfigurableApplicationContext> audit) throws Exception {
        return prepare(jdbcUrl,username,password,media,passwords,producerEvidencePath,audit,event -> {});
    }
    static Map<String,Object> prepare(String jdbcUrl,String username,String password,Path media,
            Map<String,String> passwords,Path producerEvidencePath,
            java.util.function.Consumer<ConfigurableApplicationContext> audit,
            java.util.function.Consumer<Map<String,Object>> commands) throws Exception {
        var fixture = new ReleaseReconciliationFixture();
        fixture.commandObserver = commands;
        fixture.producerEvidence = producerEvidencePath;
        var app = new SpringApplication(MarketplaceApplication.class);
        app.setDefaultProperties(Map.of("spring.datasource.url",jdbcUrl,"spring.datasource.username",username,
                "spring.datasource.password",password,"DB_URL",jdbcUrl,"DB_USERNAME",username,"DB_PASSWORD",password,
                "MEDIA_ROOT",media.toString(),"marketplace.media.root",media.toString()));
        Map<String,Object> result;
        try (var configured = app.run("--spring.profiles.active=db,local-http,local-mailbox", "--server.port=0",
                "--server.address=127.0.0.1", "--marketplace.phone.mode=disabled", "--marketplace.runtime.mode=local-full",
                "--marketplace.worker.enabled=false", "--spring.flyway.locations=classpath:db/migration")) {
            fixture.initialize(configured);
            audit.accept(configured);
            result = fixture.produce();
            for (String email : fixture.createdAccounts) {
                fixture.business("rotate individual fixture credential", () -> {
                    String individual = " P12D-reconciliation-" + UUID.randomUUID() + "-individual! ";
                    var recovery = configured.getBean(PasswordRecoveryOperations.class);
                    recovery.request(email);
                    var inbox = configured.getBean(LocalPasswordResetMailbox.class);
                    String token = inbox.messages().stream().filter(row -> row.recipient().equals(email))
                            .reduce((a,b) -> b).orElseThrow().token();
                    recovery.reset(token,individual); passwords.put(email,individual);
                    return email;
                });
            }
        }
        Files.writeString(producerEvidencePath,fixture.authMapper.writeValueAsString(Map.of(
                "sourceWriterClosed",true,"producerPorts",fixture.producerCommands,
                "accountInputs","approved individual verified accounts and SELLER grants",
                "timeInputs","approved P11 synthetic prior-Sunday delivery/confirmation; next-week settlement clock; persisted job next_run_at",
                "actualElapsedWeeks",false,"directSqlFinalBusinessState",false,"expected",result)),StandardOpenOption.CREATE_NEW);
        return result;
    }

    private void initialize(ConfigurableApplicationContext configured) {
        context=configured; jdbc=configured.getBean(NamedParameterJdbcTemplate.class);
        source=configured.getBean(DataSource.class); transactionManager=configured.getBean(PlatformTransactionManager.class);
        identityStore=configured.getBean(IdentityStore.class); passwordHasher=configured.getBean(PasswordHasher.class);
        mailbox=configured.getBean(LocalTestMailbox.class); signup=configured.getBean(SignupOperations.class);
        port=configured.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
        operator=jdbc.queryForObject("SELECT id FROM marketplace.identity_user WHERE email='p12d-desktop-admin@example.com'",Map.of(),UUID.class);
    }

    /** Override the old low-level catalog fixture: all catalog/stock writes use real commands. */
    @Override protected Map<String,Object> browseFixture(String suffix,long price,long stock) {
        try {
            return business("Categories/Stores/Products/Skus/Inventory create " + suffix, () -> {
                UUID owner=activeMember("p12d-recon-"+suffix+"@example.com");
                new TransactionTemplate(transactionManager).executeWithoutResult(t -> context.getBean(SellerMembership.class).grant(operator,owner));
                var category=context.getBean(Categories.class).save(operator,null,new Categories.Input("복구 대사 "+suffix,true,0L));
                context.getBean(Stores.class).save(owner,null,new Stores.Input("p12d-"+suffix,"복구 대사 매대","합성 준비","합성 주소","","","",true,0L));
                var products=context.getBean(Products.class);
                var product=products.save(owner,null,new Products.Input(category.id(),"p12d-"+suffix,"복구 대사 상품","합성 준비","DRAFT",0L,"3000"));
                var skus=context.getBean(Skus.class);
                var sku=skus.list(owner,product.id(),0).items().getFirst();
                skus.save(owner,product.id(),sku.id(),new Skus.Input(List.of(),price,true,sku.version()));
                var balance=context.getBean(Inventory.class).list(owner,product.id(),false,0).items().getFirst();
                context.getBean(Inventory.class).adjust(owner,product.id(),sku.id(),false,new Inventory.Change(stock,balance.version(),"대사 실제 재고 입고",UUID.randomUUID()));
                product=products.save(owner,product.id(),new Products.Input(category.id(),product.slug(),product.title(),product.description(),"ACTIVE",product.version(),"3000"));
                return Map.of("owner",owner,"product",product,"sku",sku.id());
            });
        } catch (Exception failure) { throw new IllegalStateException("RELEASE_RECONCILIATION_CATALOG_PRODUCER",failure); }
    }
    @Override protected UUID activeMember(String email) {
        UUID id=super.activeMember(email); createdAccounts.add(email); return id;
    }

    private Map<String,Object> produce() throws Exception {
        var expectedOrders=new ArrayList<Map<String,Object>>();
        var partial=business("approved P09 coupon original-unit order", () -> cancellationMoneyFixture("p12d-partial",false,false));
        var partialShipment=business("actual payment and shipment producer", () -> paidShipment(partial));
        var partialUnit=partialShipment.units().stream().filter(unit -> unit.ordinal()==2).findFirst().orElseThrow();
        var partialReceipt=business("actual partial cancellation command", () -> acceptCancellation(partial,List.of(Map.of("itemId",partialUnit.orderItemId(),"ordinal",2)),"p12d-partial"));
        UUID partialAttempt=UUID.fromString(partialReceipt.path("refundAttemptId").asText());
        business("actual partial refund worker effect", () -> {runCancellationJob("REFUND_SUBMIT.v1",partialAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",partialAttempt);return partialAttempt;});
        expectedOrders.add(orderExpected(partial,"PAID",33001,10001,List.of(10000L,10000L,10001L),List.of(refund(partialAttempt,"SUCCEEDED",10001))));

        var parties=business("actual two-unit return-fee parties", () -> returnParties("p12d-fee",true,2,"3000",0));
        var physical=business("actual return request approval and collection", () -> physicalReturn(parties,1,true));
        var returned=business("actual buyer-responsibility inspection quote and consent", () -> {
            var unit=physical.accepted().units().getFirst();
            context.getBean(com.example.marketplace.claim.api.ReturnInspections.class).inspect(physical.accepted().returnId(),shipmentAuthority(parties.seller()),
                    new com.example.marketplace.claim.api.ReturnInspections.Command("p12d-inspect",2L,List.of(new com.example.marketplace.claim.api.ReturnInspections.Unit(unit.itemId(),unit.ordinal(),
                    com.example.marketplace.claim.api.ReturnInspections.Condition.SALEABLE,com.example.marketplace.claim.api.Returns.Responsibility.BUYER)),"실제 수령 상품 구매자 책임 검수"));
            var api=context.getBean(ReturnFinances.class);var quote=api.quote(parties.buyer().order().orderNumber(),physical.accepted().returnId(),shipmentAuthority(parties.buyer()),3).feeQuote();
            assertEquals("3000",quote.increment());assertEquals("7000",quote.refund());
            return api.consent(parties.buyer().order().orderNumber(),physical.accepted().returnId(),shipmentAuthority(parties.buyer()),new ReturnFinances.Consent("p12d-consent",3L,quote.id(),quote.fingerprint(),true,false));
        });
        business("actual return refund restock and financial completion", () -> {runCancellationJob("REFUND_SUBMIT.v1",returned.refundAttemptId());runCancellationJob("REFUND_RESULT_RECORDED.v1",returned.refundAttemptId());runCancellationJob("RETURN_FINANCE_RESUME.v1",physical.accepted().returnId());return returned.refundAttemptId();});
        expectedOrders.add(orderExpected(parties.buyer(),"PAID",23000,10000,List.of(10000L,10000L),List.of(refund(returned.refundAttemptId(),"SUCCEEDED",7000))));

        var unknown=business("actual original amount for UNKNOWN refund", () -> cancellationMoneyFixture("p12d-unknown",false,false));
        var unknownShipment=business("actual UNKNOWN order payment and fulfillment", () -> paidShipment(unknown));
        var unknownUnit=unknownShipment.units().stream().filter(unit -> unit.ordinal()==2).findFirst().orElseThrow();
        var unknownReceipt=business("actual cancellation awaiting provider outcome", () -> acceptCancellation(unknown,List.of(Map.of("itemId",unknownUnit.orderItemId(),"ordinal",2)),"p12d-unknown"));
        UUID unknownAttempt=UUID.fromString(unknownReceipt.path("refundAttemptId").asText()),unknownClaim=UUID.fromString(unknownReceipt.path("claimId").asText());
        // Provider fault input only. The real submit/result handlers create UNKNOWN and reserve money.
        jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','UNKNOWN')",Map.of("id",unknownClaim));
        business("actual UNKNOWN provider submit and reservation", () -> {runCancellationJob("REFUND_SUBMIT.v1",unknownAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",unknownAttempt);return unknownAttempt;});
        // Approved P09 time preparation consumes each actual persisted automatic query slot.
        // UNKNOWN remains reserved for operator review; no live worker can race a due query
        // into the checkpoint comparison during this local rehearsal.
        for(int query=1;query<=5;query++) {
            business("actual UNKNOWN automatic query at persisted slot "+query, () -> {
                runScheduledRefundQuery(unknownAttempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",unknownAttempt);return unknownAttempt;
            });
        }
        expectedOrders.add(orderExpected(unknown,"PAID",33001,10001,List.of(10000L,10000L,10001L),List.of(refund(unknownAttempt,"UNKNOWN",10001))));

        var confirmed=business("approved P11 original two equal units", () -> twoEqualUnits("p12d-confirmed"));
        var confirmedShipment=business("actual confirmed order payment and shipment", () -> paidShipment(confirmed));
        var currentWeek=com.example.marketplace.settlement.domain.SettlementRules.week(Instant.now());
        Instant boundary=com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek),priorSunday=boundary.minusSeconds(60);
        business("approved P11 synthetic delivery and confirmation clocks", () -> {
            completeShipment(confirmed,confirmedShipment,priorSunday.minusSeconds(60));
            var api=confirmationsWith(Clock.fixed(priorSunday,ZoneOffset.UTC),stage -> {});
            var view=api.get(confirmed.order().orderNumber(),shipmentAuthority(confirmed));
            return api.confirm(confirmed.order().orderNumber(),shipmentAuthority(confirmed),new OrderConfirmations.Command("p12d-confirmed",view.revision(),view.units().stream().map(OrderConfirmations.Eligibility::unit).toList()));
        });
        var settlementOperator=business("approved dedicated settlement operator account", () -> supportFixture("p12d-recon","SETTLEMENTS"));
        var actor=new Settlements.Actor(settlementOperator.authority().member(),settlementOperator.authority().browser(),settlementOperator.authority().version());
        var settlements=context.getBean(Settlements.class);
        var prior=business("actual prior-week original mock payout", () -> settle(settlements,actor,currentWeek.minusWeeks(1)));
        var originalPayout=jdbc.queryForList("SELECT * FROM marketplace.settlement_mock_payment WHERE batch_id=:id",Map.of("id",prior.id()));
        var originalBalance=prior.balances().stream().filter(balance -> balance.storeId().equals(confirmed.order().sellers().getFirst().storeId())).findFirst().orElseThrow();
        assertEquals("22037",originalBalance.net());assertEquals("22037",originalBalance.payout());
        var support=business("approved assigned SUPPORT operator account", () -> supportFixture("p12d-confirmed","SUPPORT"));
        var opened=business("actual confirmed-order support case", () -> accountRequest(confirmed.browser(),"POST","/api/support/cases",Map.of("key",UUID.randomUUID(),"orderNumber",confirmed.order().orderNumber(),"sellerOrderId",confirmedShipment.sellerOrderId(),"body","확정 뒤 수령 상품의 실제 하자 문의"),csrfToken(confirmed.browser())));
        assertEquals(200,opened.statusCode());var ticket=authMapper.readTree(opened.body());UUID caseId=UUID.fromString(ticket.path("id").asText());
        var assignee=new SupportAssignments.Actor(support.authority().member(),support.authority().browser(),support.authority().version());
        var assignment=context.getBean(SupportAssignments.class);
        business("actual support case assignment", () -> {var entry=assignment.queue(assignee).stream().filter(row -> row.targetId().equals(caseId)).findFirst().orElseThrow();return assignment.assign(caseId,assignee,new SupportAssignments.Change(UUID.randomUUID(),entry.version(),assignee.member()));});
        var confirmedRefunds=new ArrayList<Map<String,Object>>();
        for (int ordinal : List.of(0,1)) {
            int targetOrdinal=ordinal;
            UUID attempt=business("actual collected inspected approved confirmed-unit refund "+ordinal, () -> {
                var unit=confirmedShipment.units().stream().filter(row -> row.ordinal()==targetOrdinal).findFirst().orElseThrow();
                var api=context.getBean(SupportRefunds.class);UUID collection=UUID.randomUUID(),inspection=UUID.randomUUID(),approval=UUID.randomUUID();long version=ticket.path("version").asLong();
                api.command(caseId,SupportRefunds.Action.COLLECT,new SupportRefunds.Command(collection,version,unit.orderItemId(),targetOrdinal,null,"복구 검증 수령",null,null),support.authority());
                api.command(caseId,SupportRefunds.Action.INSPECT,new SupportRefunds.Command(inspection,version,unit.orderItemId(),targetOrdinal,collection,"복구 검증 검수","SELLER","실제 회수품 결함 확인"),support.authority());
                api.command(caseId,SupportRefunds.Action.APPROVE,new SupportRefunds.Command(approval,version,unit.orderItemId(),targetOrdinal,inspection,null,null,"검수 기록에 따른 원단위 승인"),support.authority());
                var execution=api.command(caseId,SupportRefunds.Action.EXECUTE,new SupportRefunds.Command(UUID.randomUUID(),version,unit.orderItemId(),targetOrdinal,approval,null,null,null),support.authority());
                UUID id=execution.stream().filter(row -> row.ordinal()==targetOrdinal).findFirst().orElseThrow().attempt();
                runCancellationJob("REFUND_SUBMIT.v1",id);runCancellationJob("REFUND_RESULT_RECORDED.v1",id);return id;
            });
            confirmedRefunds.add(refund(attempt,"SUCCEEDED",10019));
        }
        UUID shipping=jdbc.queryForObject("SELECT refund_attempt_id FROM marketplace.claim_shipping_obligation WHERE order_id=:id",Map.of("id",confirmed.order().id()),UUID.class);
        business("actual original shipping refund", () -> {runCancellationJob("REFUND_SUBMIT.v1",shipping);runCancellationJob("REFUND_RESULT_RECORDED.v1",shipping);return shipping;});
        confirmedRefunds.add(refund(shipping,"SUCCEEDED",3000));
        assertEquals(originalPayout,jdbc.queryForList("SELECT * FROM marketplace.settlement_mock_payment WHERE batch_id=:id",Map.of("id",prior.id())));
        Object target=AopTestUtils.getUltimateTargetObject(settlements),oldClock=ReflectionTestUtils.getField(target,"clock");
        Settlements.Batch adjustment;
        try {
            ReflectionTestUtils.setField(target,"clock",Clock.fixed(com.example.marketplace.settlement.domain.SettlementRules.start(currentWeek.plusWeeks(1)),ZoneOffset.UTC));
            adjustment=business("actual negative adjustment mock payout and carryover", () -> settle(settlements,actor,currentWeek));
        } finally { ReflectionTestUtils.setField(target,"clock",oldClock); }
        var adjustmentBalance=adjustment.balances().stream().filter(balance -> balance.storeId().equals(originalBalance.storeId())).findFirst().orElseThrow();
        assertEquals("-22037",adjustmentBalance.net());assertEquals("0",adjustmentBalance.payout());assertEquals("22037",adjustmentBalance.closing());
        expectedOrders.add(orderExpected(confirmed,"CLOSED",23038,10019,List.of(10019L,10019L),confirmedRefunds));
        String sellerEmail=email(confirmed.authority().member());
        var statements=List.of(statement(prior,originalBalance,sellerEmail,confirmedShipment.sellerOrderId()),statement(adjustment,adjustmentBalance,sellerEmail,confirmedShipment.sellerOrderId()));
        var failed=failActualNotification(partial);
        var result=new LinkedHashMap<String,Object>();result.put("expectationSource","approved-p09-p11-independent-calculation");
        result.put("producerEvidencePath",producerEvidence.toAbsolutePath().toString());
        result.put("scenarios",List.of("MEMBER","TWO_SELLERS","PARTIAL_REFUND","RETURN_FEE","CONFIRMED_EXCEPTION_REFUND","NEGATIVE_CARRYOVER","UNKNOWN_REFUND","FAILED_JOB"));
        result.put("orders",expectedOrders);result.put("settlements",statements);result.put("failedJobs",List.of(failed));
        return result;
    }

    private Settlements.Batch settle(Settlements api,Settlements.Actor actor,java.time.LocalDate week) {
        var draft=api.open(new Settlements.Open(UUID.randomUUID(),week),actor);
        var reviewed=api.review(draft.id(),new Settlements.Change(UUID.randomUUID(),draft.version()),actor);
        return api.simulatePay(reviewed.id(),new Settlements.Change(UUID.randomUUID(),reviewed.version()),actor);
    }
    private Map<String,Object> statement(Settlements.Batch batch,Settlements.StoreBalance balance,String email,UUID sellerOrder) {
        return Map.of("sellerEmail",email,"sellerOrderId",sellerOrder.toString(),"storeId",balance.storeId().toString(),
                "batchId",batch.id().toString(),"weekStart",batch.weekStart().toString(),"net",balance.net(),"payout",balance.payout(),"opening",balance.opening(),"closing",balance.closing());
    }
    private String email(UUID member) {return jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",Map.of("id",member),String.class);}
    private Map<String,Object> refund(UUID id,String state,long amount) {
        assertEquals(state,jdbc.queryForObject("SELECT state FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",id),String.class));
        assertEquals(amount,jdbc.queryForObject("SELECT amount FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",id),Long.class));
        return Map.of("attemptId",id.toString(),"state",state,"amount",Long.toString(amount));
    }
    private Map<String,Object> orderExpected(PaymentFixture purchase,String state,long total,long gross,List<Long> net,List<Map<String,Object>> refunds) {
        var detail=context.getBean(OrderQueries.class).detail(purchase.order().orderNumber(),shipmentAuthority(purchase));
        assertEquals(state,detail.state());assertEquals(Long.toString(total),detail.total());
        assertEquals(1,detail.stores().size());assertEquals(1,detail.stores().getFirst().items().size());
        var item=detail.stores().getFirst().items().getFirst();var units=new ArrayList<Map<String,Object>>();
        assertEquals(net.size(),item.units().size());
        for (int ordinal=0;ordinal<net.size();ordinal++) {
            var expected=Map.<String,Object>of("itemId",item.id().toString(),"ordinal",ordinal,"gross",Long.toString(gross),"promotionDiscount","0",
                    "couponDiscount",Long.toString(gross-net.get(ordinal)),"net",Long.toString(net.get(ordinal)));
            assertEquals(Long.toString(net.get(ordinal)),item.units().get(ordinal).net());units.add(expected);
        }
        return Map.of("orderNumber",detail.orderNumber(),"buyerEmail",email(purchase.authority().member()),"state",state,"total",Long.toString(total),
                "paymentState","SUCCEEDED","units",units,"refunds",refunds);
    }
    private Map<String,Object> failActualNotification(PaymentFixture sourcePurchase) throws Exception {
        UUID id=jdbc.queryForObject("SELECT j.id FROM marketplace.job j JOIN marketplace.notification_event e ON j.cause_key=e.id::text WHERE j.type='NOTIFICATION_DELIVER.v1' AND e.target_id=:order ORDER BY j.id LIMIT 1",Map.of("order",sourcePurchase.order().id()),UUID.class);
        var actual=context.getBean(Jobs.class);
        var unavailable=new JobHandler() {public String type(){return "NOTIFICATION_DELIVER.v1";}public Result handle(Jobs.Job job){throw new IllegalStateException("P12D_TEST_NOTIFICATION_CHANNEL_UNAVAILABLE");}};
        for (int attempt=1;attempt<=5;attempt++) {
            business("actual notification worker injected transport failure attempt "+attempt, () -> {
                var before=actual.find(id).orElseThrow();
                var jobs=new JdbcJobs(new OneJobJdbc(source,id),transactionManager,Clock.fixed(before.nextRunAt().plusSeconds(1),ZoneOffset.UTC));
                try(var worker=new WorkerEngine(jobs,List.of(unavailable))) {assertTrue(worker.runOnce());}
                return jobs.find(id).orElseThrow();
            });
        }
        var failed=actual.find(id).orElseThrow();assertEquals("FAILED",failed.status());assertEquals(5,failed.attempts());
        return Map.of("id",id.toString(),"type",failed.command().type(),"status","FAILED","attempts",5,"error",failed.lastError());
    }
    private <T> T business(String label,Callable<T> work) throws Exception {
        long started=System.nanoTime(),wall=System.currentTimeMillis();
        Long parent=commandDeadline.get();
        if(parent!=null) {
            if(started>=parent) { throw new IllegalStateException("Shared release producer business deadline exceeded: "+label); }
            T result=work.call();
            if(System.nanoTime()>=parent) { throw new IllegalStateException("Shared release producer business deadline exceeded: "+label); }
            return result;
        }
        var executor=Executors.newSingleThreadExecutor();String status="failed";
        String commandId=UUID.randomUUID().toString();
        var progress=Map.<String,Object>of("id",commandId,"kind","operation","name",label,"startedAt",wall,"deadlineAt",wall+10000,"pid",ProcessHandle.current().pid());
        var beginning=new LinkedHashMap<String,Object>(progress);beginning.put("event","start");commandObserver.accept(beginning);
        // The initiating command owns the entire budget, including inherited catalog helpers.
        var future=executor.submit(() -> {commandDeadline.set(started+TimeUnit.SECONDS.toNanos(10));try{return work.call();}finally{commandDeadline.remove();}});
        try {T result=future.get(Math.max(1,started+TimeUnit.SECONDS.toNanos(10)-System.nanoTime()),TimeUnit.NANOSECONDS);status="passed";return result;}
        finally {
            if(!future.isDone()) { future.cancel(true); }executor.shutdownNow();
            var record=Map.<String,Object>of("name",label,"kind","business-command","startedAt",wall,"deadlineAt",wall+10000,
                    "elapsedNanos",System.nanoTime()-started,"status",status);
            var ending=new LinkedHashMap<String,Object>(progress);ending.put("event","end");ending.put("endedAt",System.currentTimeMillis());ending.put("status",status);commandObserver.accept(ending);
            producerCommands.add(record);
            Files.writeString(producerEvidence.resolveSibling(producerEvidence.getFileName()+".commands.ndjson"),authMapper.writeValueAsString(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }
}
