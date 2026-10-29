package com.example.marketplace.platform.infrastructure;

import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.JobHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A single handler at a time, with an independent heartbeat thread. Delivery is at least once. */
public final class WorkerEngine implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(WorkerEngine.class);
    private final Jobs jobs;
    private final Map<String,JobHandler> handlers;
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean started=new AtomicBoolean();
    private final AtomicBoolean active=new AtomicBoolean();
    public WorkerEngine(Jobs jobs,List<JobHandler> registrations) {
        this.jobs=jobs;var map=new HashMap<String,JobHandler>();
        for(var handler:registrations) {
            if(handler.type()==null||handler.type().isBlank()||map.putIfAbsent(handler.type(),handler)!=null) { throw new IllegalArgumentException("DUPLICATE_OR_INVALID_JOB_HANDLER"); }
        }
        handlers=Map.copyOf(map);
    }
    public boolean runOnce() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Worker must run outside caller transaction"); }
        if(!active.compareAndSet(false,true)) { return false; }
        try {
            var claimed=jobs.claim(handlers.keySet());if(claimed.isEmpty()) { return false; }
            var job=claimed.get();var leaseAlive=new AtomicBoolean(true);
            var heartbeat=heartbeats.scheduleAtFixedRate(()->{
                try { if(!jobs.heartbeat(job.id(),job.ownerToken())) { leaseAlive.set(false); } }
                catch(RuntimeException failure) { leaseAlive.set(false);LOG.warn("Job heartbeat unavailable; ownership will expire"); }
            },10,10,TimeUnit.SECONDS);
            try {
                var result=handlers.get(job.command().type()).handle(job);
                if(leaseAlive.get()) {
                    switch(result) {
                        case COMPLETE -> jobs.complete(job.id(),job.ownerToken());
                        case RETRY -> jobs.fail(job.id(),job.ownerToken(),false,"HANDLER_RETRY");
                        case TERMINAL -> jobs.fail(job.id(),job.ownerToken(),true,"HANDLER_TERMINAL");
                    }
                }
            } catch(Exception failure) {
                if(failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                if(leaseAlive.get()) { jobs.fail(job.id(),job.ownerToken(),false,"HANDLER_EXCEPTION"); }
            } finally { heartbeat.cancel(false); }
            return true;
        } finally { active.set(false); }
    }
    public void start() {
        if(started.compareAndSet(false,true)) {
            poller.scheduleWithFixedDelay(()->{
                try { runOnce(); } catch(RuntimeException failure) { LOG.warn("Job polling unavailable; durable jobs will be retried"); }
            },0,250,TimeUnit.MILLISECONDS);
        }
    }
    @Override public void close() { poller.shutdownNow();heartbeats.shutdownNow(); }
}
