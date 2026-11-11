package com.example.marketplace.dbtest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.bean.override.convention.TestBean;
import static org.junit.jupiter.api.Assertions.assertEquals;

abstract class SettlementContractFixture extends PostgresContractFixture {
    protected static final class SettlementClock extends Clock {
        volatile Instant at = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(at, zone); }
        @Override public Instant instant() { return at; }
    }
    @TestBean(name="settlementClock", methodName="testSettlementClock")
    protected Clock clock;
    static Clock testSettlementClock() { return new SettlementClock(); }

    protected PaymentFixture twoEqualUnits(String suffix) throws Exception {
        var d = orderDraft("p11-" + suffix);
        var line = d.quote().stores().getFirst().lines().getFirst();
        var skus = context.getBean(com.example.marketplace.catalog.api.Skus.class);
        var sku = skus.list(d.memberId(), line.productId(), 0).items().getFirst();
        skus.save(d.memberId(), line.productId(), sku.id(),
                new com.example.marketplace.catalog.api.Skus.Input(List.of(), 10019L, true, sku.version()));
        var lines = List.of(quoteLine(sku.id(), 2));
        var quote = quotes().calculate(d.memberId(), lines, List.of());
        var saved = creation().create(d.purchaseSessionId().toString(), d.memberId(), null,
                new com.example.marketplace.order.infrastructure.OrderCreationService.Input(
                        "remainder", lines, List.of(), quote.fingerprint(), d.contact(), null, 0));
        var buyer = authBrowser();
        var member = Map.of("id", d.memberId());
        String email = jdbc.queryForObject("SELECT email FROM marketplace.identity_user WHERE id=:id", member, String.class);
        assertEquals(200, authRequest(buyer, "/api/auth/login", loginForm(email, SIGNUP_PASSWORD), csrfToken(buyer)).statusCode());
        String session = jdbc.queryForObject("SELECT session_id FROM marketplace.spring_session WHERE principal_name=CAST(:id AS text)",
                member, String.class);
        long version = jdbc.queryForObject("SELECT auth_version FROM marketplace.identity_user WHERE id=:id", member, Long.class);
        return new PaymentFixture(saved, buyer, new com.example.marketplace.payment.api.Payments.Authority(session, d.memberId(), version, null));
    }

}
