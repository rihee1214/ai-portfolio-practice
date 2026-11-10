package com.example.marketplace.fulfillment.infrastructure;
import com.example.marketplace.fulfillment.api.ExchangeShipments;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.PhysicalOrders;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.inventory.api.ExchangeReservations;
import com.example.marketplace.inventory.api.PhysicalStocks;
import com.example.marketplace.platform.api.Jobs;
import com.example.marketplace.platform.api.Outbox;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service @Profile("db")
public final class JdbcExchangeShipments implements ExchangeShipments {
 private final NamedParameterJdbcTemplate jdbc;private final Outbox outbox;
 private final java.time.Clock clock;
 @org.springframework.beans.factory.annotation.Autowired
 public JdbcExchangeShipments(NamedParameterJdbcTemplate jdbc,Outbox outbox) { this(jdbc,outbox,null); }
 public JdbcExchangeShipments(NamedParameterJdbcTemplate jdbc,Outbox outbox,java.time.Clock clock) { this.jdbc=jdbc;this.outbox=outbox; this.clock=clock; }
 @Override public DispatchReceipt recorded(UUID exchange) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }var rows=jdbc.queryForList("SELECT h.*,e.id event_id,e.occurred_at FROM marketplace.fulfillment_exchange_shipment h JOIN marketplace.fulfillment_exchange_event e ON e.shipment_id=h.id AND e.version=1 AND e.state='SHIPPED' WHERE h.exchange_id=:id",Map.of("id",exchange));if(rows.size()!=1) { throw new OrderRejected(409); }var h=rows.getFirst();UUID shipment=(UUID)h.get("id");var units=jdbc.queryForList("SELECT * FROM marketplace.fulfillment_exchange_allocation WHERE shipment_id=:id ORDER BY item_id::text,ordinal",Map.of("id",shipment));if(units.isEmpty()||!((Timestamp)h.get("shipped_at")).toInstant().equals(((Timestamp)h.get("occurred_at")).toInstant())) { throw new OrderRejected(409); }return new DispatchReceipt((UUID)h.get("dispatch_id"),new PhysicalOrders.Dispatch(exchange,(UUID)h.get("order_id"),(UUID)h.get("seller_order_id"),shipment,(UUID)h.get("event_id"),((Timestamp)h.get("shipped_at")).toInstant(),units.stream().map(u->new PhysicalOrders.Successor((UUID)u.get("physical_id"),(UUID)u.get("predecessor_id"),(UUID)u.get("source_id"),(UUID)u.get("id"))).toList()),units.stream().map(u->(UUID)u.get("reservation_id")).toList());
 }
 @Override public Delivery delivery(UUID exchange) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }var rows=jdbc.queryForList("SELECT h.id,e.id event_id,e.occurred_at FROM marketplace.fulfillment_exchange_shipment h JOIN marketplace.fulfillment_exchange_event e ON e.shipment_id=h.id AND e.version=h.version AND e.state='DELIVERED' WHERE h.exchange_id=:id AND h.state='DELIVERED' AND h.delivered_at=e.occurred_at",Map.of("id",exchange));if(rows.size()!=1) { throw new OrderRejected(409); }var r=rows.getFirst();return new Delivery(exchange,(UUID)r.get("id"),(UUID)r.get("event_id"),((Timestamp)r.get("occurred_at")).toInstant());
 }
 @Override public PhysicalOrders.Dispatch dispatch(UUID request,ExchangeApplications.Source app,List<PhysicalOrders.Source> received,List<ExchangeReservations.Reservation> reservations,List<PhysicalStocks.Source> committed) {
  if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("TRANSACTION_REQUIRED"); }
  if(request==null||app==null||received.size()!=app.units().size()||reservations.size()!=received.size()||committed.size()!=received.size()||jdbc.queryForObject("SELECT count(*) FROM marketplace.fulfillment_exchange_shipment WHERE exchange_id=:id",Map.of("id",app.id()),Integer.class)!=0) { throw new OrderRejected(409); }
  UUID shipment=UUID.randomUUID(),event=UUID.randomUUID();var at=now();var p=new MapSqlParameterSource("shipment",shipment).addValue("request",request).addValue("exchange",app.id()).addValue("order",app.orderId()).addValue("seller",app.sellerOrderId()).addValue("at",Timestamp.from(at)).addValue("tracking","MOCK-EX-"+shipment).addValue("event",event);
  jdbc.update("INSERT INTO marketplace.fulfillment_exchange_shipment(id,exchange_id,dispatch_id,order_id,seller_order_id,state,version,tracking_number,shipped_at) VALUES(:shipment,:exchange,:request,:order,:seller,'SHIPPED',1,:tracking,:at)",p);
  jdbc.update("INSERT INTO marketplace.fulfillment_exchange_event(id,shipment_id,version,state,occurred_at) VALUES(:event,:shipment,1,'SHIPPED',:at)",p);var successors=new java.util.ArrayList<PhysicalOrders.Successor>();
  for(var source:received) { var reservation=reservations.stream().filter(r->r.physicalId().equals(source.originalPhysicalId())&&r.status().equals("COMMITTED")).findFirst().orElseThrow(()->new OrderRejected(409));var stock=committed.stream().filter(s->s.kind()==PhysicalStocks.Kind.EXCHANGE&&s.reservationId().equals(reservation.id())&&s.itemId().equals(source.itemId())&&s.ordinal()==source.ordinal()&&s.skuId().equals(reservation.targetSkuId())).findFirst().orElseThrow(()->new OrderRejected(409));UUID allocation=UUID.randomUUID(),physical=UUID.randomUUID();
   jdbc.update("INSERT INTO marketplace.fulfillment_exchange_allocation(id,shipment_id,exchange_id,item_id,ordinal,predecessor_id,physical_id,source_id,reservation_id) VALUES(:allocation,:shipment,:exchange,:item,:ordinal,:predecessor,:physical,:source,:reservation)",p.addValue("allocation",allocation).addValue("item",source.itemId()).addValue("ordinal",source.ordinal()).addValue("predecessor",source.id()).addValue("physical",physical).addValue("source",stock.id()).addValue("reservation",reservation.id()));successors.add(new PhysicalOrders.Successor(physical,source.id(),stock.id(),allocation));
  }
  for(int version=2;version<=3;version++) { String key="exchange-progress:"+shipment+":"+version;UUID job=outbox.append(new Outbox.Event(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new Jobs.Command("EXCHANGE_SHIPMENT_PROGRESS.v1",key,shipment.toString(),shipment+"|"+version,at)));jdbc.update("INSERT INTO marketplace.fulfillment_exchange_progress_schedule(shipment_id,expected_version,job_id,scheduled_at) VALUES(:shipment,:version,:job,:at)",p.addValue("version",version).addValue("job",job)); }
  return new PhysicalOrders.Dispatch(app.id(),app.orderId(),app.sellerOrderId(),shipment,event,at,successors);
 }
 /** Production keeps its single database clock source; controlled integration fixtures supply the same explicit clock across the full workflow. */
 private java.time.Instant now() { return clock==null?jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),Timestamp.class).toInstant():clock.instant(); }
}
