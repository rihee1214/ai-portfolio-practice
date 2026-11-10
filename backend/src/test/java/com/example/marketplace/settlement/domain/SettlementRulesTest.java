package com.example.marketplace.settlement.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementRulesTest {
    @Test void allocatesOneRoundedCommissionAndRestoresOnlyOriginalUnit() {
        var fee=SettlementRules.commissions(List.of(10019L,10019L));
        assertEquals(List.of(501L,500L),fee);
        assertEquals(1001L,fee.stream().mapToLong(Long::longValue).sum());
        assertEquals(9519L,10019L-fee.get(1));
    }
    @Test void zeroAndSmallUnitsPreserveRoundedAggregate() {
        assertEquals(List.of(0L,0L),SettlementRules.commissions(List.of(0L,0L)));
        assertEquals(List.of(1L,0L),SettlementRules.commissions(List.of(19L,1L)));
        assertThrows(IllegalArgumentException.class,()->SettlementRules.commissions(List.of(-1L)));
    }
    @Test void seoulMondayIsInclusiveAndNextMondayExclusive() {
        var week=LocalDate.of(2026,11,9);
        assertEquals(Instant.parse("2026-11-08T15:00:00Z"),SettlementRules.start(week));
        assertEquals(week,SettlementRules.week(Instant.parse("2026-11-15T14:59:59Z")));
        assertEquals(week.plusWeeks(1),SettlementRules.week(Instant.parse("2026-11-15T15:00:00Z")));
        assertThrows(IllegalArgumentException.class,()->SettlementRules.start(week.plusDays(1)));
    }
}
