package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Called only after the parent/payment/attempt locks. A consumed slot never performs HTTP twice. */
final class RefundQuerySchedule {
 static final String TYPE="REFUND_QUERY_DUE.v1";
 private static final long[] OFFSETS={60,300,1800,7200,43200,86400};
 private final NamedParameterJdbcTemplate jdbc;private final Outbox outbox;private final JobLeases leases;
 RefundQuerySchedule(NamedParameterJdbcTemplate jdbc,Outbox outbox,JobLeases leases){this.jdbc=jdbc;this.outbox=outbox;this.leases=leases;}
 void initialize(UUID attempt){
  var p=Map.of("id",attempt);var a=jdbc.queryForMap("SELECT amount,started_at FROM marketplace.payment_refund_attempt WHERE id=:id",p);
  if(((Number)a.get("amount")).longValue()==0||jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_policy WHERE attempt_id=:id",p,Integer.class)>0){return;}
  Instant start=((Timestamp)a.get("started_at")).toInstant();jdbc.update("INSERT INTO marketplace.payment_refund_query_policy(attempt_id,prepared_at,deadline) VALUES(:id,:at,:deadline)",Map.of("id",attempt,"at",Timestamp.from(start),"deadline",Timestamp.from(start.plusSeconds(86400))));
  for(int n=1;n<=OFFSETS.length;n++){String key="refund-auto:"+attempt+":"+n;Instant due=start.plusSeconds(OFFSETS[n-1]);UUID job=outbox.append(new Outbox.Event(named(key),new Jobs.Command(TYPE,key,attempt.toString(),attempt+"|"+n,due)));jdbc.update("INSERT INTO marketplace.payment_refund_query_slot(attempt_id,slot,due_at,job_id) VALUES(:id,:slot,:due,:job)",Map.of("id",attempt,"slot",n,"due",Timestamp.from(due),"job",job));}
 }
 boolean enter(UUID attempt,int slot,Jobs.Job job,String state,Clock clock){
  var rows=jdbc.queryForList("SELECT s.*,p.deadline FROM marketplace.payment_refund_query_slot s JOIN marketplace.payment_refund_query_policy p USING(attempt_id) WHERE s.attempt_id=:id AND s.slot=:slot",Map.of("id",attempt,"slot",slot));
  if(rows.size()!=1||!job.id().equals(rows.getFirst().get("job_id"))){throw new OrderRejected(409);}var saved=rows.getFirst();var fact=leases.requireDueClaim(job.id(),job.ownerToken());Instant due=((Timestamp)saved.get("due_at")).toInstant();
  if(!fact.scheduledAt().equals(due)||!job.command().dedupeKey().equals("refund-auto:"+attempt+":"+slot)){throw new OrderRejected(409);}
  if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND slot=:slot",Map.of("id",attempt,"slot",slot),Integer.class)>0){return false;}
  Instant consumed=clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant();
  String decision="CALL";
  if(!java.util.List.of("PROCESSING","UNKNOWN").contains(state)){decision="SKIP_TERMINAL";}
  else if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND decision='CALL' AND consumption_basis='CLAIM_ONLY_LEGACY'",Map.of("id",attempt),Integer.class)>0){decision="REVIEW";}
  else if(slot==6||!consumed.isBefore(((Timestamp)saved.get("deadline")).toInstant())||!fact.claimedAt().isBefore(((Timestamp)saved.get("deadline")).toInstant())){decision="REVIEW";}
  else if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND decision='CALL' AND consumed_at>=:due",Map.of("id",attempt,"due",Timestamp.from(due)),Integer.class)>0){decision="SKIP_DELAYED";}
  else if(jdbc.queryForObject("SELECT count(*) FROM marketplace.payment_refund_query_consumption WHERE attempt_id=:id AND decision='CALL'",Map.of("id",attempt),Integer.class)>=5){decision="REVIEW";}
  jdbc.update("INSERT INTO marketplace.payment_refund_query_consumption(attempt_id,slot,job_id,owner_token,decision,checked_at,consumed_at,consumption_basis) VALUES(:id,:slot,:job,:token,:decision,:at,:consumed,'AFTER_LOCK_READ')",Map.of("id",attempt,"slot",slot,"job",job.id(),"token",job.ownerToken(),"decision",decision,"at",Timestamp.from(fact.claimedAt()),"consumed",Timestamp.from(consumed)));
  return decision.equals("CALL");
 }
 static UUID named(String value){return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
