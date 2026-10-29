package com.example.marketplace.platform.infrastructure;

import com.example.marketplace.platform.api.Outbox;
import com.example.marketplace.platform.api.Jobs;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcOutbox implements Outbox {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Jobs jobs;
    public JdbcOutbox(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,Jobs jobs) { this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.jobs=jobs; }
    @Override public UUID append(Event event) {
        Objects.requireNonNull(event.id());
        return tx.execute(status->{
            UUID job=jobs.enqueue(event.command());var p=Map.of("id",event.id(),"job",job);
            jdbc.update("INSERT INTO marketplace.outbox_event(id,job_id) VALUES(:id,:job) ON CONFLICT DO NOTHING",p);
            UUID saved=jdbc.queryForObject("SELECT job_id FROM marketplace.outbox_event WHERE id=:id FOR UPDATE",p,UUID.class);
            if(!job.equals(saved)) { throw new IllegalArgumentException("EVENT_ID_CONFLICT"); }
            return job;
        });
    }
}
