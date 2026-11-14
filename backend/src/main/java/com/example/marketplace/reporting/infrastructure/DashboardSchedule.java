package com.example.marketplace.reporting.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("db")
@ConditionalOnProperty(name="marketplace.worker.enabled",havingValue="true")
public class DashboardSchedule {
    private final DashboardRuns runs;
    public DashboardSchedule(DashboardRuns runs) { this.runs=runs; }
    @Scheduled(cron="0 */5 * * * *",zone="Asia/Seoul")
    public void refresh() { runs.schedule(null,null,null); }
}
