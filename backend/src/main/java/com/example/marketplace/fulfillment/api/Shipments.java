package com.example.marketplace.fulfillment.api;
import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface Shipments {
 record Item(UUID orderItemId,int quantity) { }
 record Command(String key,Long version,List<Item> items) { public Command { if(items!=null) { items=List.copyOf(items); } } }
 record Event(UUID id,String key,String source,String actor,Instant occurredAt,Instant processedAt,String previousState,String nextState,long version,boolean applied) { }
 record View(UUID id,UUID sellerOrderId,String state,long version,String provider,List<Item> items,List<Event> events) { public View { items=List.copyOf(items);events=List.copyOf(events); } }
 record Result(UUID id,String state,long version,boolean applied,UUID eventId) { }
 List<View> seller(UUID sellerOrder,OrderQueries.Authority authority);
 List<View> buyer(String orderNumber,OrderQueries.Authority authority);
 Result ship(UUID shipment,OrderQueries.Authority authority,Command command);
}
