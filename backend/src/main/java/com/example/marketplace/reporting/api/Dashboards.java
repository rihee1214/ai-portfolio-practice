package com.example.marketplace.reporting.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface Dashboards {
    record Actor(UUID member,String session,Long version) { }
    record Metric(String id,String basis,String unit,String value) { }
    record View(String area,String storeName,LocalDate from,LocalDate to,String status,UUID generation,
                int definitionVersion,Instant sourceAsOf,Instant completedAt,List<Metric> metrics) { }
    View read(Actor actor,String area,LocalDate from,LocalDate to);
    long refresh(Actor actor,UUID key,String reason);
}
