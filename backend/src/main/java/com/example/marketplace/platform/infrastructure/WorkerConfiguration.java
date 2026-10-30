package com.example.marketplace.platform.infrastructure;

import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import com.example.marketplace.platform.api.JobHandler;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class WorkerConfiguration {
    @Bean JdbcJobs jobs(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) { return new JdbcJobs(jdbc,manager,Clock.systemUTC()); }
    @Bean Outbox outbox(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,Jobs jobs) { return new JdbcOutbox(jdbc,manager,jobs); }
    @Bean(initMethod="start",destroyMethod="close") @ConditionalOnProperty(name="marketplace.worker.enabled",havingValue="true")
    WorkerEngine workerEngine(Jobs jobs,List<JobHandler> handlers) { return new WorkerEngine(jobs,handlers); }
}
