package com.example.marketplace.reporting.domain;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DashboardPeriodTest {
    @Test void periodLengthDoesNotRestrictHistoricalAccess() {
        var today=LocalDate.of(2026,11,14);
        assertEquals(today.minusDays(29),DashboardPeriod.resolve(null,null,today).from());
        var start=LocalDate.of(2020,1,1);
        assertEquals(start.plusDays(365),new DashboardPeriod(start,start.plusDays(365)).to());
        assertThrows(IllegalArgumentException.class,()->new DashboardPeriod(start,start.plusDays(366)));
        assertThrows(IllegalArgumentException.class,()->new DashboardPeriod(start,start.minusDays(1)));
        assertThrows(IllegalArgumentException.class,()->DashboardPeriod.resolve(start,null,today));
    }
}
