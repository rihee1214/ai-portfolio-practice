package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.Returns;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.claim.domain.ReturnRules;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("db")
public final class JdbcReturns implements Returns {
 private final OrderQueries queries;private final OrderConfirmations confirmations;private final ReturnApplications applications;
 private final FinancialIntents intents;private final NamedParameterJdbcTemplate jdbc;private final Outbox outbox;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 private final ReturnCommands commands;
 public JdbcReturns(OrderQueries queries,OrderConfirmations confirmations,ReturnApplications applications,FinancialIntents intents,
                    NamedParameterJdbcTemplate jdbc,Outbox outbox,PlatformTransactionManager manager,ReturnCommands commands) {
  this.queries=queries;this.confirmations=confirmations;this.applications=applications;this.intents=intents;this.jdbc=jdbc;this.outbox=outbox;this.tx=new TransactionTemplate(manager);
  this.commands=commands;
 }
 @Override public Progress get(String number,UUID id,OrderQueries.Authority authority) { return commands.get(number,id,authority); }
 @Override public Progress approve(UUID id,OrderQueries.Authority authority,Command command) { return commands.execute(null,id,authority,command,"APPROVED"); }
 @Override public Progress reject(UUID id,OrderQueries.Authority authority,Command command) { return commands.execute(null,id,authority,command,"REJECTED"); }
 @Override public Progress withdraw(String number,UUID id,OrderQueries.Authority authority,Command command) { return commands.execute(number,id,authority,command,"WITHDRAWN"); }
 @Override public Receipt request(String number,OrderQueries.Authority authority,Request command) {
  if(command==null||command.key()==null||!command.key().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")||command.expectedRevision()==null||command.expectedRevision()<0||command.reason()==null||command.reason().isBlank()||command.reason().length()>500) { throw new OrderRejected(400); }
  java.util.List<Unit> units;try { units=ReturnRules.canonical(command.units()); }catch(IllegalArgumentException invalid) { throw new OrderRejected(400); }
  String reason=command.reason().strip();
  return tx.execute(status->{
   var own=queries.lockOwned(number,authority);String fingerprint=hash(own.id()+"|"+command.expectedRevision()+"|"+units+"|"+reason);
   var prior=jdbc.queryForList("SELECT request_fingerprint,receipt::text FROM marketplace.claim_return_request WHERE order_id=:order AND request_key=:key FOR UPDATE",Map.of("order",own.id(),"key",command.key()));
   if(!prior.isEmpty()) { if(prior.size()!=1||!fingerprint.equals(prior.getFirst().get("request_fingerprint"))) { throw new OrderRejected(409); }queries.lockOwned(number,authority);return mapper.readValue(prior.getFirst().get("receipt").toString(),Receipt.class); }
   if(!own.state().equals("PAID")||confirmations.get(number,authority).revision()!=command.expectedRevision()) { throw new OrderRejected(409); }
   UUID id=UUID.randomUUID(),hold=UUID.randomUUID(),cause=UUID.randomUUID();var selected=units.stream().map(u->new OrderConfirmations.Unit(u.itemId(),u.ordinal())).toList();
   var application=applications.begin(number,authority,id,hold,cause,selected);
   if(application.source().units().stream().anyMatch(u->!ReturnRules.beforeVerifiedDeliveryDeadline(u.physical()==null?u.deliveredAt():u.physical().delivery().deliveredAt(),application.requestedAt(),application.windowSeconds()))) { throw new OrderRejected(409); }
   intents.registerReturn(number,authority,id,hold,cause,selected);
   var receipt=new Receipt(id,hold,id,"REQUESTED",0,application.source().revision(),application.requestedAt(),reason,units);
   var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("order",own.id()).addValue("seller",application.sellerOrderId()).addValue("hold",hold).addValue("cause",cause).addValue("key",command.key()).addValue("hash",fingerprint).addValue("reason",reason).addValue("at",Timestamp.from(application.requestedAt())).addValue("receipt",mapper.writeValueAsString(receipt));
   jdbc.update("INSERT INTO marketplace.claim_return_request(id,order_id,seller_order_id,hold_id,cause_id,request_key,request_fingerprint,reason,state,requested_at,receipt) VALUES(:id,:order,:seller,:hold,:cause,:key,:hash,:reason,'REQUESTED',:at,CAST(:receipt AS jsonb))",p);
   for(var unit:units) { jdbc.update("INSERT INTO marketplace.claim_return_unit(return_id,hold_id,order_id,seller_order_id,item_id,ordinal,claimed_responsibility) VALUES(:id,:hold,:order,:seller,:item,:ordinal,:responsibility)",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("hold",hold).addValue("order",own.id()).addValue("seller",application.sellerOrderId()).addValue("item",unit.itemId()).addValue("ordinal",unit.ordinal()).addValue("responsibility",unit.responsibility().name())); }
   for(var source:application.source().units()) { if(source.physical()!=null) { jdbc.update("INSERT INTO marketplace.claim_return_physical_source(return_id,item_id,ordinal,physical_id,source_snapshot) VALUES(:id,:item,:ordinal,:physical,CAST(:snapshot AS jsonb))",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",id).addValue("item",source.itemId()).addValue("ordinal",source.ordinal()).addValue("physical",source.physical().id()).addValue("snapshot",mapper.writeValueAsString(source.physical()))); } }
   String actor=authority.member()==null?"GAS":"MEMBER:"+authority.member();
   jdbc.update("INSERT INTO marketplace.claim_return_history(id,return_id,action,version,actor,reason,occurred_at) VALUES(:event,:id,'REQUESTED',0,:actor,:reason,:at)",p.addValue("event",UUID.randomUUID()).addValue("actor",actor));
   String key="return-requested:"+id;outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command("RETURN_RECORDED.v1",key,id.toString(),id.toString(),application.requestedAt())));
   queries.lockOwned(number,authority);return receipt;
  });
 }
 private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
