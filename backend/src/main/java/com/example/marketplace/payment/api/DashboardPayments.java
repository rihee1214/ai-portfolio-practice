package com.example.marketplace.payment.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DashboardPayments {
    record Charge(UUID orderId, LocalDate day, long amount) { }
    record Refund(UUID sellerOrderId, String purpose, LocalDate day, long amount) { }
    record Pending(UUID orderId, String state) { }
    List<Charge> charges();
    List<Refund> refunds();
    List<Pending> unresolved();
}
