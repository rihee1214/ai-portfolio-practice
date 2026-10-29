package com.example.marketplace.payment.domain;
import java.time.Instant;
public final class PaymentRules {
 private PaymentRules() { }
 public static boolean uncertain(String state) { return "PROCESSING".equals(state)||"UNKNOWN".equals(state)||"CREATED".equals(state); }
 public static boolean review(int queries,Instant started,Instant now) { return queries>=5||!now.isBefore(started.plusSeconds(900)); }
}
