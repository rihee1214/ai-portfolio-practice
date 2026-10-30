package com.example.marketplace.platform.infrastructure;

import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.domain.JobRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcJobs implements Jobs,com.example.marketplace.platform.api.JobLeases {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate independent;
    public JdbcJobs(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc=jdbc;this.clock=clock;tx=new TransactionTemplate(manager);independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private static Command validated(Command c) {
        Objects.requireNonNull(c);Objects.requireNonNull(c.nextRunAt());
        if(c.type()==null||c.type().isBlank()||c.type().length()>100||c.dedupeKey()==null||c.dedupeKey().isBlank()||c.dedupeKey().length()>200||c.causeKey()==null||c.causeKey().isBlank()||c.causeKey().length()>200||c.payload()==null||c.payload().length()>65536) { throw new IllegalArgumentException("INVALID_JOB"); }
        return new Command(c.type(),c.dedupeKey(),c.causeKey(),c.payload(),c.nextRunAt().truncatedTo(ChronoUnit.MICROS));
    }
    @Override public UUID enqueue(Command input) {
        var c=validated(input);
        return tx.execute(status->{
            var p=new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("type",c.type()).addValue("key",c.dedupeKey()).addValue("cause",c.causeKey()).addValue("payload",c.payload()).addValue("due",Timestamp.from(c.nextRunAt()));
            jdbc.update("INSERT INTO marketplace.job(id,type,dedupe_key,cause_key,payload,scheduled_at,next_run_at,status,attempts) VALUES(:id,:type,:key,:cause,:payload,:due,:due,'READY',0) ON CONFLICT DO NOTHING",p);
            var stored=jdbc.queryForObject("SELECT * FROM marketplace.job WHERE dedupe_key=:key FOR UPDATE",p,JdbcJobs::row);
            if(!stored.command().equals(c)) { throw new IllegalArgumentException("JOB_KEY_CONFLICT"); }
            return stored.id();
        });
    }
    @Override public Optional<Job> claim(Set<String> types) {
        if(types.isEmpty()) { return Optional.empty(); }
        return independent.execute(status->{
            var now=clock.instant();var p=new MapSqlParameterSource().addValue("types",types).addValue("now",Timestamp.from(now));
            // Exhausted crashes become visible failures; unknown types remain untouched for their owning worker.
            jdbc.update("WITH exhausted AS (SELECT id FROM marketplace.job WHERE type IN (:types) AND status='RUNNING' AND attempts=5 AND lease_until<=:now FOR UPDATE SKIP LOCKED) UPDATE marketplace.job j SET status='FAILED',owner_token=NULL,lease_until=NULL,last_error='LEASE_EXPIRED' FROM exhausted e WHERE j.id=e.id",p);
            p.addValue("token",UUID.randomUUID()).addValue("lease",Timestamp.from(now.plusSeconds(30)));
            var claimed=jdbc.query("WITH candidate AS (SELECT id FROM marketplace.job WHERE type IN (:types) AND attempts<5 AND ((status='READY' AND next_run_at<=:now) OR (status='RUNNING' AND lease_until<=:now)) ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE marketplace.job j SET status='RUNNING',attempts=j.attempts+1,owner_token=:token,lease_until=:lease FROM candidate c WHERE j.id=c.id RETURNING j.*",p,JdbcJobs::row);
            return claimed.stream().findFirst();
        });
    }
    @Override public void requireOwned(UUID id,UUID token) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
        var rows=jdbc.query("SELECT * FROM marketplace.job WHERE id=:id FOR UPDATE",Map.of("id",id),JdbcJobs::row);var now=clock.instant();
        if(rows.size()!=1||!rows.getFirst().status().equals("RUNNING")||!Objects.equals(token,rows.getFirst().ownerToken())||!now.isBefore(rows.getFirst().leaseUntil())) { throw new IllegalStateException("JOB_LEASE_LOST"); }
    }
    private boolean owned(UUID id,UUID token,BiFunction<Job,Instant,Boolean> action) {
        return independent.execute(status->{
            var rows=jdbc.query("SELECT * FROM marketplace.job WHERE id=:id FOR UPDATE",Map.of("id",id),JdbcJobs::row);
            if(rows.isEmpty()) { return false; }
            var job=rows.getFirst();var now=clock.instant(); // Evaluate only AFTER any row-lock wait.
            if(!"RUNNING".equals(job.status())||!Objects.equals(token,job.ownerToken())||!now.isBefore(job.leaseUntil())) { return false; }
            return action.apply(job,now);
        });
    }
    @Override public boolean heartbeat(UUID id,UUID token) {
        return owned(id,token,(job,now)->jdbc.update("UPDATE marketplace.job SET lease_until=:lease WHERE id=:id AND owner_token=:token",Map.of("id",id,"token",token,"lease",Timestamp.from(now.plusSeconds(30))))==1);
    }
    @Override public boolean complete(UUID id,UUID token) {
        return owned(id,token,(job,now)->jdbc.update("UPDATE marketplace.job SET status='SUCCEEDED',owner_token=NULL,lease_until=NULL,last_error=NULL WHERE id=:id AND owner_token=:token",Map.of("id",id,"token",token))==1);
    }
    @Override public boolean fail(UUID id,UUID token,boolean terminal,String error) {
        if(error==null||!error.matches("[A-Z0-9_]{1,64}")) { throw new IllegalArgumentException("INVALID_ERROR_CODE"); }
        return owned(id,token,(job,now)->{
            boolean exhausted=terminal||JobRules.terminal(job.attempts());
            var due=exhausted?now:now.plus(JobRules.retryDelay(job.attempts()));
            return jdbc.update("UPDATE marketplace.job SET status=:status,next_run_at=:due,owner_token=NULL,lease_until=NULL,last_error=:error WHERE id=:id AND owner_token=:token",Map.of("id",id,"token",token,"status",exhausted?"FAILED":"READY","due",Timestamp.from(due),"error",error))==1;
        });
    }
    @Override public Optional<Job> find(UUID id) { return jdbc.query("SELECT * FROM marketplace.job WHERE id=:id",Map.of("id",id),JdbcJobs::row).stream().findFirst(); }
    @Override public List<Job> failed(int limit) {
        if(limit<1||limit>1000) { throw new IllegalArgumentException("INVALID_LIMIT"); }
        return jdbc.query("SELECT * FROM marketplace.job WHERE status='FAILED' ORDER BY next_run_at,id LIMIT :limit",Map.of("limit",limit),JdbcJobs::row);
    }
    private static Job row(ResultSet rs,int index) throws SQLException {
        var c=new Command(rs.getString("type"),rs.getString("dedupe_key"),rs.getString("cause_key"),rs.getString("payload"),rs.getTimestamp("scheduled_at").toInstant());
        var lease=rs.getTimestamp("lease_until");return new Job(rs.getObject("id",UUID.class),c,rs.getString("status"),rs.getInt("attempts"),rs.getTimestamp("next_run_at").toInstant(),rs.getObject("owner_token",UUID.class),lease==null?null:lease.toInstant(),rs.getString("last_error"));
    }
}
