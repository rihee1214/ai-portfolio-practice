package com.example.marketplace.dbtest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.require;
import static com.example.marketplace.dbtest.ReleaseSnapshotContract.text;

/** Independent row-based reference arithmetic for the preserved commerce dataset.
 * Does not call any reporting collector, dashboard API or returned metric as an oracle. */
final class ReleaseDashboardExpectation {
    record Key(String scope,String owner,String day,String metric) implements Comparable<Key> {
        @Override public int compareTo(Key other) {return toString().compareTo(other.toString());}
    }
    private final Map<String,List<JsonNode>> rows;
    private final Map<Key,BigInteger> expected=new TreeMap<>();
    private final Instant asOf;
    private ReleaseDashboardExpectation(Map<String,List<JsonNode>> rows,Instant asOf) {this.rows=rows;this.asOf=asOf;}
    static Map<Key,BigInteger> calculate(ReleaseSnapshotContract.Snapshot source,Instant asOf) {
        var reference=new ReleaseDashboardExpectation(source.tables(),asOf);reference.trade();reference.catalog();reference.claims();reference.shipments();reference.settlement();reference.operations();
        reference.add("GLOBAL","","","REFRESH_FAILURES",BigInteger.valueOf(source.tables().get("dashboard_failed_attempt").size()));return reference.expected;
    }
    static Key metricKey(JsonNode row) {return new Key(text(row,"scope"),optional(row,"scope_id"),optional(row,"day"),text(row,"metric"));}
    static BigInteger amount(JsonNode row,String field) {return new BigDecimal(text(row,field)).toBigIntegerExact();}
    private List<JsonNode> table(String name) {return rows.get(name);}
    private JsonNode by(String table,String field,String value) {return table(table).stream().filter(row->text(row,field).equals(value)).findFirst().orElseThrow(()->new AssertionError("Missing independent dashboard source: "+table));}
    private static String optional(JsonNode row,String field) {return row.path(field).isNull()||row.path(field).isMissingNode()?"":text(row,field);}
    private static String day(JsonNode row,String field) {return ReleaseSnapshotContract.time(row,field).atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();}
    private void add(String scope,String owner,String day,String metric,BigInteger value) {expected.merge(new Key(scope,owner,day,metric),value,BigInteger::add);}
    private void global(String day,String metric,BigInteger value) {add("GLOBAL","",day,metric,value);}
    private void both(String store,String day,String metric,BigInteger value) {global(day,metric,value);add("STORE",store,day,metric,value);}
    private String storeForSeller(String seller) {return text(by("seller_order","id",seller),"store_id");}
    private String storeForProduct(String product) {return text(by("catalog_product","id",product),"store_id");}
    private void trade() {
        for(var order:table("purchase_order")) {global(day(order,"created_at"),"ORDERS_CREATED",BigInteger.ONE);}
        for(var seller:table("seller_order")) {var order=by("purchase_order","id",text(seller,"order_id"));add("STORE",text(seller,"store_id"),day(order,"created_at"),"ORDERS_CREATED",BigInteger.ONE);}
        for(var charge:table("payment_attempt")) {
            String order=text(charge,"order_id");var allocation=table("seller_order").stream().filter(row->text(row,"order_id").equals(order)).toList();
            var success=table("payment_event").stream().filter(row->text(row,"attempt_id").equals(text(charge,"id"))&&text(row,"event_key").equals(text(charge,"id")+":succeeded")&&text(row,"action").equals("SUCCEEDED")).toList();
            require(success.size()<=1,"Duplicate independent payment success source");
            if(!success.isEmpty()) {
                require(allocation.stream().map(row->amount(row,"total")).reduce(BigInteger.ZERO,BigInteger::add).equals(amount(charge,"amount")),"Independent seller allocation does not reconcile to charge");
                String date=day(success.getFirst(),"occurred_at");global(date,"PAID_ORDERS",BigInteger.ONE);
                for(var seller:allocation) {
                    String store=text(seller,"store_id");add("STORE",store,date,"PAID_ORDERS",BigInteger.ONE);
                    both(store,date,"PAID_AMOUNT",amount(seller,"total"));both(store,date,"NET_TRADED",amount(seller,"total"));both(store,date,"PRODUCT_GROSS",amount(seller,"subtotal"));
                    both(store,date,"PRODUCT_DISCOUNT",amount(seller,"product_discount"));both(store,date,"COUPON_DISCOUNT",amount(seller,"coupon_discount"));both(store,date,"SHIPPING_CHARGED",amount(seller,"shipping_base").subtract(amount(seller,"shipping_discount")));
                }
            }
            if(java.util.Set.of("FAILED","UNKNOWN","PROCESSING","CREATED").contains(text(charge,"state"))) {global("","PAYMENT_"+text(charge,"state"),BigInteger.ONE);for(var seller:allocation) {add("STORE",text(seller,"store_id"),"","PAYMENT_"+text(charge,"state"),BigInteger.ONE);}}
        }
        for(var refund:table("payment_refund_attempt")) {if(text(refund,"state").equals("SUCCEEDED")) {String store=storeForSeller(text(refund,"seller_order_id")),date=day(refund,"result_at");both(store,date,"REFUNDED_"+text(refund,"purpose"),amount(refund,"amount"));both(store,date,"NET_TRADED",amount(refund,"amount").negate());}}
        for(var confirmation:table("order_confirmation")) {for(var unit:table("order_confirmation_unit")) {if(text(unit,"confirmation_id").equals(text(confirmation,"id"))) {var item=by("order_item","id",text(unit,"item_id"));var money=table("order_unit").stream().filter(row->text(row,"item_id").equals(text(unit,"item_id"))&&row.path("ordinal").equals(unit.path("ordinal"))).findFirst().orElseThrow();both(storeForSeller(text(item,"seller_order_id")),day(confirmation,"confirmed_at"),"CONFIRMED_PRODUCT_NET",amount(money,"net"));}}}
    }
    private void catalog() {
        for(var product:table("catalog_product")) {
            String store=text(product,"store_id");both(store,"","PRODUCTS_REGISTERED",BigInteger.ONE);
            if(product.path("is_hidden_by_admin").asBoolean()) {both(store,"","PRODUCTS_HIDDEN",BigInteger.ONE);}
            if(text(product,"state").equals("STOPPED")) {both(store,"","PRODUCTS_STOPPED",BigInteger.ONE);}
            var shop=by("seller_store","id",store);var owner=by("identity_user","id",text(shop,"owner_id"));var category=by("catalog_category","id",text(product,"category_id"));
            boolean publicProduct=text(product,"state").equals("ACTIVE")&&!product.path("is_hidden_by_admin").asBoolean()&&category.path("active").asBoolean()&&text(shop,"state").equals("OPEN")&&shop.path("visible").asBoolean()&&text(owner,"state").equals("ACTIVE")&&table("role_assignment").stream().anyMatch(row->text(row,"user_id").equals(text(owner,"id"))&&text(row,"role").equals("SELLER"));
            if(publicProduct) {both(store,"","PRODUCTS_PUBLIC",BigInteger.ONE);}
        }
        for(var stock:table("inventory_balance")) {var sku=by("catalog_sku","id",text(stock,"sku_id"));String store=storeForProduct(text(sku,"product_id"));both(store,"","STOCK_ON_HAND",amount(stock,"on_hand"));both(store,"","STOCK_RESERVED",amount(stock,"reserved"));both(store,"","STOCK_AVAILABLE",amount(stock,"on_hand").subtract(amount(stock,"reserved")));}
        for(var question:table("content_product_question")) {if(table("content_product_answer").stream().noneMatch(row->text(row,"question_id").equals(text(question,"id")))) {both(storeForProduct(text(question,"product_id")),"","QUESTIONS_UNANSWERED",BigInteger.ONE);}}
        for(var application:table("seller_application")) {global("","APPLICATION_"+text(application,"state"),BigInteger.ONE);global(day(application,"created_at"),"APPLICATION_RECEIVED",BigInteger.ONE);}
    }
    private void claims() {
        for(String kind:List.of("CANCEL","RETURN","EXCHANGE")) {
            String table=kind.equals("CANCEL")?"claim":kind.equals("RETURN")?"claim_return_request":"claim_exchange_request";
            String units=kind.equals("CANCEL")?"claim_unit":kind.equals("RETURN")?"claim_return_unit":"claim_exchange_unit",parent=kind.equals("CANCEL")?"claim_id":kind.equals("RETURN")?"return_id":"exchange_id";
            var excluded=kind.equals("CANCEL")?java.util.Set.of("COMPLETED","WITHDRAWN"):kind.equals("RETURN")?java.util.Set.of("COMPLETED","WITHDRAWN","REJECTED"):java.util.Set.of("COMPLETED","WITHDRAWN","REJECTED","REFUNDED");
            for(var request:table(table)) {if(!excluded.contains(text(request,"state"))) {String store=storeForSeller(text(request,"seller_order_id"));both(store,"","OPEN_"+kind,BigInteger.ONE);long count=table(units).stream().filter(row->text(row,parent).equals(text(request,"id"))).count();if(count>0) {both(store,"","OPEN_"+kind+"_UNITS",BigInteger.valueOf(count));}}}
        }
        for(var fee:table("claim_fee_application")) {var assessment=by("claim_fee_assessment","id",text(fee,"assessment_id"));both(storeForSeller(text(assessment,"seller_order_id")),day(fee,"applied_at"),"RECOVERY_FEE_DEDUCTED",amount(fee,"deducted"));}
    }
    private void shipments() {
        for(var shipment:table("shipment")) {
            String store=storeForSeller(text(shipment,"seller_order_id"));both(store,"","SHIPMENT_"+text(shipment,"state"),BigInteger.ONE);
            if(java.util.Set.of("DELAYED","FAILED").contains(text(shipment,"exception_status"))) {both(store,"","SHIPMENT_"+text(shipment,"exception_status"),BigInteger.ONE);}
            if(!text(shipment,"state").equals("PREPARING")) {long count=table("shipment_unit").stream().filter(row->text(row,"shipment_id").equals(text(shipment,"id"))).count();if(count>0) {both(store,"","SHIPPED_ORIGINAL_UNITS",BigInteger.valueOf(count));}}
        }
        var oldest=new TreeMap<String,Instant>();
        for(var shipment:table("fulfillment_exchange_shipment")) {String seller=text(shipment,"seller_order_id"),store=storeForSeller(seller);both(store,"","REPLACEMENT_"+text(shipment,"state"),BigInteger.ONE);if(java.util.Set.of("SHIPPED","IN_TRANSIT").contains(text(shipment,"state"))) {oldest.merge(seller,ReleaseSnapshotContract.time(shipment,"shipped_at"),(a,b)->a.isBefore(b)?a:b);}}
        var perStore=new TreeMap<String,BigInteger>();oldest.forEach((seller,time)->perStore.merge(storeForSeller(seller),BigInteger.valueOf(Math.max(0,java.time.Duration.between(time,asOf).getSeconds())),BigInteger::max));
        perStore.forEach((store,value)->add("STORE",store,"","REPLACEMENT_OLDEST_SECONDS",value));if(!perStore.isEmpty()) {global("","REPLACEMENT_OLDEST_SECONDS",perStore.values().stream().max(BigInteger::compareTo).orElseThrow());}
    }
    private void settlement() {
        for(var ledger:table("settlement_ledger")) {String store=text(ledger,"store_id"),date=day(ledger,"occurred_at");both(store,date,"LEDGER_GROSS",amount(ledger,"amount"));both(store,date,"LEDGER_FEE",amount(ledger,"fee"));both(store,date,"LEDGER_NET",amount(ledger,"amount").subtract(amount(ledger,"fee")));}
        var latest=new TreeMap<String,JsonNode>();var weeks=new TreeMap<String,String>();BigInteger mismatch=BigInteger.ZERO;
        for(var batch:table("settlement_batch")) {
            global("","BATCH_"+text(batch,"state"),BigInteger.ONE);
            var members=table("settlement_batch_item").stream().filter(row->text(row,"batch_id").equals(text(batch,"id"))).map(row->by("settlement_ledger","id",text(row,"ledger_id"))).toList();
            BigInteger gross=members.stream().map(row->amount(row,"amount")).reduce(BigInteger.ZERO,BigInteger::add),fee=members.stream().map(row->amount(row,"fee")).reduce(BigInteger.ZERO,BigInteger::add);
            if(!gross.equals(amount(batch,"gross"))||!fee.equals(amount(batch,"fee"))||!gross.subtract(fee).equals(amount(batch,"net"))) {mismatch=mismatch.add(BigInteger.ONE);}
            for(var balance:batch.path("store_balances")) {String store=text(balance,"storeId"),week=text(batch,"week_start");if(!weeks.containsKey(store)||week.compareTo(weeks.get(store))>0) {latest.put(store,balance);weeks.put(store,week);}}
            for(var payment:table("settlement_mock_payment")) {if(text(payment,"batch_id").equals(text(batch,"id"))) {String date=day(payment,"occurred_at");if(batch.path("store_balances").isEmpty()&&batch.path("readiness_version").asInt()==1) {for(var ledger:members) {both(text(ledger,"store_id"),date,"MOCK_PAYOUT",amount(ledger,"amount").subtract(amount(ledger,"fee")));}}else {for(var balance:batch.path("store_balances")) {both(text(balance,"storeId"),date,"MOCK_PAYOUT",amount(balance,"payout"));}}}}
            if(!text(batch,"state").equals("PAID_MOCK")) {for(var held:batch.path("held")) {both(storeForSeller(text(held,"sellerOrderId")),"","SETTLEMENT_HELD",BigInteger.ONE);}}
        }
        latest.forEach((store,balance)->{both(store,"","CARRY_OPENING",amount(balance,"opening"));both(store,"","CARRY_CLOSING",amount(balance,"closing"));});global("","BATCH_MISMATCH",mismatch);
    }
    private void operations() {
        BigInteger waiting=BigInteger.ZERO;
        for(var support:table("operations_support_case")) {var messages=table("operations_case_message").stream().filter(row->text(row,"case_id").equals(text(support,"id"))).max(java.util.Comparator.comparingLong(row->row.path("version").asLong()));if(messages.isPresent()&&!text(messages.get(),"sender").equals("OPERATOR")) {waiting=waiting.add(BigInteger.ONE);for(var assignment:table("operations_support_assignment")) {if(text(assignment,"target_id").equals(text(support,"id"))) {add("ASSIGNEE",text(assignment,"operator_id"),"","MY_CASES_WAITING",BigInteger.ONE);}}}}
        global("","CASES_WAITING",waiting);
        for(var report:table("operations_report")) {global("","REPORT_"+text(report,"state"),BigInteger.ONE);global(day(report,"created_at"),"REPORT_RECEIVED_TOTAL",BigInteger.ONE);}
        for(var event:table("operations_report_event")) {if(!text(event,"previous_state").equals("CLOSED")&&text(event,"next_state").equals("CLOSED")) {global(day(event,"occurred_at"),"REPORT_CLOSURES",BigInteger.ONE);}}
        for(var moderation:table("operations_moderation_action")) {global(day(moderation,"occurred_at"),moderation.path("after_hidden").asBoolean()?"MODERATION_HIDE":"MODERATION_RESTORE",BigInteger.ONE);}
    }
}
