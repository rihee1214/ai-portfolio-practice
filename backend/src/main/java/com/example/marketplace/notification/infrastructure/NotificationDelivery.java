package com.example.marketplace.notification.infrastructure;

import com.example.marketplace.notification.api.NotificationEvents;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class NotificationDelivery implements NotificationEvents, JobHandler {
    private static final String TYPE="NOTIFICATION_DELIVER.v1";
    private final NamedParameterJdbcTemplate jdbc;
    private final Outbox outbox;
    private final JobLeases leases;

    public NotificationDelivery(NamedParameterJdbcTemplate jdbc,Outbox outbox,JobLeases leases) {
        this.jdbc=jdbc;
        this.outbox=outbox;
        this.leases=leases;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(Kind kind,UUID cause,UUID recipient,UUID target) {
        var key=Map.of("kind",kind.name(),"cause",cause,"recipient",recipient,"target",target,"id",UUID.randomUUID());
        jdbc.update("""
                INSERT INTO marketplace.notification_event(id,kind,cause_id,recipient_id,target_id)
                VALUES(:id,:kind,:cause,:recipient,:target) ON CONFLICT(kind,cause_id,recipient_id) DO NOTHING
                """,key);
        var saved=jdbc.queryForMap("""
                SELECT id,target_id,created_at FROM marketplace.notification_event
                WHERE kind=:kind AND cause_id=:cause AND recipient_id=:recipient
                """,key);
        if(!saved.get("target_id").equals(target)) { throw new IllegalStateException("NOTIFICATION_TARGET_CONFLICT"); }
        UUID event=(UUID)saved.get("id");
        var at=((Timestamp)saved.get("created_at")).toInstant();
        outbox.append(new Outbox.Event(event,new Jobs.Command(TYPE,"notify:"+event,event.toString(),event.toString(),at)));
    }

    @Override public String type() { return TYPE; }

    @Override
    @Transactional
    public Result handle(Jobs.Job job) {
        UUID event=UUID.fromString(job.command().payload());
        var key=Map.of("event",event,"job",job.id());
        if(!TYPE.equals(job.command().type()) || !event.toString().equals(job.command().causeKey())
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM marketplace.outbox_event o WHERE o.job_id=:job
                        AND ((o.id=:event AND :dedupe='notify:'||CAST(o.id AS text)) OR
                        (:dedupe='notify-retry:'||CAST(o.id AS text) AND EXISTS(SELECT 1 FROM marketplace.notification_retry r
                         WHERE r.id=o.id AND r.job_id=o.job_id AND r.event_id=:event))))
                    """,new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(key).addValue("dedupe",job.command().dedupeKey()),Boolean.class))) { return Result.TERMINAL; }
        jdbc.update("INSERT INTO marketplace.notification_inbox(event_id) VALUES(:event) ON CONFLICT DO NOTHING",key);
        leases.requireOwned(job.id(),job.ownerToken());
        return Result.COMPLETE;
    }
}
