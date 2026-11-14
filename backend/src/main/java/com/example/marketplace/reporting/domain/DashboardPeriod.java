package com.example.marketplace.reporting.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record DashboardPeriod(LocalDate from, LocalDate to) {
    public DashboardPeriod {
        if (from==null || to==null || from.isAfter(to) || ChronoUnit.DAYS.between(from,to)>=366) {
            throw new IllegalArgumentException("DASHBOARD_PERIOD");
        }
    }
    public static DashboardPeriod resolve(LocalDate from,LocalDate to,LocalDate today) {
        if (from==null && to==null) { return new DashboardPeriod(today.minusDays(29),today); }
        return new DashboardPeriod(from,to);
    }
}
