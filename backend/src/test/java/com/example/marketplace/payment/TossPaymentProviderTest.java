package com.example.marketplace.payment;

import com.example.marketplace.payment.api.PaymentProvider;
import com.example.marketplace.payment.infrastructure.TossPaymentProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(OutputCaptureExtension.class)
class TossPaymentProviderTest {
 private static final String KEY="test-payment-fixture-private-reference";
 private static final String SECRET="test_sk_fixture-never-a-real-key";
 private final PaymentProvider.Request request=new PaymentProvider.Request(UUID.randomUUID(),UUID.randomUUID(),15000,"SANDBOX");
 private String body(String status) { return "{\"paymentKey\":\""+KEY+"\",\"orderId\":\""+request.orderId()+"\",\"totalAmount\":15000,\"balanceAmount\":"+(status.equals("CANCELED")?0:15000)+",\"status\":\""+status+"\",\"currency\":\"KRW\",\"method\":\"카드\",\"mId\":\"fixture-mid\",\"version\":\"2024-06-01\"}"; }
 private final class Stub implements AutoCloseable {
  final HttpServer server;final AtomicReference<String> response=new AtomicReference<>(body("DONE"));final List<String> calls=new ArrayList<>();boolean disconnect;boolean holdBody;int status=200;final java.util.concurrent.CountDownLatch releaseBody=new java.util.concurrent.CountDownLatch(1);
  Stub() throws Exception { server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
   calls.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath()+" "+exchange.getRequestHeaders().getFirst("Idempotency-Key"));
   assertEquals("Basic "+java.util.Base64.getEncoder().encodeToString((SECRET+":").getBytes(StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
   String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
   if(exchange.getRequestURI().getPath().endsWith("/confirm")) { var json=new tools.jackson.databind.ObjectMapper().readTree(input);assertEquals(request.orderId().toString(),json.get("orderId").asText());assertEquals(request.amount(),json.get("amount").asLong());assertEquals(KEY,json.get("paymentKey").asText()); }
   if(disconnect) { disconnect=false;exchange.close();return; }byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);if(holdBody) { try { releaseBody.await(3,java.util.concurrent.TimeUnit.SECONDS); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } }exchange.getResponseBody().write(bytes);exchange.close();
  });server.start(); }
  TossPaymentProvider adapter() { return new TossPaymentProvider(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),SECRET,"fixture-mid","2024-06-01",id->id.equals(request.attemptId())?new TossPaymentProvider.Binding(KEY,request.orderId().toString(),request.amount()):null,Duration.ofMillis(500)); }
  @Override public void close() { releaseBody.countDown();server.stop(0); }
 }
 @Test void storedOrderAndAmountAreVerified() throws Exception {
  try(var stub=new Stub()) { var provider=stub.adapter();assertEquals(PaymentProvider.Result.SUCCEEDED,provider.approve(request));
   for(String wrong:List.of(body("DONE").replace(request.orderId().toString(),UUID.randomUUID().toString()),body("DONE").replace(KEY,"another-key"),body("DONE").replace("15000","1"),body("DONE").replace("fixture-mid","foreign-mid"),body("DONE").replace("2024-06-01","2022-06-08"),body("DONE").replace("KRW","USD"),body("DONE").replace("카드","가상계좌"),body("DONE").replace("15000","15000.5"))) { stub.response.set(wrong);assertEquals(PaymentProvider.Result.UNKNOWN,provider.approve(request)); }
   int count=stub.calls.size();assertEquals(PaymentProvider.Result.UNKNOWN,provider.approve(new PaymentProvider.Request(request.attemptId(),request.orderId(),1,"SANDBOX")));assertEquals(PaymentProvider.Result.UNKNOWN,provider.approve(new PaymentProvider.Request(request.attemptId(),request.orderId(),15000,"DEMO")));assertEquals(count,stub.calls.size());
  }
 }
 @Test void timeoutAndQueryFollowCoordinator(CapturedOutput output) throws Exception {
  try(var stub=new Stub()) { var provider=stub.adapter();stub.disconnect=true;assertEquals(PaymentProvider.Result.UNKNOWN,provider.approve(request));assertEquals(PaymentProvider.Result.SUCCEEDED,provider.query(request));assertEquals(2,stub.calls.size());assertEquals("POST /v1/payments/confirm "+request.attemptId(),stub.calls.getFirst());assertEquals("GET /v1/payments/"+KEY+" null",stub.calls.getLast());
   stub.status=503;stub.response.set("{\"message\":\""+SECRET+" "+KEY+"\"}");assertEquals(PaymentProvider.Result.UNKNOWN,provider.approve(request));stub.status=400;assertEquals(PaymentProvider.Result.UNKNOWN,provider.query(request));
   assertFalse(output.getAll().contains(SECRET));assertFalse(output.getAll().contains(KEY));assertFalse(provider.toString().contains(SECRET));assertFalse(new TossPaymentProvider.Binding(KEY,"order",1).toString().contains(KEY));
  }
 }
 @Test void fullCancellationAndConfirmedFailureAreValidated() throws Exception {
  try(var stub=new Stub()) { var provider=stub.adapter();stub.response.set(body("ABORTED"));assertEquals(PaymentProvider.Result.FAILED,provider.query(request));stub.response.set(body("IN_PROGRESS"));assertEquals(PaymentProvider.Result.UNKNOWN,provider.query(request));stub.response.set(body("CANCELED"));assertEquals(PaymentProvider.Result.SUCCEEDED,provider.cancel(request));assertEquals(PaymentProvider.Result.SUCCEEDED,provider.queryCancellation(request));assertEquals("POST /v1/payments/"+KEY+"/cancel "+request.attemptId(),stub.calls.get(2));stub.response.set(body("DONE"));assertEquals(PaymentProvider.Result.UNKNOWN,provider.queryCancellation(request)); }
 }
 @Test void productionKeysAndUntrustedEndpointsAreRejected() {
  assertThrows(IllegalArgumentException.class,()->new TossPaymentProvider(URI.create("https://api.tosspayments.com"),"live_sk_do-not-use","fixture-mid","2024-06-01",id->null,Duration.ofSeconds(3)));
  assertThrows(IllegalArgumentException.class,()->new TossPaymentProvider(URI.create("https://foreign.example"),SECRET,"fixture-mid","2024-06-01",id->null,Duration.ofSeconds(3)));
 }
 @Test void responseBodyDeadlineBoundsUnknown() throws Exception {
  try(var stub=new Stub()) { stub.holdBody=true;var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try { var result=pool.submit(()->stub.adapter().approve(request));assertEquals(PaymentProvider.Result.UNKNOWN,result.get(1200,java.util.concurrent.TimeUnit.MILLISECONDS)); }finally { stub.releaseBody.countDown();pool.shutdownNow(); } }
 }
 @Test void overflowingOrNegativeBalancesAreUnknownForEveryOperation() throws Exception {
  try(var stub=new Stub()) { var provider=stub.adapter();for(String balance:List.of("18446744073709551616","-1","15001")) { stub.response.set(body("CANCELED").replace("\"balanceAmount\":0","\"balanceAmount\":"+balance));assertEquals(PaymentProvider.Result.UNKNOWN,provider.approve(request));assertEquals(PaymentProvider.Result.UNKNOWN,provider.query(request));assertEquals(PaymentProvider.Result.UNKNOWN,provider.cancel(request));assertEquals(PaymentProvider.Result.UNKNOWN,provider.queryCancellation(request)); } }
 }
}
