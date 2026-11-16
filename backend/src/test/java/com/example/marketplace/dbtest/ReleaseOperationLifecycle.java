package com.example.marketplace.dbtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Cancels transient operator processes and joins their request handlers before target cleanup. */
final class ReleaseOperationLifecycle {
    private final Object admission=new Object();
    private final Path evidence;
    private final Map<String,Owned> operations=new ConcurrentHashMap<>();
    private final Map<Thread,Request> requests=new ConcurrentHashMap<>();
    private final java.util.Set<Thread> requestThreads=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newCachedThreadPool(task->{var thread=new Thread(task,"release-observer");thread.setDaemon(true);return thread;});
    private volatile boolean closing;
    private volatile long shutdownDeadline=Long.MAX_VALUE;
    private Path cancellation;
    ReleaseOperationLifecycle(Path evidence) {this.evidence=evidence;}
    Request enterRequest() {
        synchronized(admission) {require(!closing,"Release observer is closing");var request=new Request(Thread.currentThread());require(requests.putIfAbsent(Thread.currentThread(),request)==null,"Nested observer request");requestThreads.add(Thread.currentThread());return request;}
    }
    Owned start(ProcessBuilder builder) throws java.io.IOException {
        synchronized(admission) {require(!closing,"New release operation refused during cleanup");var owned=new Owned(builder.start());operations.put(owned.id,owned);return owned;}
    }
    void stopAccepting() {synchronized(admission) {closing=true;}}
    int activeOperations() {return operations.size();}
    int activeRequests() {return requests.size();}
    java.util.concurrent.ExecutorService executor() {return executor;}
    Path cancellationEvidence() {return cancellation;}
    void cancelAndJoin() throws Exception {
        require(!requests.containsKey(Thread.currentThread()),"Observer cannot join its own request");stopAccepting();Instant started=Instant.now();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);shutdownDeadline=deadline;var owned=new ArrayList<>(operations.values());var handles=new ArrayList<ProcessHandle>();
        for(var operation:owned) {handles.addAll(operation.handles());}
        // Close the processes first; interrupted request loops cannot start another operation.
        handles.forEach(handle->{if(handle.isAlive()) {handle.destroyForcibly();}});
        executor.shutdownNow();requests.keySet().forEach(Thread::interrupt);boolean passed=false;
        try {
            while((!operations.isEmpty()||!requests.isEmpty()||handles.stream().anyMatch(ProcessHandle::isAlive)||!executor.isTerminated()||requestThreads.stream().anyMatch(thread->thread!=Thread.currentThread()&&thread.isAlive()))&&System.nanoTime()<deadline) {Thread.sleep(20);}
            require(operations.isEmpty()&&requests.isEmpty()&&handles.stream().noneMatch(ProcessHandle::isAlive)&&executor.isTerminated()&&requestThreads.stream().noneMatch(thread->thread!=Thread.currentThread()&&thread.isAlive()),"Owned release cancellation/join did not finish within shared ten seconds");passed=true;
        } finally {
            cancellation=evidence.resolve("operation-cancellation-"+UUID.randomUUID()+".json");var record=new LinkedHashMap<String,Object>();record.put("started",started);record.put("ended",Instant.now());record.put("limitMs",10000);record.put("status",passed?"passed":"failed");record.put("activeOperations",operations.size());record.put("activeRequests",requests.size());record.put("ownedProcesses",owned.stream().map(value->Map.of("id",value.id,"pid",value.process.pid(),"alive",value.process.isAlive())).toList());record.put("observedHandles",handles.stream().map(value->Map.of("pid",value.pid(),"alive",value.isAlive())).toList());
            record.put("executorTerminated",executor.isTerminated());record.put("handlerThreads",requestThreads.stream().map(thread->Map.of("id",thread.threadId(),"alive",thread.isAlive())).toList());Files.writeString(cancellation,ReleaseSnapshotContract.JSON.writeValueAsString(record));
        }
    }
    final class Request implements AutoCloseable {
        private final Thread thread;
        private Request(Thread thread) {this.thread=thread;}
        @Override public void close() {requests.remove(thread,this);}
    }
    final class Owned implements AutoCloseable {
        private final String id=UUID.randomUUID().toString();
        private final Process process;
        private final List<ProcessHandle> observed=new ArrayList<>();
        private Owned(Process process) {this.process=process;observed.add(process.toHandle());}
        Process process() {return process;}
        synchronized List<ProcessHandle> handles() {
            if(process.isAlive()) {for(var handle:process.descendants().toList()) {if(observed.stream().noneMatch(old->old.pid()==handle.pid())) {observed.add(handle);}}}
            return List.copyOf(observed);
        }
        @Override public void close() throws Exception {
            var handles=handles();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);handles.forEach(handle->{if(handle.isAlive()) {handle.destroyForcibly();}});
            boolean interrupted=Thread.interrupted();
            try {
                while(handles.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<Math.min(deadline,shutdownDeadline)) {try {Thread.sleep(20);}catch(InterruptedException signal) {interrupted=true;}}
                require(handles.stream().noneMatch(ProcessHandle::isAlive),"Owned operator process or descendant survived request completion");operations.remove(id,this);
            } finally {if(interrupted) {Thread.currentThread().interrupt();}}
        }
    }
    private static void require(boolean value,String message) {if(!value) {throw new AssertionError(message);}}
}
