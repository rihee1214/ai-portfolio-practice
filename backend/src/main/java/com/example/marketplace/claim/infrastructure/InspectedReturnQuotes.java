package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.claim.api.FinancialIntents;
import com.example.marketplace.order.api.ReturnApplications;
import com.example.marketplace.order.api.ReturnSources;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Typed accepted/inspected source; the OPEN common fee quote retains its original selection contract. */
@Service @Profile("db")
public final class InspectedReturnQuotes {
 record Source(ReturnSources.Selection selection,List<ReturnFees.Target> targets,ReturnFeeQuoting.Inspection inspection) { }
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final ReturnApplications applications;
 private final OrderConfirmations confirmations;private final FinancialIntents intents;private final Orders orders;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public InspectedReturnQuotes(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ReturnApplications applications,OrderConfirmations confirmations,FinancialIntents intents,Orders orders,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.queries=queries;this.applications=applications;this.confirmations=confirmations;this.intents=intents;this.orders=orders;this.tx=new TransactionTemplate(manager);
 }
 public ReturnFees.Quote quote(String number,OrderQueries.Authority authority,UUID id,long expectedRevision) {
  if(id==null||expectedRevision<0) { throw new OrderRejected(400); }
  try { return tx.execute(t->{var source=lock(number,authority,id,expectedRevision);var quote=ReturnFeeQuoting.write(jdbc,orders,number,source.selection(),source.targets(),source.inspection());queries.lockOwned(number,authority);return quote;}); }
  catch(ArithmeticException overflow) { throw new OrderRejected(400); }
 }
 Source lock(String number,OrderQueries.Authority authority,UUID id,long expectedRevision) {
  var own=queries.lockOwned(number,authority);var accepted=applications.lockOwner(number,authority,id);var row=jdbc.queryForMap("SELECT * FROM marketplace.claim_return_request WHERE id=:id FOR UPDATE",Map.of("id",id));var intent=intents.lock(id);
  if(!own.id().equals(accepted.orderId())||!id.equals(row.get("id"))||!accepted.orderId().equals(row.get("order_id"))||!accepted.sellerOrderId().equals(row.get("seller_order_id"))||!accepted.holdId().equals(row.get("hold_id"))||!accepted.causeId().equals(row.get("cause_id"))||!row.get("state").equals("AWAITING_CONSENT")||((Number)row.get("version")).longValue()!=expectedRevision) { throw new OrderRejected(409); }
  var expected=accepted.source().units().stream().map(u->new OrderConfirmations.Unit(u.itemId(),u.ordinal())).sorted(java.util.Comparator.comparing((OrderConfirmations.Unit u)->u.itemId().toString()).thenComparingInt(OrderConfirmations.Unit::ordinal)).toList();
  if(!intent.kind().equals("RETURN")||!intent.orderId().equals(accepted.orderId())||!intent.sellerOrderId().equals(accepted.sellerOrderId())||!intent.holdId().equals(accepted.holdId())||!intent.causeId().equals(accepted.causeId())||!intent.units().equals(expected)) { throw new OrderRejected(409); }
  var facts=ReturnPhysicalFacts.read(jdbc,id);if(facts.size()!=expected.size()||facts.stream().anyMatch(u->!u.collection().equals("RECEIVED")||!List.of("SALEABLE","DEFECTIVE").contains(u.inspection())||u.responsibility()==null||u.inspectionId()==null||u.inspection().equals("SALEABLE")&&u.restitutionId()==null)) { throw new OrderRejected(409); }
  var targets=facts.stream().map(u->new ReturnFees.Target(u.itemId(),u.ordinal(),ReturnFees.Responsibility.valueOf(u.responsibility().name()))).toList();String snapshot=mapper.writeValueAsString(facts);
  var source=new ReturnSources.Selection(accepted.orderId(),accepted.sellerOrderId(),accepted.source().checkoutId(),accepted.source().mode(),confirmations.get(number,authority).revision(),accepted.source().units());
  return new Source(source,targets,new ReturnFeeQuoting.Inspection(id,expectedRevision,ReturnFeeQuoting.hash(snapshot),snapshot));
 }
}
