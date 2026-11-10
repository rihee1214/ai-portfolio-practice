package com.example.marketplace.fulfillment.api;
import com.example.marketplace.order.api.ExchangeApplications;
import com.example.marketplace.order.api.PhysicalOrders;
import com.example.marketplace.inventory.api.ExchangeReservations;
import com.example.marketplace.inventory.api.PhysicalStocks;
import java.util.List;
import java.util.UUID;
/** Trusted caller-owned transaction, separate concrete exchange allocation and actual outgoing stock. */
public interface ExchangeShipments {
 PhysicalOrders.Dispatch dispatch(UUID dispatchId,ExchangeApplications.Source application,List<PhysicalOrders.Source> received,List<ExchangeReservations.Reservation> reservations,List<PhysicalStocks.Source> committed);
 record Delivery(UUID exchangeId,UUID shipmentId,UUID eventId,java.time.Instant deliveredAt) { }
 Delivery delivery(UUID exchangeId);
 record DispatchReceipt(UUID dispatchId,PhysicalOrders.Dispatch fact,List<UUID> reservationIds) { public DispatchReceipt { reservationIds=List.copyOf(reservationIds); } }
 /** Immutable outgoing evidence, readable after delivery and parent closure without new-sale eligibility. */
 DispatchReceipt recorded(UUID exchangeId);
}
