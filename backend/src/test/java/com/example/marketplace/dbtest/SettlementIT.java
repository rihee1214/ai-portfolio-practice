package com.example.marketplace.dbtest;

import com.example.marketplace.claim.api.SettlementClaims;
import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.SettlementOrders;
import com.example.marketplace.order.infrastructure.JdbcOrders;
import com.example.marketplace.payment.api.SettlementPayments;
import com.example.marketplace.settlement.api.Settlements;
import com.example.marketplace.settlement.infrastructure.SettlementService;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementIT extends SettlementContractFixture {
    @Test void simultaneousSellerStatementsSerializeBeforeSessionReadAndRecheckLostSellerRole() throws Exception {
        var purchase=twoEqualUnits("seller-race");
        var shipment=paidShipment(purchase);
        var week=com.example.marketplace.settlement.domain.SettlementRules.week(Instant.now()).plusWeeks(47);
        var confirmedAt=com.example.marketplace.settlement.domain.SettlementRules.start(week).plusSeconds(300);
        completeShipment(purchase,shipment,confirmedAt.minusSeconds(60));
        var owner=shipmentAuthority(purchase);
        var api=confirmationsWith(Clock.fixed(confirmedAt,ZoneOffset.UTC),stage->{ });
        var current=api.get(purchase.order().orderNumber(),owner);
        var receipt=api.confirm(purchase.order().orderNumber(),owner,new OrderConfirmations.Command("concurrent-seller-read",current.revision(),
                current.units().stream().map(OrderConfirmations.Eligibility::unit).toList()));
        var settlementClock=(SettlementClock)clock;
        var previous=settlementClock.at;
        try {
            settlementClock.at=com.example.marketplace.settlement.domain.SettlementRules.start(week.plusWeeks(1));
            var operator=supportFixture("b-seller-reads","SETTLEMENTS").browser();
            post(operator,BATCHES,Map.of("key",UUID.randomUUID(),"weekStart",week.toString()),200);
        }finally { settlementClock.at=previous; }
        String path="/api/seller/settlements";var member=Map.of("id",owner.member());
        String email=jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id",member,String.class);
        var second=authBrowser();
        assertEquals(200,authRequest(second,"/api/auth/login",loginForm(email,SIGNUP_PASSWORD),csrfToken(second)).statusCode());
        String cause=receipt.receiptId().toString();
        for(boolean revoke:List.of(false,true)) {
            try(var lock=context.getBean(javax.sql.DataSource.class).getConnection();var pool=Executors.newFixedThreadPool(2)) {
                lock.setAutoCommit(false);
                try(var sql=lock.prepareStatement("SELECT pg_backend_pid() FROM marketplace.identity_user WHERE id=? FOR SHARE")) {
                    sql.setObject(1,owner.member());int blocker;
                    try(var rows=sql.executeQuery()){assertTrue(rows.next());blocker=rows.getInt(1);}
                    var requests=List.of(purchase.browser(),second).stream().map(client->pool.submit(
                            ()->accountRequest(client,"GET",path,null,null))).toList();
                    try {
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        List<Map<String,Object>> waits;
                        do {
                            waits=jdbc.queryForList("""
                                WITH RECURSIVE waiting(pid) AS (
                                 SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND :blocker=ANY(pg_blocking_pids(pid))
                                 UNION SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid=ANY(pg_blocking_pids(a.pid))
                                 WHERE a.datname=current_database())
                                SELECT a.pid,a.query FROM pg_stat_activity a JOIN waiting w ON w.pid=a.pid
                                WHERE a.query LIKE '%marketplace.identity_user%' AND a.query LIKE '%FOR NO KEY UPDATE%'
                                """,Map.of("blocker",blocker));
                            if(waits.size()==2) { break; }
                            Thread.sleep(20);
                        }while(System.nanoTime()<deadline);
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("A3_EVIDENCE"),"seller-statement-waits-"+revoke+".json"),authMapper.writeValueAsString(waits));
                        assertEquals(2,waits.size(),"both requests reached the real member lock before releasing the blocker");
                        if(revoke) {
                            try(var drop=lock.prepareStatement("DELETE FROM marketplace.role_assignment WHERE user_id=? AND role='SELLER'")) {
                                drop.setObject(1,owner.member());assertEquals(1,drop.executeUpdate());
                            }
                        }
                    }finally { lock.commit(); }
                    for(var request:requests) {
                        var response=request.get(15,TimeUnit.SECONDS);
                        assertEquals(revoke?403:200,response.statusCode(),response.body());
                        assertEquals(!revoke,response.body().contains(cause));
                    }
                }
            }
        }
    }
    private static final String BASE = "/api/admin/settlements";
    private static final String BATCHES = BASE + "/batches";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private JsonNode get(HttpClient client, String path) throws Exception {
        var response = accountRequest(client, "GET", path, null, null);
        assertEquals(200, response.statusCode(), path + response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return authMapper.readTree(response.body());
    }
    private JsonNode post(HttpClient client, String path, Map<String,Object> command, int status) throws Exception {
        var response = accountRequest(client, "POST", path, command, csrfToken(client));
        assertEquals(status, response.statusCode(), path + response.body());
        return status == 200 ? authMapper.readTree(response.body()) : null;
    }
    private static Map<String,Object> change(long version) {
        return Map.of("key", UUID.randomUUID(), "expectedVersion", version);
    }
    private void assertUnpaidConflict(HttpClient browser, String path, Map<String,Object> command, LocalDate ownerWeek) throws Exception {
        var response = accountRequest(browser, "POST", path, command, csrfToken(browser));
        assertEquals(409, response.statusCode());
        var body = authMapper.readTree(response.body());
        assertEquals("SETTLEMENT_UNPAID_BATCH_CONFLICT", body.path("code").asText());
        assertEquals(List.of(ownerWeek.toString()), body.path("conflictingWeeks").valueStream().map(JsonNode::asText).toList());
    }
    private OrderConfirmations.Result confirmRemaining(PaymentFixture f) {
        var number = f.order().orderNumber();
        var authority = shipmentAuthority(f);
        var view = confirmations().get(number, authority);
        var units = view.units().stream().filter(OrderConfirmations.Eligibility::eligible)
                .map(OrderConfirmations.Eligibility::unit).toList();
        assertFalse(units.isEmpty());
        return confirmations().confirm(number, authority, new OrderConfirmations.Command("p11-confirm", view.revision(), units));
    }
    private static List<JsonNode> entries(JsonNode batch, UUID lower, String kind) {
        return batch.path("items").valueStream().filter(i -> i.path("sellerOrderId").asText().equals(lower.toString())
                && i.path("kind").asText().equals(kind)).toList();
    }
    private static void assertTotals(JsonNode batch, long gross, long fee, long net) {
        assertEquals(Long.toString(gross), batch.path("gross").asText());
        assertEquals(Long.toString(fee), batch.path("fee").asText());
        assertEquals(Long.toString(net), batch.path("net").asText());
        assertEquals(net, batch.path("items").valueStream().mapToLong(i -> Long.parseLong(i.path("net").asText())).sum());
    }
    private record CutoffOrder(UUID order, UUID sellerOrder, UUID confirmation, UUID hold, UUID cause, OrderConfirmations.Unit second,
            PaymentFixture purchase) { }

    private CutoffOrder confirmFirstAndHoldSecond(String suffix, Instant sunday, Instant acquiredAt) throws Exception {
        var order = confirmFirst(suffix, sunday);
        holdSecond(order, acquiredAt);
        return order;
    }

    private CutoffOrder confirmFirst(String suffix, Instant sunday) throws Exception {
        var purchase = twoEqualUnits(suffix);
        var shipment = paidShipment(purchase);
        completeShipment(purchase, shipment, sunday.minusSeconds(60));
        var first = shipment.units().stream().filter(u -> u.ordinal() == 0).findFirst().orElseThrow();
        var second = shipment.units().stream().filter(u -> u.ordinal() == 1).findFirst().orElseThrow();
        String number = purchase.order().orderNumber();
        var authority = shipmentAuthority(purchase);
        var confirm = confirmationsWith(Clock.fixed(sunday, ZoneOffset.UTC), stage -> { });
        var receipt = confirm.confirm(number, authority, new OrderConfirmations.Command("first-only",
                confirm.get(number, authority).revision(), List.of(new OrderConfirmations.Unit(first.orderItemId(), 0))));
        return new CutoffOrder(purchase.order().id(), shipment.sellerOrderId(), receipt.receiptId(),
                UUID.randomUUID(), UUID.randomUUID(), new OrderConfirmations.Unit(second.orderItemId(), 1),purchase);
    }

    // Drive the two database-owned observations for this exact attempt. All production commands,
    // deferred constraints, payment budgets, FKs and result/outbox handlers still run.
    private UUID reserveFirstAt(PaymentFixture f,UUID seller,UUID item,Instant reservedAt,Instant releasedAt,String suffix) throws Exception {
        var operator=supportFixture("bclock-"+suffix,"SUPPORT");
        var who=operator.authority();
        var opened=post(f.browser(),"/api/support/cases",Map.of("key",UUID.randomUUID(),"orderNumber",f.order().orderNumber(),
                "sellerOrderId",seller,"body","확정 수량의 환불 예약 귀속 확인"),200);
        var target=UUID.fromString(opened.path("id").asText());
        var assignment=context.getBean(com.example.marketplace.operations.api.SupportAssignments.class);
        var actor=new com.example.marketplace.operations.api.SupportAssignments.Actor(who.member(),who.browser(),who.version());
        var entry=assignment.queue(actor).stream().filter(r->r.targetId().equals(target)).findFirst().orElseThrow();
        assignment.assign(target,actor,new com.example.marketplace.operations.api.SupportAssignments.Change(UUID.randomUUID(),entry.version(),who.member()));
        String path="/api/admin/support/cases/"+target+"/confirmed-refunds";
        UUID collection=UUID.randomUUID(),inspection=UUID.randomUUID(),approval=UUID.randomUUID();
        long version=opened.path("version").asLong();
        post(operator.browser(),path+"/COLLECT",Map.of("caseVersion",version,"key",collection,"item",item,"ordinal",0,"reference","회수 "+suffix),200);
        post(operator.browser(),path+"/INSPECT",Map.of("caseVersion",version,"key",inspection,"item",item,"ordinal",0,"previous",collection,
                "reference","검수 "+suffix,"responsibility","SELLER","reason","회수품 하자 확인"),200);
        post(operator.browser(),path+"/APPROVE",Map.of("caseVersion",version,"key",approval,"item",item,"ordinal",0,"previous",inspection,"reason","원확정 수량 확인"),200);
        UUID attempt=UUID.nameUUIDFromBytes(("support-item:"+approval).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var sql=jdbc.getJdbcTemplate();
        sql.execute("CREATE TABLE IF NOT EXISTS marketplace.test_p11b_attempt_clock(id uuid PRIMARY KEY,reserved_at timestamptz NOT NULL,released_at timestamptz NOT NULL)");
        sql.execute("""
                CREATE OR REPLACE FUNCTION marketplace.test_p11b_attempt_time() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE observation timestamptz;
                BEGIN
                 IF TG_TABLE_NAME='payment_refund_attempt' THEN
                  SELECT reserved_at INTO observation FROM marketplace.test_p11b_attempt_clock WHERE id=NEW.id;
                  IF FOUND THEN NEW.created_at=observation;END IF;
                 ELSIF NEW.action='FAILED' THEN
                  SELECT released_at INTO observation FROM marketplace.test_p11b_attempt_clock WHERE id=NEW.attempt_id;
                  IF FOUND THEN NEW.occurred_at=observation;END IF;
                 END IF;RETURN NEW;
                END $$;
                """);
        for(String table:List.of("payment_refund_attempt","payment_refund_event")) {
            sql.execute("CREATE OR REPLACE TRIGGER test_p11b_attempt_time BEFORE INSERT ON marketplace."+table+
                    " FOR EACH ROW EXECUTE FUNCTION marketplace.test_p11b_attempt_time()");
        }
        jdbc.update("INSERT INTO marketplace.test_p11b_attempt_clock VALUES(:id,:created,:released)",Map.of("id",attempt,
                "created",java.sql.Timestamp.from(reservedAt),"released",java.sql.Timestamp.from(releasedAt)));
        post(operator.browser(),path+"/EXECUTE",Map.of("caseVersion",version,"key",UUID.randomUUID(),"item",item,"ordinal",0,"previous",approval),200);
        jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','FAILED')",Map.of("id",approval));
        assertEquals(reservedAt,jdbc.queryForObject("SELECT created_at FROM marketplace.payment_refund_attempt WHERE id=:id",Map.of("id",attempt),java.sql.Timestamp.class).toInstant());
        return attempt;
    }
    private void releaseFailedReservation(UUID attempt,Instant expected) {
        runCancellationJob("REFUND_SUBMIT.v1",attempt);runCancellationJob("REFUND_RESULT_RECORDED.v1",attempt);
        assertEquals(expected,jdbc.queryForObject("SELECT occurred_at FROM marketplace.payment_refund_event WHERE attempt_id=:id AND action='FAILED'",
                Map.of("id",attempt),java.sql.Timestamp.class).toInstant());
    }

    private void holdSecond(CutoffOrder order, Instant acquiredAt) {
        var atClaim = confirmationsWith(Clock.fixed(acquiredAt, ZoneOffset.UTC), stage -> { });
        new TransactionTemplate(transactionManager).executeWithoutResult(t -> atClaim.acquire(order.order(),
                order.hold(), order.cause(), "RETURN", List.of(order.second())));
    }

    @Test void knownHoldOnSecondUnitLeavesFirstConfirmedUnitPayableInNewDraft() throws Exception {
        var admin = supportFixture("p11b-scoped-hold", "SETTLEMENTS").browser();
        var week = LocalDate.now(SEOUL).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).plusWeeks(28);
        Instant cutoff = week.plusWeeks(1).atStartOfDay(SEOUL).toInstant();
        var order = confirmFirstAndHoldSecond("p11b-known", cutoff.minusSeconds(60), cutoff.minusSeconds(1));
        ((SettlementClock) clock).at = cutoff;
        var draft = post(admin, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID()), 200);
        var products = entries(draft, order.sellerOrder(), "PRODUCT");
        assertEquals(1, products.size());
        assertEquals(0, products.getFirst().path("ordinal").asInt());
        assertEquals(order.confirmation().toString(), products.getFirst().path("causeId").asText());
        assertEquals(501L, entries(draft, order.sellerOrder(), "COMMISSION").getFirst().path("fee").asLong());
        assertTrue(entries(draft, order.sellerOrder(), "SHIPPING").isEmpty());
        assertTotals(draft, 10019, 501, 9518);
    }

    @Test void lateWeeklyPayoutUsesTheExclusiveMondayCutoffAndNeverPaysTheSameQuantityTwice() throws Exception {
        var admin = supportFixture("p11-delayed-week", "SETTLEMENTS").browser();
        // Pick a separate week from the current-time refund workflows. Every event below uses a real order port.
        var week = LocalDate.now(SEOUL).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).plusWeeks(3);
        Instant cutoff = week.plusWeeks(1).atStartOfDay(SEOUL).toInstant();
        Instant sunday = cutoff.minusSeconds(60);
        var before = confirmFirstAndHoldSecond("before-cutoff", sunday, cutoff.minusNanos(1000));
        var monday = confirmFirstAndHoldSecond("on-cutoff", sunday, cutoff);
        Instant tuesday = cutoff.plusSeconds(86400);
        Instant releasedAt=tuesday.plusSeconds(60);
        UUID beforeReservation=reserveFirstAt(before.purchase(),before.sellerOrder(),before.second().itemId(),cutoff.minusNanos(1000),releasedAt,"before");
        UUID mondayReservation=reserveFirstAt(monday.purchase(),monday.sellerOrder(),monday.second().itemId(),cutoff,releasedAt,"monday");
        ((SettlementClock) clock).at = tuesday;
        Map<String,Object> open = Map.of("weekStart", week.toString(), "key", UUID.randomUUID());
        var draft = post(admin, BATCHES, open, 200);
        assertTotals(draft, 10019, 501, 9518);
        var product = entries(draft, monday.sellerOrder(), "PRODUCT").getFirst();
        assertEquals(monday.confirmation().toString(), product.path("causeId").asText());
        assertEquals(sunday, Instant.parse(product.path("eligibleAt").asText()));
        assertEquals(0, product.path("eligibilityHolds").size());
        assertTrue(entries(draft, before.sellerOrder(), "PRODUCT").isEmpty());
        assertTrue(draft.path("held").valueStream().anyMatch(h -> before.sellerOrder().toString().equals(h.path("sellerOrderId").asText())));
        assertFalse(draft.path("held").valueStream().anyMatch(h -> monday.sellerOrder().toString().equals(h.path("sellerOrderId").asText())));
        assertEquals(draft, post(admin, BATCHES, open, 200));
        String path = BATCHES + "/" + draft.path("id").asText();
        var reviewed = post(admin, path + "/review", change(draft.path("version").asLong()), 200);
        var pay = change(reviewed.path("version").asLong());

        releaseFailedReservation(beforeReservation,releasedAt);
        releaseFailedReservation(mondayReservation,releasedAt);
        var release = confirmationsWith(Clock.fixed(releasedAt, ZoneOffset.UTC), stage -> { });
        for (var order : List.of(before, monday)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(t ->
                    release.release(order.order(), order.hold(), order.cause(), "WITHDRAWN"));
        }
        ((SettlementClock) clock).at = releasedAt;
        var paid = post(admin, path + "/simulate-pay", pay, 200);
        assertEquals("PAID_MOCK", paid.path("state").asText());
        assertEquals(draft.path("items"), paid.path("items"));
        assertEquals(draft.path("held"), paid.path("held"));
        ((SettlementClock) clock).at = week.plusWeeks(2).atStartOfDay(SEOUL).toInstant();
        assertEquals(paid, post(admin, path + "/simulate-pay", pay, 200));
        var next = post(admin, BATCHES, Map.of("weekStart", week.plusWeeks(1).toString(), "key", UUID.randomUUID()), 200);
        assertTotals(next, 10019, 501, 9518);
        assertTrue(entries(next, monday.sellerOrder(), "PRODUCT").isEmpty());
        var carried = entries(next, before.sellerOrder(), "PRODUCT").getFirst();
        assertEquals(before.confirmation().toString(), carried.path("causeId").asText());
        assertTrue(carried.path("eligibilityHolds").isEmpty());
        assertReservation(carried.path("eligibilityReservations"),"REFUND",beforeReservation,cutoff.minusNanos(1000),releasedAt);
        assertEquals(releasedAt, Instant.parse(carried.path("eligibleAt").asText()));
        String nextPath = BATCHES + "/" + next.path("id").asText();
        var nextReview = post(admin, nextPath + "/review", change(next.path("version").asLong()), 200);
        var nextPay = change(nextReview.path("version").asLong());
        var nextPaid = post(admin, nextPath + "/simulate-pay", nextPay, 200);
        assertEquals(nextPaid, post(admin, nextPath + "/simulate-pay", nextPay, 200));
        assertEquals(List.of(9518L, 9518L), jdbc.queryForList("""
                SELECT amount FROM marketplace.settlement_mock_payment WHERE batch_id IN (:ids)
                """, Map.of("ids", List.of(UUID.fromString(paid.path("id").asText()), UUID.fromString(nextPaid.path("id").asText()))), Long.class));
    }
    @Test void unpaidPriorDraftMustReleaseItsLedgerBeforeTheFollowingWeekCanPay() throws Exception {
        var admin = supportFixture("p11-stale-draft", "SETTLEMENTS").browser();
        var week = LocalDate.now(SEOUL).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).plusWeeks(8);
        Instant cutoff = week.plusWeeks(1).atStartOfDay(SEOUL).toInstant();
        Instant sunday = cutoff.minusSeconds(60);
        var at = (SettlementClock) clock;
        var first = confirmFirst("prior-draft", sunday);
        at.at = sunday;
        var w1 = post(admin, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID()), 200);
        var original = entries(w1, first.sellerOrder(), "PRODUCT").getFirst().path("id");
        holdSecond(first, cutoff.minusNanos(1000));
        UUID reserved=reserveFirstAt(first.purchase(),first.sellerOrder(),first.second().itemId(),cutoff.minusNanos(1000),cutoff.plusSeconds(120),"draft");
        var other = confirmFirst("independent", cutoff.plusSeconds(60));
        at.at = week.plusWeeks(2).atStartOfDay(SEOUL).toInstant();
        Map<String,Object> openNext = Map.of("weekStart", week.plusWeeks(1).toString(), "key", UUID.randomUUID());
        var w2 = post(admin, BATCHES, openNext, 200);
        var path = BATCHES + "/" + w2.path("id").asText();
        assertTotals(w2, 10019, 501, 9518);
        assertEquals(1, entries(w2, other.sellerOrder(), "PRODUCT").size());
        var reviewKey = change(w2.path("version").asLong());
        var reviewed = post(admin, path + "/review", reviewKey, 200);

        // The source now records a release belonging to W2. The original row is still assigned to W1's unpaid draft.
        var release = confirmationsWith(Clock.fixed(cutoff.plusSeconds(120), ZoneOffset.UTC), stage -> { });
        new TransactionTemplate(transactionManager).executeWithoutResult(t -> release.release(first.order(), first.hold(), first.cause(), "WITHDRAWN"));
        releaseFailedReservation(reserved,cutoff.plusSeconds(120));
        var pay = change(reviewed.path("version").asLong());
        assertUnpaidConflict(admin, path + "/simulate-pay", pay, week);
        assertUnpaidConflict(admin, path + "/review", reviewKey, week);
        assertUnpaidConflict(admin, BATCHES, openNext, week);
        Map<String,Object> rebuild = Map.of("weekStart", week.plusWeeks(1).toString(), "key", UUID.randomUUID());
        assertUnpaidConflict(admin, BATCHES, rebuild, week);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.settlement_mock_payment WHERE batch_id=:id",
                Map.of("id", UUID.fromString(w2.path("id").asText())), Integer.class));
        var cleared = post(admin, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID()), 200);
        assertTotals(cleared, 0, 0, 0);
        assertTrue(cleared.path("items").isEmpty());
        var corrected = post(admin, BATCHES, rebuild, 200);
        assertEquals(original, entries(corrected, first.sellerOrder(), "PRODUCT").getFirst().path("id"));
        assertEquals(1, entries(corrected, other.sellerOrder(), "PRODUCT").size());
        assertTotals(corrected, 20038, 1002, 19036);
        var current = post(admin, path + "/review", change(corrected.path("version").asLong()), 200);
        var retry = change(current.path("version").asLong());
        var paid = post(admin, path + "/simulate-pay", retry, 200);
        assertEquals(paid, post(admin, path + "/simulate-pay", retry, 200));
        assertEquals(List.of(19036L), jdbc.queryForList("SELECT amount FROM marketplace.settlement_mock_payment WHERE batch_id=:id",
                Map.of("id", UUID.fromString(w2.path("id").asText())), Long.class));
    }

    // Keep the real withdrawal transaction, including its order/fee/refund receipts, while supplying two week-boundary clocks.
    private ReturnFees withdrawalAt(Instant holdReleasedAt, Instant feeReleasedAt) {
        var observed = new NamedParameterJdbcTemplate(source) {
            @Override public <T> T queryForObject(String sql, Map<String,?> params, Class<T> requiredType) {
                if (sql.equals("SELECT clock_timestamp()") && requiredType == java.sql.Timestamp.class) {
                    return requiredType.cast(java.sql.Timestamp.from(feeReleasedAt));
                }
                return super.queryForObject(sql, params, requiredType);
            }
        };
        return new com.example.marketplace.claim.infrastructure.JdbcReturnFees(observed,
                context.getBean(com.example.marketplace.order.api.ReturnSources.class),
                context.getBean(com.example.marketplace.order.api.OrderQueries.class), orders(),
                context.getBean(com.example.marketplace.claim.api.FinancialIntents.class),
                context.getBean(com.example.marketplace.payment.api.Refunds.class), context.getBean(com.example.marketplace.platform.api.Outbox.class),
                confirmationsWith(Clock.fixed(holdReleasedAt, ZoneOffset.UTC), stage -> { }),
                context.getBean(com.example.marketplace.platform.api.JobLeases.class),
                context.getBean(com.example.marketplace.claim.infrastructure.CurrentSupport.class), transactionManager,
                context.getBean(com.example.marketplace.claim.infrastructure.InspectedReturnQuotes.class));
    }

    private static void assertReservation(JsonNode reservations, String kind, UUID id, Instant acquired, Instant released) {
        var matching = reservations.valueStream().filter(r -> kind.equals(r.path("kind").asText())
                && id.toString().equals(r.path("id").asText())).toList();
        assertEquals(1, matching.size());
        assertEquals(acquired, Instant.parse(matching.getFirst().path("acquiredAt").asText()));
        assertEquals(released, Instant.parse(matching.getFirst().path("releasedAt").asText()));
    }
    private UUID refundFirstAndConfirmSecond() throws Exception {
        var f = twoEqualUnits("remainder");
        var shipment = paidShipment(f);
        completeShipment(f, shipment, Instant.now());
        var first = shipment.units().stream().filter(u -> u.ordinal() == 0).findFirst().orElseThrow();
        var physical = recoveryPhysicalUnit(new ReturnParties(f, f, shipment), first, "p11-remainder-0");
        var consent = inspectAndConsentBuyerReturn(physical, "p11-remainder-fee");
        assertEquals("7019", consent.quote().refund());
        completeActualReturnMoney(physical, consent.accepted());
        runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1", shipment.sellerOrderId());
        var rest = confirmRemaining(f);
        runCancellationJob("ORDER_CONFIRMATION_RECORDED", rest.receiptId());
        runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1", shipment.sellerOrderId());
        return shipment.sellerOrderId();
    }
    private void interruptedPayment(UUID batch, long version, SupportFixture admin) {
        var fail = new SettlementService(jdbc, context.getBean(SettlementOrders.class), context.getBean(SettlementPayments.class),
                context.getBean(SettlementClaims.class), context.getBean(HistorySessions.class), context.getBean(AccessPolicy.class),
                clock, stage -> { if (stage.equals("PAYMENT")) { throw new IllegalStateException("interrupted payment"); } });
        var actor = new Settlements.Actor(admin.authority().member(), admin.authority().browser(), admin.authority().version());
        var command = new Settlements.Change(UUID.randomUUID(), version);
        var tx = new TransactionTemplate(transactionManager);
        assertEquals("interrupted payment", assertThrows(IllegalStateException.class,
                () -> tx.executeWithoutResult(s -> fail.simulatePay(batch, command, actor))).getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.settlement_mock_payment WHERE batch_id=:id",
                Map.of("id", batch), Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.settlement_command WHERE request_key=:key",
                Map.of("key", command.key()), Integer.class));
    }

    @Test void verifiedWeeklyBatchReconcilesTwoSellersAndPaysOnlyOnce() throws Exception {
        var admin = supportFixture("p11-ledger", "SETTLEMENTS");
        var support = supportFixture("p11-support", "SUPPORT");
        var browser = admin.browser();
        var localClock = (SettlementClock) clock;
        var clean = cancellationMoneyFixture("p11-confirmed", false, false);
        var shipment = paidShipment(clean);
        completeShipment(clean, shipment, Instant.now());
        var confirmed = confirmRemaining(clean);
        localClock.at = confirmed.confirmedAt();
        var policy = get(browser, BASE + "/policy");
        assertEquals("SETTLEMENT_V1", policy.path("version").asText());
        assertEquals(500, policy.path("rateBasisPoints").asInt());
        var week = LocalDate.parse(policy.path("currentWeekStart").asText());
        var end = week.plusWeeks(1).atStartOfDay(SEOUL).toInstant();
        Map<String,Object> open = Map.of("weekStart", week.toString(), "key", UUID.randomUUID());
        assertEquals(403, accountRequest(support.browser(), "GET", BATCHES, null, null).statusCode());
        assertEquals(403, accountRequest(browser, "POST", BATCHES, open, null).statusCode());
        post(browser, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID(), "amount", 1), 400);

        var unresolved = cancellationAtRefundState("p11-held", false);
        jdbc.update("INSERT INTO marketplace.payment_refund_mock_setting(claim_id,purpose,behavior) VALUES(:id,'ITEM','UNKNOWN')",
                Map.of("id", unresolved.claim()));
        runCancellationJob("REFUND_SUBMIT.v1", unresolved.attempt());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT state FROM marketplace.payment_refund_attempt WHERE id=:id",
                Map.of("id", unresolved.attempt()), String.class));
        var budgetSql = "SELECT reserved,succeeded FROM marketplace.payment_refund_budget WHERE order_id=:id";
        var heldOrder = Map.of("id", unresolved.fixture().order().id());
        var budget = jdbc.queryForMap(budgetSql, heldOrder);
        assertTrue(((Number)budget.get("reserved")).longValue() > 0);
        assertEquals(0L, ((Number)budget.get("succeeded")).longValue());
        UUID heldSeller = jdbc.queryForObject("SELECT seller_order_id FROM marketplace.payment_refund_attempt WHERE id=:id",
                Map.of("id", unresolved.attempt()), UUID.class);
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM marketplace.payment_refund_attempt p
                CROSS JOIN LATERAL marketplace.settlement_refund_intervals(p.payment_id,p.seller_order_id) r
                WHERE p.id=:attempt AND r.id=p.id AND r.acquired_at=p.created_at AND r.released_at IS NULL
                """, Map.of("attempt", unresolved.attempt()), Integer.class));
        var draft = post(browser, BATCHES, open, 200);
        var path = BATCHES + "/" + draft.path("id").asText();
        var id = UUID.fromString(draft.path("id").asText());
        assertTotals(draft, 33001, 1500, 31501);
        assertEquals(List.of("10000","10000","10001"), entries(draft, shipment.sellerOrderId(), "PRODUCT").stream()
                .map(i -> i.path("amount").asText()).toList());
        assertTrue(entries(draft, shipment.sellerOrderId(), "PRODUCT").stream()
                .allMatch(i -> i.path("causeId").asText().equals(confirmed.receiptId().toString())));
        assertTrue(draft.path("held").valueStream().anyMatch(h -> heldSeller.toString().equals(h.path("sellerOrderId").asText())));
        assertFalse(draft.path("items").valueStream().anyMatch(i -> heldSeller.toString().equals(i.path("sellerOrderId").asText())));
        assertEquals(draft, get(browser, path));
        assertEquals(draft, post(browser, BATCHES, open, 200));
        var productEntry = UUID.fromString(entries(draft, shipment.sellerOrderId(), "PRODUCT").getFirst().path("id").asText());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO marketplace.settlement_ledger
                SELECT (jsonb_populate_record(NULL::marketplace.settlement_ledger,
                    to_jsonb(l) || jsonb_build_object('id',CAST(:fake AS uuid),'source_key','duplicate-economic-source'))).*
                FROM marketplace.settlement_ledger l WHERE l.id=:original
                """, Map.of("fake", UUID.randomUUID(), "original", productEntry)));
        post(browser, path + "/review", change(draft.path("version").asLong()), 409);
        post(browser, path + "/simulate-pay", change(draft.path("version").asLong()), 409);

        // A delivered second quantity alone must not defer the first one's confirmed proceeds.
        var partial = twoEqualUnits("partial-hold");
        var partialShipment = paidShipment(partial);
        completeShipment(partial, partialShipment, Instant.now());
        var partialNumber = partial.order().orderNumber();
        var partialOwner = shipmentAuthority(partial);
        var firstUnit = partialShipment.units().stream().filter(u -> u.ordinal() == 0).findFirst().orElseThrow();
        var firstOnly = List.of(new OrderConfirmations.Unit(firstUnit.orderItemId(), 0));
        var firstReceipt = confirmations().confirm(partialNumber, partialOwner, new OrderConfirmations.Command("first-only",
                confirmations().get(partialNumber, partialOwner).revision(), firstOnly));
        var withPartial = post(browser, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID()), 200);
        assertTotals(withPartial, 43020, 2001, 41019);
        assertEquals(1, entries(withPartial, partialShipment.sellerOrderId(), "PRODUCT").size());
        assertEquals(firstReceipt.receiptId().toString(), entries(withPartial, partialShipment.sellerOrderId(), "PRODUCT").getFirst().path("causeId").asText());
        assertTrue(entries(withPartial, partialShipment.sellerOrderId(), "SHIPPING").isEmpty());
        var secondUnit = partialShipment.units().stream().filter(u -> u.ordinal() == 1).findFirst().orElseThrow();
        var fee = acceptReturnFee(partial, List.of(new ReturnFees.Target(secondUnit.orderItemId(), secondUnit.ordinal(),
                ReturnFees.Responsibility.BUYER)), "p11-carry-fee");
        UUID holdId = fee.hold();
        Instant exactReservation=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID firstReservation=reserveFirstAt(partial,partialShipment.sellerOrderId(),firstUnit.orderItemId(),exactReservation,end.plusSeconds(60).plusNanos(1000),"partial");
        var tx = new TransactionTemplate(transactionManager);

        var mixed = cancellationMoneyFixture("p11-refund", true, false);
        var returned = paidShipment(mixed);
        completeShipment(mixed, returned, Instant.now());
        var parties = new ReturnParties(mixed, mixed, returned);
        var first = returned.units().stream().filter(u -> u.ordinal() == 0).findFirst().orElseThrow();
        var physical = recoveryPhysicalUnit(parties, first, "p11-unit");
        var consent = inspectAndConsentBuyerReturn(physical, "p11-buyer");
        assertEquals("5001", consent.quote().refund());
        completeActualReturnMoney(physical, consent.accepted());
        runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1", returned.sellerOrderId());
        var rest = confirmRemaining(mixed);
        runCancellationJob("ORDER_CONFIRMATION_RECORDED", rest.receiptId());
        runCancellationJob("SELLER_AFTERCARE_REEVALUATE.v1", returned.sellerOrderId());
        localClock.at = end;
        post(browser, BATCHES, Map.of("weekStart", week.plusWeeks(1).toString(), "key", UUID.randomUUID()), 409);
        post(browser, path + "/review", change(withPartial.path("version").asLong()), 409);
        var updated = post(browser, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID()), 200);
        assertTotals(updated, 54004, 2300, 51704);
        assertTrue(entries(updated, partialShipment.sellerOrderId(), "PRODUCT").isEmpty());
        assertTrue(updated.path("held").valueStream().anyMatch(h -> partialShipment.sellerOrderId().toString().equals(h.path("sellerOrderId").asText())));
        var refund = entries(updated, returned.sellerOrderId(), "REFUND");
        assertEquals(1, refund.size());
        assertEquals("-8001", refund.getFirst().path("amount").asText());
        assertEquals(consent.accepted().refundAttemptId().toString(), refund.getFirst().path("refundAttemptId").asText());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM marketplace.claim_fee_assessment a
                JOIN marketplace.claim_fee_application app ON app.assessment_id=a.id
                CROSS JOIN LATERAL marketplace.settlement_claim_intervals(a.seller_order_id) r
                WHERE a.id=:assessment AND r.id=a.id AND r.acquired_at=a.accepted_at AND r.released_at=app.applied_at
                """, Map.of("assessment", consent.accepted().assessmentId()), Integer.class));
        assertEquals("-400", entries(updated, returned.sellerOrderId(), "COMMISSION_REVERSAL").getFirst().path("fee").asText());
        assertEquals("3000", entries(updated, returned.sellerOrderId(), "RECOVERY_FEE").getFirst().path("amount").asText());
        assertEquals("2000", entries(updated, returned.sellerOrderId(), "SHIPPING").getFirst().path("amount").asText());
        assertTrue(entries(updated, returned.sellerOrderId(), "SHIPPING_REFUND").isEmpty());
        var review = change(updated.path("version").asLong());
        var reviewed = post(browser, path + "/review", review, 200);
        assertEquals("REVIEWED", reviewed.path("state").asText());
        assertEquals(reviewed, post(browser, path + "/review", review, 200));
        var remainderSeller = refundFirstAndConfirmSecond();
        post(browser, path + "/simulate-pay", change(reviewed.path("version").asLong()), 409);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM marketplace.settlement_mock_payment WHERE batch_id=:id",
                Map.of("id", id), Integer.class));
        var reopened = post(browser, BATCHES, Map.of("weekStart", week.toString(), "key", UUID.randomUUID()), 200);
        assertEquals("DRAFT", reopened.path("state").asText());
        assertTotals(reopened, 70023, 2800, 67223);
        var previous = Map.of("id", id, "version", reviewed.path("version").asLong());
        var history = jdbc.queryForMap("""
                SELECT state,gross,fee,net FROM marketplace.settlement_batch_revision
                WHERE batch_id=:id AND version=:version
                """, previous);
        assertEquals("REVIEWED", history.get("state"));
        for (String amount : List.of("gross", "fee", "net")) {
            assertEquals(reviewed.path(amount).asText(), history.get(amount).toString());
        }
        assertEquals(reviewed.path("items").valueStream().map(i -> i.path("id").asText()).sorted().toList(),
                jdbc.queryForList("""
                    SELECT unnest(ledger_ids)::text FROM marketplace.settlement_batch_revision
                    WHERE batch_id=:id AND version=:version ORDER BY 1
                    """, previous, String.class));
        var archivedEligibility = authMapper.readTree(jdbc.queryForObject("""
                SELECT eligibility::text FROM marketplace.settlement_batch_revision WHERE batch_id=:id AND version=:version
                """, previous, String.class));
        for (var item : reviewed.path("items")) {
            var archived = archivedEligibility.valueStream()
                    .filter(e -> e.path("ledgerId").asText().equals(item.path("id").asText())).findFirst().orElseThrow();
            assertTrue(item.path("eligibilityReservations").isArray());
            assertEquals(item.path("eligibilityReservations"), archived.path("reservations"));
        }
        assertEquals(List.of("501", "500"), entries(reopened, remainderSeller, "COMMISSION").stream()
                .sorted(java.util.Comparator.comparingInt(i -> i.path("ordinal").asInt()))
                .map(i -> i.path("fee").asText()).toList());
        var reversal = entries(reopened, remainderSeller, "COMMISSION_REVERSAL");
        assertEquals(1, reversal.size());
        assertEquals(0, reversal.getFirst().path("ordinal").asInt());
        assertEquals("-501", reversal.getFirst().path("fee").asText());
        reviewed = post(browser, path + "/review", change(reopened.path("version").asLong()), 200);
        long version = reviewed.path("version").asLong();
        interruptedPayment(id, version, admin);
        assertEquals(reviewed, get(browser, path));
        var pay = change(version);
        String token = csrfToken(browser);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(pay, pay).stream().map(cmd -> pool.submit(() -> {
                gate.await(); return accountRequest(browser, "POST", path + "/simulate-pay", cmd, token);
            })).toList();
            gate.countDown();
            for (var task : tasks) { assertEquals(200, task.get(15, TimeUnit.SECONDS).statusCode()); }
        }
        post(browser, path + "/simulate-pay", change(version), 409);
        var paid = get(browser, path);
        assertEquals("PAID_MOCK", paid.path("state").asText());
        assertEquals(reviewed.path("items"), paid.path("items"));
        assertEquals(List.of(67223L), jdbc.queryForList("SELECT amount FROM marketplace.settlement_mock_payment WHERE batch_id=:id",
                Map.of("id", id), Long.class));
        assertEquals(budget, jdbc.queryForMap(budgetSql, heldOrder));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM marketplace.settlement_batch_item WHERE batch_id=:id", Map.of("id", id)));

        // A different quantity held this seller across the cutoff. The other stores' payment is already final.
        var releaseAt = end.plusSeconds(60);
        Instant finalRelease = releaseAt.plusNanos(1000);
        var withdrawal = withdrawalAt(releaseAt, finalRelease);
        tx.executeWithoutResult(t -> withdrawal.withdraw(partialNumber, partialOwner, fee.accepted().id()));
        releaseFailedReservation(firstReservation,finalRelease);
        var receipts = jdbc.queryForMap("""
                SELECT a.accepted_at,w.withdrawn_at,p.created_at,e.occurred_at
                FROM marketplace.claim_fee_assessment a JOIN marketplace.claim_fee_withdrawal w ON a.id=w.assessment_id
                JOIN marketplace.payment_refund_attempt p ON p.id=w.attempt_id
                JOIN marketplace.payment_refund_event e ON e.attempt_id=p.id AND e.action='WITHDRAWN'
                WHERE a.id=:fee AND p.id=:refund
                """, Map.of("fee", fee.accepted().id(), "refund", fee.attempt()));
        Instant feeStart = ((java.sql.Timestamp)receipts.get("accepted_at")).toInstant();
        Instant refundStart = ((java.sql.Timestamp)receipts.get("created_at")).toInstant();
        Instant refundEnd = ((java.sql.Timestamp)receipts.get("occurred_at")).toInstant();
        assertTrue(feeStart.isBefore(end));
        assertEquals(finalRelease, ((java.sql.Timestamp)receipts.get("withdrawn_at")).toInstant());
        localClock.at = finalRelease;
        Map<String,Object> nextWeek = Map.of("weekStart", week.plusWeeks(1).toString(), "key", UUID.randomUUID());
        var carried = post(browser, BATCHES, nextWeek, 200);
        assertTotals(carried, 10019, 501, 9518);
        var carriedUnit = entries(carried, partialShipment.sellerOrderId(), "PRODUCT").getFirst();
        var originalUnit = entries(withPartial, partialShipment.sellerOrderId(), "PRODUCT").getFirst();
        assertEquals(originalUnit.path("id"), carriedUnit.path("id"));
        assertEquals(originalUnit.path("occurredAt"), carriedUnit.path("occurredAt"));
        assertEquals(finalRelease, Instant.parse(carriedUnit.path("eligibleAt").asText()));
        var reservations = carriedUnit.path("eligibilityReservations");
        assertEquals(1, reservations.size());
        assertReservation(reservations,"REFUND",firstReservation,exactReservation,finalRelease);
        assertTrue(carriedUnit.path("eligibilityHolds").isEmpty());
        var reservationsForSeller=context.getBean(SettlementClaims.class);
        var ownClaims=tx.execute(t->reservationsForSeller.lock(partialShipment.sellerOrderId()));
        assertTrue(ownClaims.reservations().stream().anyMatch(r->r.id().equals(fee.accepted().id())&&r.acquiredAt().equals(feeStart)
                &&r.releasedAt().equals(finalRelease)));
        assertEquals(List.of(new OrderConfirmations.Unit(secondUnit.orderItemId(),1)),ownClaims.attributedUnits().get(fee.attempt()));
        assertEquals(List.of(new OrderConfirmations.Unit(firstUnit.orderItemId(),0)),ownClaims.attributedUnits().get(firstReservation));
        assertEquals(releaseAt,jdbc.queryForObject("SELECT occurred_at FROM marketplace.order_lifecycle_event WHERE cause_id=:id AND action='RELEASED'",Map.of("id",holdId),java.sql.Timestamp.class).toInstant());
        assertFalse(refundEnd.isBefore(refundStart));
        assertEquals(paid, get(browser, path));
        localClock.at = week.plusWeeks(2).atStartOfDay(SEOUL).toInstant();
        var nextPath = BATCHES + "/" + carried.path("id").asText();
        var nextReview = post(browser, nextPath + "/review", change(carried.path("version").asLong()), 200);
        var nextPay = change(nextReview.path("version").asLong());
        var nextPaid = post(browser, nextPath + "/simulate-pay", nextPay, 200);
        assertEquals(nextPaid, post(browser, nextPath + "/simulate-pay", nextPay, 200));
        var reviewedReservations = authMapper.readTree(jdbc.queryForObject("""
                SELECT (entry->'reservations')::text FROM marketplace.settlement_batch_revision b,
                LATERAL jsonb_array_elements(b.eligibility) entry
                WHERE b.batch_id=:batch AND b.version=:version AND b.state='REVIEWED' AND entry->>'ledgerId'=:ledger
                """, Map.of("batch", UUID.fromString(carried.path("id").asText()), "version", nextReview.path("version").asLong(),
                "ledger", carriedUnit.path("id").asText()), String.class));
        assertEquals(1, reviewedReservations.size());
        assertReservation(reviewedReservations,"REFUND",firstReservation,exactReservation,finalRelease);
        assertEquals(List.of(9518L), jdbc.queryForList("SELECT amount FROM marketplace.settlement_mock_payment WHERE batch_id=:id",
                Map.of("id", UUID.fromString(carried.path("id").asText())), Long.class));
        jdbc.update("DELETE FROM marketplace.admin_permission WHERE user_id=:id AND permission='SETTLEMENTS'",
                Map.of("id", admin.authority().member()));
        assertEquals(403, accountRequest(browser, "GET", path, null, null).statusCode());
        post(browser, path + "/simulate-pay", pay, 403);
    }

    @Test void upgradeLeavesEarlierOrdersUnversionedEvenWhenRetriedAfterActivation() {
        var isolated = freshLegacyDatabase("p11-policy-upgrade");
        var target = new NamedParameterJdbcTemplate(isolated);
        var migrations = Flyway.configure().dataSource(isolated).schemas("marketplace").defaultSchema("marketplace")
                .locations("classpath:db/migration").cleanDisabled(true);
        migrations.target("116").load().migrate();
        var draft = orderDraft("p11-legacy");
        var owners = Map.of("buyer", draft.memberId(), "stores", draft.quote().stores().stream()
                .map(com.example.marketplace.promotion.api.Quotes.Store::storeId).toList());
        copyLegacyRows(target, "identity_user", """
                SELECT * FROM marketplace.identity_user WHERE id=:buyer OR id IN (
                    SELECT owner_id FROM marketplace.seller_store WHERE id IN (:stores))
                """, owners);
        copyLegacyRows(target, "seller_store", "SELECT * FROM marketplace.seller_store WHERE id IN (:stores)", owners);
        var orders = new JdbcOrders(target, authMapper, new JdbcTransactionManager(isolated));
        var original = orders.save(draft);
        migrations.target("117").load().migrate();
        assertEquals(original, orders.save(draft));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM marketplace.order_settlement_policy", Map.of(), Integer.class));
        var next = new com.example.marketplace.order.api.Orders.Draft(UUID.randomUUID(), "after-activation",
                draft.requestHash(), draft.memberId(), null, null, draft.mode(), draft.quote(), draft.contact(), draft.createdAt(), draft.expiresAt());
        var fresh = orders.save(next);
        assertEquals("SETTLEMENT_V1", target.queryForObject("SELECT version FROM marketplace.order_settlement_policy WHERE order_id=:id",
                Map.of("id", fresh.id()), String.class));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM marketplace.order_settlement_policy WHERE order_id=:id",
                Map.of("id", original.id()), Integer.class));
    }
}
