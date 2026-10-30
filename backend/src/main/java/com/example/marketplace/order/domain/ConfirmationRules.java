package com.example.marketplace.order.domain;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
public final class ConfirmationRules {
 private ConfirmationRules() { }
 public static Instant normalized(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
 public static Instant due(Instant delivered,long seconds) { if(seconds<=0) { throw new IllegalArgumentException("INVALID_DELAY"); }return normalized(delivered).plusSeconds(seconds); }
 public static boolean automatic(Instant now,Instant due) { return !normalized(now).isBefore(due); }
 public static boolean claim(Instant now,Instant due) { return normalized(now).isBefore(due); }
}
