package com.example.marketplace.seller.api;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Summaries contain only scope keys and measurements from the caller's current transaction. */
public interface DashboardSellers {
    record Fact(String scope,UUID owner,LocalDate day,String metric,BigInteger amount) { }
    List<Fact> measure();
}
