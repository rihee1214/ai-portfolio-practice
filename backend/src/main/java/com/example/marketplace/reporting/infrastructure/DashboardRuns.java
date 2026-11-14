package com.example.marketplace.reporting.infrastructure;

import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("db")
public class DashboardRuns {
    private final JdbcTemplate jdbc;
    private final Jobs jobs;
    private final Outbox outbox;
    public DashboardRuns(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,Jobs jobs,Outbox outbox) {
        this.jdbc=jdbc.getJdbcTemplate();this.jobs=jobs;this.outbox=outbox;
    }
    @Transactional public long schedule(UUID actor,UUID request,String reason) {
        return schedule(actor,request,reason,null);
    }
    private long schedule(UUID actor,UUID request,String reason,UUID failedJob) {
        var now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        Instant slot=Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(),300)*300);
        var last=jdbc.queryForObject("SELECT last_scheduled FROM marketplace.dashboard_head WHERE singleton FOR UPDATE",Timestamp.class);
        if(request!=null) {
            var old=jdbc.queryForList("SELECT actor_id,reason,run_id FROM marketplace.dashboard_request WHERE request_key=?",request);
            if(!old.isEmpty()) {
                var origins=jdbc.queryForList("SELECT failed_job FROM marketplace.dashboard_failed_followup WHERE request_key=?",UUID.class,request);
                if(!java.util.Objects.equals(failedJob,origins.isEmpty()?null:origins.getFirst())) {
                    throw new com.example.marketplace.order.api.OrderRejected(409);
                }
                if(!old.getFirst().get("actor_id").equals(actor)||!old.getFirst().get("reason").equals(reason)) {
                    throw new com.example.marketplace.order.api.OrderRejected(409);
                }
                return ((Number)old.getFirst().get("run_id")).longValue();
            }
        }else if(last!=null && !last.toInstant().isBefore(slot)) { return 0L; }
        var active=jdbc.queryForList("SELECT id,job_id FROM marketplace.dashboard_run WHERE state='PENDING' ORDER BY id DESC LIMIT 1");
        long id;
        if(!active.isEmpty() && jobs.find((UUID)active.getFirst().get("job_id")).orElseThrow().status().equals("FAILED")) {
            active=java.util.List.of();
        }
        if(active.isEmpty()) {
            var event=UUID.randomUUID();
            String key="dashboard:"+event;
            UUID job=outbox.append(new Outbox.Event(event,new Jobs.Command(DashboardWorker.TYPE,key,key,event.toString(),now)));
            id=jdbc.queryForObject("INSERT INTO marketplace.dashboard_run(job_id,scheduled_at,state) VALUES(?,?,'PENDING') RETURNING id",
                    Long.class,job,Timestamp.from(now));
        }else { id=((Number)active.getFirst().get("id")).longValue(); }
        if(request!=null) {
            jdbc.update("INSERT INTO marketplace.dashboard_request(request_key,actor_id,reason,run_id) VALUES(?,?,?,?)",
                    request,actor,reason,id);
        }
        jdbc.update("UPDATE marketplace.dashboard_head SET last_scheduled=greatest(last_scheduled,?) WHERE singleton",Timestamp.from(slot));
        return id;
    }
    public long followup(UUID actor,UUID failedJob,UUID key,String reason) {
        jdbc.queryForObject("SELECT singleton FROM marketplace.dashboard_head WHERE singleton FOR UPDATE",Boolean.class);
        var failed=jobs.find(failedJob).orElseThrow(()->new com.example.marketplace.order.api.OrderRejected(404));
        if(!DashboardWorker.TYPE.equals(failed.command().type())||!"FAILED".equals(failed.status())) {
            throw new com.example.marketplace.order.api.OrderRejected(409);
        }
        var origin=jdbc.queryForList("SELECT failed_job FROM marketplace.dashboard_failed_followup WHERE request_key=?",UUID.class,key);
        if(!origin.isEmpty()&&!origin.getFirst().equals(failedJob)||origin.isEmpty()
                &&jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace.dashboard_request WHERE request_key=?)",Boolean.class,key)) {
            throw new com.example.marketplace.order.api.OrderRejected(409);
        }
        long run=schedule(actor,key,reason,failedJob);
        if(origin.isEmpty()) {
            jdbc.update("INSERT INTO marketplace.dashboard_failed_followup(request_key,failed_job,next_job) SELECT ?,?,job_id FROM marketplace.dashboard_run WHERE id=?",
                    key,failedJob,run);
        }
        return run;
    }
}
