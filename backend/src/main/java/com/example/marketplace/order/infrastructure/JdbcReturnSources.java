package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.ReturnSources;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderUnitHolds;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.order.api.Orders;
import com.example.marketplace.order.domain.OrderRules;
import com.example.marketplace.order.domain.ConfirmationRules;
import com.example.marketplace.catalog.api.ReturnPolicy;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public final class JdbcReturnSources implements ReturnSources {
 private final NamedParameterJdbcTemplate jdbc;private final OrderQueries queries;private final Orders orders;private final OrderUnitHolds holds;private final AftercareLifecycle lifecycle;private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
 public JdbcReturnSources(NamedParameterJdbcTemplate jdbc,OrderQueries queries,Orders orders,OrderUnitHolds holds,AftercareLifecycle lifecycle) { this.jdbc=jdbc;this.queries=queries;this.orders=orders;this.holds=holds;this.lifecycle=lifecycle; }
 @Override public Selection lock(String number,OrderQueries.Authority authority,List<OrderConfirmations.Unit> input,UUID hold) {
  AftercareLifecycle.transaction();var selected=AftercareLifecycle.canonical(input);var own=queries.lockOwned(number,authority);if(!own.state().equals("PAID")) { throw new OrderRejected(409); }for(var u:selected) { lifecycle.lockUnit(own.id(),u); }
  if(hold!=null) { var actual=holds.inspect(own.id(),hold);if(!actual.kind().equals("RETURN")||!actual.state().equals("ACTIVE")||!actual.units().equals(selected)) { throw new OrderRejected(409); } }
  queries.lockOwned(number,authority);var snapshot=orders.find(number);try { OrderRules.validateSnapshot(snapshot.quote());if(snapshot.quote().total()!=Long.parseLong(own.amount())) { throw new IllegalArgumentException(); } }catch(IllegalArgumentException|ArithmeticException invalid) { throw new OrderRejected(409); }
  UUID seller=null;var units=new ArrayList<Unit>();var now=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),java.sql.Timestamp.class).toInstant();
  for(var u:selected) {
   var life=lifecycle.lockUnit(own.id(),u);UUID current=(UUID)life.get("seller_order_id");if(seller!=null&&!seller.equals(current)||hold==null&&!life.get("state").equals("OPEN")||hold!=null&&(!life.get("state").equals("HELD")||!hold.equals(life.get("active_hold_id")))) { throw new OrderRejected(409); }seller=current;
   var due=lifecycle.due(own.id(),u,true);if(due==null||!ConfirmationRules.claim(now,due)) { throw new OrderRejected(409); }
   var rows=jdbc.queryForList("SELECT d.event_id,d.shipment_id,d.occurred_at FROM marketplace.order_initial_delivery_unit u JOIN marketplace.order_initial_delivery d ON d.event_id=u.event_id WHERE u.order_id=:order AND u.seller_order_id=:seller AND u.item_id=:item AND u.ordinal=:o",Map.of("order",own.id(),"seller",seller,"item",u.itemId(),"o",u.ordinal()));if(rows.size()!=1) { throw new OrderRejected(409); }var delivery=rows.getFirst();
   var item=snapshot.sellers().stream().flatMap(s->s.items().stream()).filter(i->i.id().equals(u.itemId())).findFirst().orElseThrow(()->new OrderRejected(409));var line=snapshot.quote().stores().stream().flatMap(s->s.lines().stream()).filter(l->l.skuId().equals(item.skuId())).findFirst().orElseThrow(()->new OrderRejected(409));long net=jdbc.queryForObject("SELECT net FROM marketplace.order_unit WHERE item_id=:item AND ordinal=:o",Map.of("item",u.itemId(),"o",u.ordinal()),Long.class);if(line.quantity()!=item.quantity()||u.ordinal()>=line.units().size()||line.units().get(u.ordinal()).net()!=net) { throw new OrderRejected(409); }
   var policies=jdbc.queryForList("SELECT status,policy_snapshot::text FROM marketplace.order_item_return_policy WHERE item_id=:item",Map.of("item",u.itemId()));if(policies.size()!=1) { throw new OrderRejected(409); }var saved=policies.getFirst();ReturnPolicy policy=saved.get("policy_snapshot")==null?null:mapper.readValue(saved.get("policy_snapshot").toString(),ReturnPolicy.class);if(saved.get("status").equals("ACTIVE")) { if(policy==null||!policy.equals(line.returnPolicy())) { throw new OrderRejected(409); }policy.checkedAmount(); }else if(!saved.get("status").equals("UNRESOLVED")||policy!=null) { throw new OrderRejected(409); }
   units.add(new Unit(u.itemId(),u.ordinal(),item.skuId(),(UUID)delivery.get("shipment_id"),(UUID)delivery.get("event_id"),((java.sql.Timestamp)delivery.get("occurred_at")).toInstant(),net,saved.get("status").toString(),policy));
  }
  queries.lockOwned(number,authority);return new Selection(own.id(),seller,snapshot.checkoutId(),own.mode(),lifecycle.revision(own.id()),units);
 }
}
