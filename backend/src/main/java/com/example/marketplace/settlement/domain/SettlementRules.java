package com.example.marketplace.settlement.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SettlementRules {
    public static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    private SettlementRules() { }
    public static List<Long> commissions(List<Long> units) {
        long total=0,allocated=0;
        var result=new ArrayList<Long>();var indices=new ArrayList<Integer>();
        for(int i=0;i<units.size();i++) {
            long amount=units.get(i);if(amount<0) { throw new IllegalArgumentException("NEGATIVE_NET"); }
            total=Math.addExact(total,amount);long fee=amount/20;allocated=Math.addExact(allocated,fee);
            result.add(fee);indices.add(i);
        }
        indices.sort(Comparator.<Integer>comparingLong(i->units.get(i)%20).reversed().thenComparingInt(i->i));
        long remainder=total/20-allocated;
        for(int i=0;i<remainder;i++) { int index=indices.get(i);result.set(index,result.get(index)+1); }
        return List.copyOf(result);
    }
    public static LocalDate week(Instant at) { return at.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    public static Instant start(LocalDate date) {
        if(date==null||date.getDayOfWeek()!=DayOfWeek.MONDAY) { throw new IllegalArgumentException("MONDAY_REQUIRED"); }
        return date.atStartOfDay(ZONE).toInstant();
    }
}
