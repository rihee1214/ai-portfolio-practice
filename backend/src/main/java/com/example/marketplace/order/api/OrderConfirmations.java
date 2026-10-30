package com.example.marketplace.order.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface OrderConfirmations {
 record Unit(UUID itemId,int ordinal) { }
 record Command(String key,Long expectedRevision,List<Unit> units) { }
 record Result(UUID receiptId,List<Unit> units,Instant confirmedAt,String cause,long revision) { public Result { units=List.copyOf(units); } }
 record Eligibility(Unit unit,String lifecycle,long unitRevision,Instant deliveredAt,Instant dueAt,String policyStatus,boolean eligible,String reason) { }
 record View(String orderNumber,String orderState,long revision,String policyStatus,List<Eligibility> units) { public View { units=List.copyOf(units); } }
 View get(String number,OrderQueries.Authority authority);
 Result confirm(String number,OrderQueries.Authority authority,Command command);
}
