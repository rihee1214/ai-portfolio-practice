package com.example.marketplace.platform.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobInspection {
    record Summary(UUID id,String type,String status,int attempts,Instant scheduledAt,Instant nextRunAt,String error) { }
    List<Summary> list(String status,UUID after);
    Summary find(UUID id);
}
