package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.claim.api.ReturnFees;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.api.ReturnSources;
import com.example.marketplace.inventory.api.ExchangeReservations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A real EXCHANGE inspection is financial evidence for conversion; no RETURN workflow is created. */
@Service @Profile("db")
public final class InspectedExchangeRefundQuotes {
 record Source(ExchangeApplications.Source application,ReturnSources.Selection selection,List<ReturnFees.Target> targets,ReturnFeeQuoting.ExchangeInspection inspection) { }
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final ExchangeApplications applications;
 private final JdbcExchanges exchanges;private final ExchangeReservations reservations;private final Orders orders;private final TransactionTemplate tx;
 private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public InspectedExchangeRefundQuotes(NamedParameterJdbcTemplate jdbc,OrderQueries queries,ExchangeApplications applications,JdbcExchanges exchanges,ExchangeReservations reservations,Orders orders,PlatformTransactionManager manager) {
  this.jdbc=jdbc;this.queries=queries;this.applications=applications;this.exchanges=exchanges;this.reservations=reservations;this.orders=orders;this.tx=new TransactionTemplate(manager);
 }
 public ReturnFees.Quote quote(String number,OrderQueries.Authority authority,UUID id,long revision) {
  if(id==null||revision<0) { throw new OrderRejected(400); }
  try { return tx.execute(t->{var source=lock(number,authority,id,revision);var quote=ReturnFeeQuoting.write(jdbc,orders,number,source.selection(),source.targets(),source.inspection());queries.lockOwned(number,authority);return quote;}); }
  catch(ArithmeticException invalid) { throw new OrderRejected(400); }
 }
 Source lock(String number,OrderQueries.Authority authority,UUID id,long revision) {
  var application=applications.lockOwner(number,authority,id);var selection=applications.lockRefundSource(number,authority,id);var row=exchanges.lock(id);
  if(((Number)row.get("version")).longValue()!=revision||!List.of("REQUESTED","RESPONSIBILITY_PENDING","APPROVED","COLLECTING","READY","REVIEW_REQUIRED","DISPATCH_PENDING","EXPIRED").contains(row.get("state").toString())
    ||jdbc.queryForObject("SELECT count(*) FROM marketplace.claim_exchange_dispatch_effect WHERE exchange_id=:id",Map.of("id",id),Integer.class)!=0
    ||reservations.get(id).stream().anyMatch(r->r.status().equals("COMMITTED"))) { throw new OrderRejected(409); }
  var facts=exchanges.progress(id).physical();
  if(facts.size()!=selection.units().size()||facts.stream().anyMatch(p->!p.collection().equals("RECEIVED")||p.inspectionId()==null||p.responsibility()==null||!List.of("SALEABLE","DEFECTIVE").contains(p.inspection())||p.inspection().equals("SALEABLE")&&p.restitutionId()==null)) { throw new OrderRejected(409); }
  for(var unit:selection.units()) { var fact=facts.stream().filter(p->p.itemId().equals(unit.itemId())&&p.ordinal()==unit.ordinal()).findFirst().orElseThrow(()->new OrderRejected(409));if(unit.physical()==null||!fact.physicalId().equals(unit.physical().id())||!fact.actualSkuId().equals(unit.physical().stock().skuId())) { throw new OrderRejected(409); } }
  var targets=facts.stream().map(p->new ReturnFees.Target(p.itemId(),p.ordinal(),ReturnFees.Responsibility.valueOf(p.responsibility().name()))).toList();String snapshot=mapper.writeValueAsString(facts);
  queries.lockOwned(number,authority);return new Source(application,selection,targets,new ReturnFeeQuoting.ExchangeInspection(id,revision,ReturnFeeQuoting.hash(snapshot),snapshot));
 }
}
