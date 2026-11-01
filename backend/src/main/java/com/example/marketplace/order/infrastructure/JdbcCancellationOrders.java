package com.example.marketplace.order.infrastructure;

import com.example.marketplace.order.api.CancellationOrders;
import com.example.marketplace.order.api.OrderConfirmations;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Profile("db")
public final class JdbcCancellationOrders implements CancellationOrders {
 private final OrderQueries queries;private final NamedParameterJdbcTemplate jdbc;
 public JdbcCancellationOrders(OrderQueries queries,NamedParameterJdbcTemplate jdbc) { this.queries=queries;this.jdbc=jdbc; }
 @Override public void lockUnits(UUID order,List<OrderConfirmations.Unit> input) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(jdbc.queryForList("SELECT id FROM marketplace.purchase_order WHERE id=:id FOR UPDATE",Map.of("id",order)).size()!=1) { throw new OrderRejected(409); }
  for(var u:AftercareLifecycle.canonical(input)) { if(jdbc.queryForList("SELECT item_id FROM marketplace.order_unit_lifecycle WHERE order_id=:id AND item_id=:i AND ordinal=:o FOR UPDATE",Map.of("id",order,"i",u.itemId(),"o",u.ordinal())).size()!=1) { throw new OrderRejected(409); } }
 }
 @Override public boolean allCanceled(UUID seller) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  return jdbc.queryForObject("SELECT count(*)>0 AND bool_and(l.state='REFUNDED' AND h.kind='CANCEL' AND d.item_id IS NULL AND s.cause_id IS NOT NULL) FROM marketplace.order_unit_lifecycle l LEFT JOIN marketplace.order_unit_hold h ON h.cause_id=l.refund_cause_id LEFT JOIN marketplace.order_dispatch_unit d ON d.item_id=l.item_id AND d.ordinal=l.ordinal LEFT JOIN marketplace.order_cancellation_settlement_unit s ON s.item_id=l.item_id AND s.ordinal=l.ordinal AND s.order_id=l.order_id AND s.cause_id=l.refund_cause_id WHERE l.seller_order_id=:seller",Map.of("seller",seller),Boolean.class);
 }
 @Override public void requireSellerUnits(UUID order,UUID seller,List<OrderConfirmations.Unit> input) {
  lockUnits(order,input);for(var u:AftercareLifecycle.canonical(input)) { if(jdbc.queryForObject("SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE order_id=:order AND seller_order_id=:seller AND item_id=:item AND ordinal=:ordinal",Map.of("order",order,"seller",seller,"item",u.itemId(),"ordinal",u.ordinal()),Integer.class)!=1) { throw new OrderRejected(409); } }
 }
 @Override public Selection lock(String number,OrderQueries.Authority authority,List<OrderConfirmations.Unit> input,long revision) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(revision<0) { throw new OrderRejected(400); }var units=AftercareLifecycle.canonical(input);
  var owned=queries.lockOwned(number,authority);var selected=new ArrayList<Unit>();UUID seller=null;
  for(var u:units) {
   var rows=jdbc.queryForList("SELECT l.seller_order_id,l.state,i.sku_id,u.net FROM marketplace.order_unit_lifecycle l JOIN marketplace.order_unit u ON u.item_id=l.item_id AND u.ordinal=l.ordinal JOIN marketplace.order_item i ON i.id=l.item_id WHERE l.order_id=:id AND l.item_id=:item AND l.ordinal=:ordinal FOR UPDATE OF l",Map.of("id",owned.id(),"item",u.itemId(),"ordinal",u.ordinal()));
   if(rows.size()!=1) { throw new OrderRejected(409); }var row=rows.getFirst();UUID current=(UUID)row.get("seller_order_id");
   if(seller!=null&&!seller.equals(current)||!row.get("state").equals("OPEN")||jdbc.queryForObject("SELECT count(*) FROM marketplace.order_dispatch_unit WHERE item_id=:i AND ordinal=:o",Map.of("i",u.itemId(),"o",u.ordinal()),Integer.class)!=0) { throw new OrderRejected(409); }
   seller=current;long net=((Number)row.get("net")).longValue();if(net<0) { throw new OrderRejected(409); }selected.add(new Unit(u.itemId(),u.ordinal(),(UUID)row.get("sku_id"),net));
  }
  // Current authority is checked again after the final unit lock wait.
  queries.lockOwned(number,authority);
  long actual=jdbc.queryForObject("SELECT revision FROM marketplace.order_confirmation_state WHERE order_id=:id",Map.of("id",owned.id()),Long.class);
  if(!owned.state().equals("PAID")||revision!=actual) { throw new OrderRejected(409); }
  var detail=queries.detail(number,authority);UUID sellerId=seller;var store=detail.stores().stream().filter(s->s.sellerOrderId().equals(sellerId)).findFirst().orElseThrow(()->new OrderRejected(409));
  long shipping;try { shipping=Math.subtractExact(Long.parseLong(store.shippingBase()),Long.parseLong(store.shippingDiscount())); }catch(ArithmeticException|NumberFormatException bad) { throw new OrderRejected(409); }
  if(shipping<0) { throw new OrderRejected(409); }return new Selection(owned.id(),seller,owned.mode(),actual,selected,shipping);
 }
}
