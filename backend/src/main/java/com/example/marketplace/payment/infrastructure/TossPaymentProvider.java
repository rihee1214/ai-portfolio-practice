package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.PaymentProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-key-only adapter. Provider identity comes from the durable server binding. */
public final class TossPaymentProvider implements PaymentProvider {
 public record Binding(String paymentKey,String orderId,long amount) {
  @Override public String toString() { return "TossBinding[redacted]"; }
 }
 private final URI endpoint;private final String authorization;private final String mid;private final String version;private final Function<UUID,Binding> bindings;private final Duration timeout;
 private final HttpClient client;private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
 public TossPaymentProvider(URI endpoint,String secret,String mid,String version,Function<UUID,Binding> bindings,Duration timeout) {
  boolean official="https".equals(endpoint.getScheme())&&"api.tosspayments.com".equals(endpoint.getHost())&&endpoint.getPort()==-1;
  boolean local="http".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost())&&endpoint.getPort()>0;
  if((!official&&!local)||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null||!ListPath.empty(endpoint.getPath())||secret==null||!secret.matches("test_sk_[A-Za-z0-9_-]+")||mid==null||mid.isBlank()||version==null||!version.matches("\\d{4}-\\d{2}-\\d{2}")||timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(3))>0) { throw new IllegalArgumentException("INVALID_TOSS_TEST_CONFIGURATION"); }
  this.endpoint=endpoint;this.authorization="Basic "+Base64.getEncoder().encodeToString((secret+":").getBytes(StandardCharsets.UTF_8));this.mid=mid;this.version=version;this.bindings=bindings;this.timeout=timeout;
  this.client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 private static final class ListPath { private static boolean empty(String path) { return path==null||path.isEmpty()||path.equals("/"); } }
 @Override public Result approve(Request request) { return execute(request,"approve"); }
 @Override public Result query(Request request) { return execute(request,"query"); }
 @Override public Result cancel(Request request) { return execute(request,"cancel"); }
 @Override public Result queryCancellation(Request request) { return execute(request,"cancel-query"); }
 private Result execute(Request request,String operation) {
  if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("PROVIDER_INSIDE_TRANSACTION"); }
  if(request==null||!"SANDBOX".equals(request.mode())||request.amount()<=0) { return Result.UNKNOWN; }
  Binding saved=bindings.apply(request.attemptId());if(saved==null||saved.paymentKey()==null||saved.paymentKey().isBlank()||saved.paymentKey().length()>200||!request.orderId().toString().equals(saved.orderId())||request.amount()!=saved.amount()) { return Result.UNKNOWN; }
  try {
   String path="/v1/payments/"+URLEncoder.encode(saved.paymentKey(),StandardCharsets.UTF_8).replace("+","%20");
   boolean approval=operation.equals("approve"),cancel=operation.equals("cancel");
   var builder=HttpRequest.newBuilder(endpoint.resolve(approval?"/v1/payments/confirm":cancel?path+"/cancel":path)).timeout(timeout).header("Authorization",authorization).header("Accept","application/json");
   if(approval||cancel) { builder.header("Idempotency-Key",request.attemptId().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(approval?Map.of("paymentKey",saved.paymentKey(),"orderId",saved.orderId(),"amount",saved.amount()):Map.of("cancelReason","Expired order approval compensation")))); }else { builder.GET(); }
   var future=client.sendAsync(builder.build(),info->new LimitedBody());
   HttpResponse<byte[]> response;
   try { response=future.get(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS); }
   finally { if(!future.isDone()) { future.cancel(true); } }
   {
    if(response.statusCode()!=200) { return Result.UNKNOWN; }
    byte[] bytes=response.body();
    var data=json.readTree(bytes);
    if(!saved.paymentKey().equals(data.path("paymentKey").asText())||!saved.orderId().equals(data.path("orderId").asText())||!mid.equals(data.path("mId").asText())||!version.equals(data.path("version").asText())||!"KRW".equals(data.path("currency").asText())||!java.util.List.of("카드","간편결제").contains(data.path("method").asText())||!data.path("totalAmount").isIntegralNumber()||!data.path("totalAmount").canConvertToLong()||data.path("totalAmount").asLong()!=saved.amount()||!data.path("balanceAmount").isIntegralNumber()||!data.path("balanceAmount").canConvertToLong()||data.path("balanceAmount").asLong()<0||data.path("balanceAmount").asLong()>saved.amount()) { return Result.UNKNOWN; }
    String status=data.path("status").asText();long balance=data.path("balanceAmount").asLong();
    if(cancel||operation.equals("cancel-query")) { return status.equals("CANCELED")&&balance==0?Result.SUCCEEDED:Result.UNKNOWN; }
    if(status.equals("DONE")&&balance==saved.amount()) { return Result.SUCCEEDED; }
    return java.util.List.of("ABORTED","EXPIRED").contains(status)||status.equals("CANCELED")&&balance==0?Result.FAILED:Result.UNKNOWN;
   }
  }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return Result.UNKNOWN; }
  catch(Exception ignored) { return Result.UNKNOWN; }
 }
 /** Bound the complete response body as well as the request/header deadline. */
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
  private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();private java.util.concurrent.Flow.Subscription subscription;
  @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
  @Override public void onSubscribe(java.util.concurrent.Flow.Subscription current) { subscription=current;current.request(1); }
  @Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
   for(var buffer:buffers) { if((long)bytes.size()+buffer.remaining()>65536) { subscription.cancel();result.completeExceptionally(new IllegalStateException("PROVIDER_RESPONSE_TOO_LARGE"));return; }byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk); }subscription.request(1);
  }
  @Override public void onError(Throwable ignored) { result.completeExceptionally(new IllegalStateException("PROVIDER_RESPONSE_UNAVAILABLE")); }
  @Override public void onComplete() { result.complete(bytes.toByteArray()); }
 }
}
