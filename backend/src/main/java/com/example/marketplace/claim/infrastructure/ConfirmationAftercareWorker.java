package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.order.api.AftercareSettlements;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.JobLeases;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Consumes the existing B fact without changing B's original command result. */
@Service @Profile("db")
public final class ConfirmationAftercareWorker {
 private final AftercareSettlements orders;private final Jobs jobs;private final JobLeases leases;private final Outbox outbox;private final TransactionTemplate tx;
 public ConfirmationAftercareWorker(AftercareSettlements orders,Jobs jobs,JobLeases leases,Outbox outbox,PlatformTransactionManager manager) { this.orders=orders;this.jobs=jobs;this.leases=leases;this.outbox=outbox;tx=new TransactionTemplate(manager); }
 private void stored(Jobs.Job job,UUID id) {
  if(job==null||job.ownerToken()==null) { throw new OrderRejected(409); }var actual=jobs.find(job.id()).orElseThrow(()->new OrderRejected(409));var c=actual.command();if(!actual.status().equals("RUNNING")||!actual.ownerToken().equals(job.ownerToken())||!c.equals(job.command())||!c.type().equals("ORDER_CONFIRMATION_RECORDED")||!c.causeKey().equals(id.toString())||!c.payload().equals(id.toString())||!c.dedupeKey().equals("confirmation-recorded:"+id)) { throw new OrderRejected(409); }
 }
 public JobHandler.Result recorded(Jobs.Job job) {
  UUID id;try { id=UUID.fromString(job.command().payload()); }catch(RuntimeException invalid) { throw new OrderRejected(400); }stored(job,id);
  return tx.execute(t->{var fact=orders.lockConfirmation(id);for(UUID seller:fact.units().stream().map(AftercareSettlements.ConfirmationUnit::sellerOrderId).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
    var view=orders.lockAftercare(seller);if(!view.orderId().equals(fact.orderId())) { throw new OrderRejected(409); }String key="seller-aftercare:"+seller+":confirmation:"+id;
    outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),new Jobs.Command(SellerAftercareWorker.TYPE,key,seller.toString(),"CONFIRMATION|"+id,fact.confirmedAt())));
   }stored(job,id);leases.requireOwned(job.id(),job.ownerToken());return JobHandler.Result.COMPLETE;});
 }
}
