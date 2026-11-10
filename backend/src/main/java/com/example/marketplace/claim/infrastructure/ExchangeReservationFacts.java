package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Claim-owned immutable approval and due deliveries; inventory owns the actual allocation. */
final class ExchangeReservationFacts {
 static final String TYPE="EXCHANGE_RESERVATION_DUE.v1";
 private ExchangeReservationFacts() { }
 static void approval(NamedParameterJdbcTemplate jdbc,Outbox outbox,UUID id,int sequence,String key,long originalRevision,long revision,Instant at,Instant expires,String receipt) {
  var p=new MapSqlParameterSource("id",id).addValue("seq",sequence).addValue("key",key).addValue("original",originalRevision).addValue("revision",revision).addValue("at",Timestamp.from(at)).addValue("expires",Timestamp.from(expires)).addValue("receipt",receipt);
  jdbc.update("INSERT INTO marketplace.claim_exchange_approval_generation(exchange_id,sequence,command_key,original_revision,revision,approved_at,expires_at,receipt) VALUES(:id,:seq,:key,:original,:revision,:at,:expires,CAST(:receipt AS jsonb))",p);schedule(jdbc,outbox,id,sequence,1,expires);
 }
 static void schedule(NamedParameterJdbcTemplate jdbc,Outbox outbox,UUID id,int sequence,int epoch,Instant due) {
  String key="exchange-reservation-due:"+id+":"+sequence+":"+epoch;UUID event=UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));UUID job=outbox.append(new Outbox.Event(event,new Jobs.Command(TYPE,key,id.toString(),id+"|"+sequence+"|"+epoch,due)));
  jdbc.update("INSERT INTO marketplace.claim_exchange_reservation_schedule(exchange_id,sequence,epoch,due_at,job_id,outbox_id) VALUES(:id,:seq,:epoch,:due,:job,:event)",new MapSqlParameterSource("id",id).addValue("seq",sequence).addValue("epoch",epoch).addValue("due",Timestamp.from(due)).addValue("job",job).addValue("event",event));
 }
}
