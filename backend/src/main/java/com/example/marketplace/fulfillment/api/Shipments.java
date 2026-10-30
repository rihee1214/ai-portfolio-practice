package com.example.marketplace.fulfillment.api;
import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface Shipments {
 record Item(UUID orderItemId,int quantity) { }
 record Unit(UUID orderItemId,int ordinal) { }
 record Command(String key,Long version,List<Item> items) { public Command { if(items!=null) { items=List.copyOf(items); } } }
 record Event(UUID id,String key,String source,String actor,Instant occurredAt,Instant processedAt,String previousState,String nextState,long version,boolean applied) { }
 record TrackingCommand(String key,Long version,String tracking,String reason) { }
 record TrackingHistory(UUID eventId,String previousTracking,String nextTracking,String reason,String source,String actor,Instant recordedAt) { }
 record View(UUID id,UUID sellerOrderId,String state,long version,String provider,List<Item> items,List<Event> events,List<Unit> units,String tracking,List<TrackingHistory> trackingHistory,String exceptionStatus,long progressEpoch,Instant deliveredAt) { public View { items=List.copyOf(items);events=List.copyOf(events);units=List.copyOf(units);trackingHistory=List.copyOf(trackingHistory); } }
 record Result(UUID id,String state,long version,boolean applied,UUID eventId) { }
 record SplitResult(Result source,Result target,List<Unit> units) { public SplitResult { units=List.copyOf(units); } }
 List<View> seller(UUID sellerOrder,OrderQueries.Authority authority);
 List<View> buyer(String orderNumber,OrderQueries.Authority authority);
 Result ship(UUID shipment,OrderQueries.Authority authority,Command command);
 SplitResult split(UUID shipment,OrderQueries.Authority authority,Command command);
 Result tracking(UUID shipment,OrderQueries.Authority authority,TrackingCommand command);
}
