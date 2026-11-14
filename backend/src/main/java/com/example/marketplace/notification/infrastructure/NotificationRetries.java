package com.example.marketplace.notification.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.notification.api.NotificationAccess.Reader;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service @Profile("db")
public class NotificationRetries {
    public record Command(UUID key,UUID failedJob,String reason) { }
    public record Failure(UUID event,UUID job) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final Outbox outbox;
    private final Jobs jobs;
    private final HistorySessions sessions;
    private final AccessPolicy access;
    public NotificationRetries(NamedParameterJdbcTemplate jdbc,Outbox outbox,Jobs jobs,HistorySessions sessions,AccessPolicy access) {
        this.jdbc=jdbc;this.outbox=outbox;this.jobs=jobs;this.sessions=sessions;this.access=access;
    }
    private void authorize(Reader reader) {
        if(reader.member()==null || !sessions.lockMemberThenSession(reader.session(),reader.member(),reader.version())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if(!access.allowed(reader.member(),AccessArea.SYSTEM)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
    private static final String CURRENT="""
            WITH deliveries AS (SELECT e.id event,o.job_id job FROM marketplace.notification_event e
                JOIN marketplace.outbox_event o ON o.id=e.id UNION ALL SELECT event_id,job_id FROM marketplace.notification_retry)
            SELECT d.event,d.job FROM deliveries d JOIN marketplace.job j ON j.id=d.job
            WHERE j.status='FAILED' AND NOT EXISTS(SELECT 1 FROM marketplace.notification_retry r WHERE r.previous_job=d.job)
            """;
    @Transactional public List<Failure> failed(Reader reader) {
        authorize(reader);
        return jdbc.query(CURRENT+" ORDER BY d.event LIMIT 100",Map.of(),(r,n)->new Failure(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));
    }
    @Transactional public UUID retry(UUID event,Command command,Reader reader) {
        authorize(reader);
        if(command.key()==null || command.failedJob()==null || command.reason()==null || command.reason().isBlank() || command.reason().length()>500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var params=Map.of("event",event,"old",command.failedJob(),"actor",reader.member(),"key",command.key(),"reason",command.reason().strip());
        if(jdbc.queryForList("SELECT id FROM marketplace.notification_event WHERE id=:event FOR UPDATE",params,UUID.class).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        authorize(reader);
        var prior=jdbc.queryForList("SELECT * FROM marketplace.notification_retry WHERE operator_id=:actor AND request_key=:key",params);
        if(!prior.isEmpty()) {
            var r=prior.getFirst();
            if(!event.equals(r.get("event_id")) || !command.failedJob().equals(r.get("previous_job")) || !params.get("reason").equals(r.get("reason"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            return (UUID)r.get("job_id");
        }
        if(jdbc.queryForList(CURRENT+" AND d.event=:event AND d.job=:old",params).isEmpty()
                || !jobs.find(command.failedJob()).orElseThrow().command().type().equals("NOTIFICATION_DELIVER.v1")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        UUID round=UUID.randomUUID();
        var now=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant();
        UUID job=outbox.append(new Outbox.Event(round,new Jobs.Command("NOTIFICATION_DELIVER.v1","notify-retry:"+round,event.toString(),event.toString(),now)));
        jdbc.update("""
                INSERT INTO marketplace.notification_retry(id,event_id,previous_job,job_id,operator_id,request_key,reason)
                VALUES(:id,:event,:old,:job,:actor,:key,:reason)
                """,new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(params).addValue("id",round).addValue("job",job));
        return job;
    }
}
