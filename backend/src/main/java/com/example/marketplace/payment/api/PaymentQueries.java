package com.example.marketplace.payment.api;
import com.example.marketplace.order.api.OrderQueries;
import java.time.Instant;
import java.util.List;
public interface PaymentQueries {
    record Event(String action,Instant occurredAt) { }
    record History(String orderNumber,String orderState,String paymentState,String compensation,boolean reviewRequired,String amount,String mode,List<Event> events) { public History { events=List.copyOf(events); } }
    History history(String orderNumber,OrderQueries.Authority authority);
}
