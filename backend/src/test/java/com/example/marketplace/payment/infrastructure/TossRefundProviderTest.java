package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.RefundProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TossRefundProviderTest {
 private static final String SECRET="test_sk_f-refund-local-only",PAYMENT_KEY="fixture-f-private-payment";
 private final UUID payment=UUID.randomUUID(),order=UUID.randomUUID(),attempt=UUID.randomUUID();
 private final TossRefundProvider.Binding binding=new TossRefundProvider.Binding(PAYMENT_KEY,order.toString(),15000);
 private RefundProvider.Request request(String reference){var raw=new RefundProvider.Request(payment,order,attempt,"customer-refund:"+attempt,4000,"SANDBOX","F-CUSTOMER-"+attempt,null,reference);return new RefundProvider.Request(payment,order,attempt,raw.idempotencyKey(),4000,"SANDBOX",raw.marker(),TossRefundProvider.fingerprint(raw,binding,"fixture-mid","2024-06-01"),reference);}
 private Map<String,Object> cancel(String transaction,long amount,String marker){return new LinkedHashMap<>(Map.of("transactionKey",transaction,"cancelAmount",amount,"cancelReason",marker,"cancelStatus","DONE"));}
 private Map<String,Object> body(){var root=new LinkedHashMap<String,Object>();root.putAll(Map.of("paymentKey",PAYMENT_KEY,"orderId",order.toString(),"mId","fixture-mid","currency","KRW","version","2024-06-01","method","카드","totalAmount",15000,"balanceAmount",10000,"status","PARTIAL_CANCELED","lastTransactionKey","own-transaction"));var own=cancel("own-transaction",4000,request(null).marker());own.put("cancelRequestId",null);root.put("cancels",List.of(own,cancel("unrelated-last-array-element",1000,"external operation")));return root;}
 private final class Stub implements AutoCloseable {
  private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();final HttpServer server;final List<Map<String,String>> calls=new java.util.concurrent.CopyOnWriteArrayList<>();volatile Map<String,Object> response=body();volatile int status=200;volatile boolean disconnect;
  Stub() throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{calls.add(Map.of("method",exchange.getRequestMethod(),"path",exchange.getRequestURI().getPath(),"authorization",String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),"key",String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),"body",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));if(disconnect){exchange.close();return;}byte[] bytes=json.writeValueAsBytes(response);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();}
  TossRefundProvider adapter(){return new TossRefundProvider(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),SECRET,"fixture-mid","2024-06-01",id->id.equals(payment)?binding:null,Duration.ofMillis(500));}
  public void close(){server.stop(0);}
 }
 @Test void firstPostBindsExactStoredRequestAndOneMarkedTransactionNotArrayOrder() throws Exception {
  try(var stub=new Stub()){var result=stub.adapter().submit(request(null));assertEquals("SUCCEEDED",result.state());assertEquals("own-transaction",result.reference());assertEquals(payment,result.receipt().paymentId());assertEquals(order,result.receipt().orderId());assertEquals(4000,result.receipt().amount());assertEquals(15000,result.receipt().originalAmount());assertEquals(request(null).fingerprint(),result.receipt().requestFingerprint());assertEquals(1,stub.calls.size());var call=stub.calls.getFirst();assertEquals("POST",call.get("method"));assertEquals("/v1/payments/"+PAYMENT_KEY+"/cancel",call.get("path"));assertEquals("customer-refund:"+attempt,call.get("key"));assertEquals("Basic "+java.util.Base64.getEncoder().encodeToString((SECRET+":").getBytes(StandardCharsets.UTF_8)),call.get("authorization"));var input=new tools.jackson.databind.ObjectMapper().readTree(call.get("body"));assertEquals(2,input.size());assertEquals(4000,input.path("cancelAmount").asLong());assertEquals("F-CUSTOMER-"+attempt,input.path("cancelReason").asText());assertFalse(result.toString().contains(PAYMENT_KEY));assertFalse(result.receipt().toString().contains(PAYMENT_KEY));assertFalse(result.receipt().evidence().contains(PAYMENT_KEY));assertFalse(result.receipt().evidence().contains(SECRET));}
 }
 @Test void unboundGetCannotAttributeAmountMarkerOrLastKeyAndBoundGetRequiresExactTransaction() throws Exception {
  try(var stub=new Stub()){assertEquals("UNKNOWN",stub.adapter().query(request(null)).state());assertEquals("SUCCEEDED",stub.adapter().query(request("own-transaction")).state());assertEquals("UNKNOWN",stub.adapter().query(request("another-transaction")).state());assertTrue(stub.calls.stream().allMatch(c->c.get("method").equals("GET")&&c.get("key").equals("null")&&c.get("body").isEmpty()));}
 }
 @Test void duplicatedAmbiguousForeignAndInconsistentEvidenceStaysUnknown() throws Exception {
  try(var stub=new Stub()){var cases=new ArrayList<Map<String,Object>>();for(String field:List.of("paymentKey","orderId","mId","currency","version","method","lastTransactionKey")){var changed=body();changed.put(field,"foreign");cases.add(changed);}for(Object amount:List.of(1,15000.5,"18446744073709551616")){var changed=body();changed.put("totalAmount",amount);cases.add(changed);}for(long balance:List.of(-1L,15001L)){var changed=body();changed.put("balanceAmount",balance);cases.add(changed);}var duplicate=body();duplicate.put("cancels",List.of(cancel("own-transaction",4000,request(null).marker()),cancel("own-transaction",4000,"external")));cases.add(duplicate);var ambiguous=body();ambiguous.put("cancels",List.of(cancel("own-transaction",4000,request(null).marker()),cancel("copied-marker",4000,request(null).marker())));cases.add(ambiguous);var wrongAmount=body();wrongAmount.put("cancels",List.of(cancel("own-transaction",3999,request(null).marker())));cases.add(wrongAmount);var pending=body();var entry=cancel("own-transaction",4000,request(null).marker());entry.put("cancelStatus","PENDING");pending.put("cancels",List.of(entry));cases.add(pending);for(var changed:cases){stub.response=changed;assertEquals("UNKNOWN",stub.adapter().submit(request(null)).state());}}
 }
 @Test void failuresNeverBecomeDefinitiveFailureAndZeroOrWrongBindingsNeverCallProvider() throws Exception {
  try(var stub=new Stub()){stub.disconnect=true;assertEquals("UNKNOWN",stub.adapter().submit(request(null)).state());stub.disconnect=false;for(int code:List.of(400,401,409,429,500)){stub.status=code;assertEquals("UNKNOWN",stub.adapter().query(request(null)).state());}int count=stub.calls.size();var r=request(null);for(var invalid:List.of(new RefundProvider.Request(payment,order,attempt,r.idempotencyKey(),0,"SANDBOX",r.marker(),r.fingerprint(),null),new RefundProvider.Request(UUID.randomUUID(),order,attempt,r.idempotencyKey(),4000,"SANDBOX",r.marker(),r.fingerprint(),null),new RefundProvider.Request(payment,order,attempt,r.idempotencyKey(),4000,"DEMO",r.marker(),r.fingerprint(),null),new RefundProvider.Request(payment,order,attempt,r.idempotencyKey(),4000,"SANDBOX",r.marker(),"0".repeat(64),null))){assertEquals("UNKNOWN",stub.adapter().submit(invalid).state());}assertEquals(count,stub.calls.size());}
 }
 @Test void transactionProductionKeysAndUntrustedDestinationsAreRejected() throws Exception {
  assertThrows(IllegalArgumentException.class,()->new TossRefundProvider(URI.create("https://api.tosspayments.com"),"live_sk_forbidden","mid","2024-06-01",id->binding,Duration.ofSeconds(1)));assertThrows(IllegalArgumentException.class,()->new TossRefundProvider(URI.create("https://foreign.example"),SECRET,"mid","2024-06-01",id->binding,Duration.ofSeconds(1)));
  try(var stub=new Stub()){org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->stub.adapter().query(request(null)));}finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}assertTrue(stub.calls.isEmpty());}
 }
}
