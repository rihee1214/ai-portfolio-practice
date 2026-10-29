package com.example.marketplace.dbtest;
import com.example.marketplace.platform.api.Jobs;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
/** A child fixture claims only its own unique test job type, even with new application handlers installed. */
final class ScopedJobs implements Jobs {
 private final Jobs delegate;private final String type;
 ScopedJobs(Jobs delegate,String type) { this.delegate=delegate;this.type=type; }
 public UUID enqueue(Command command) { return delegate.enqueue(command); }
 public Optional<Job> claim(Set<String> types) { return types.contains(type)?delegate.claim(Set.of(type)):Optional.empty(); }
 public boolean heartbeat(UUID id,UUID owner) { return delegate.heartbeat(id,owner); }
 public boolean complete(UUID id,UUID owner) { return delegate.complete(id,owner); }
 public boolean fail(UUID id,UUID owner,boolean terminal,String error) { return delegate.fail(id,owner,terminal,error); }
 public Optional<Job> find(UUID id) { return delegate.find(id); }
 public List<Job> failed(int limit) { return delegate.failed(limit); }
}
