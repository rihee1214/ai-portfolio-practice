package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.RefundProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-credential adapter. A GET can validate an existing receipt, never invent an assignment. */
public final class TossRefundProvider implements RefundProvider {
 public record Binding(String paymentKey,String orderId,long amount) {
  @Override public String toString() { return "RefundBinding[redacted]"; }
 }
 private final URI endpoint;private final String authorization;private final String merchant;private final String version;
 private final Duration timeout;private final Function<UUID,Binding> bindings;private final HttpClient client;
 private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
 public TossRefundProvider(URI endpoint,String secret,String merchant,String version,Function<UUID,Binding> bindings,Duration timeout) {
  boolean official="https".equals(endpoint.getScheme())&&"api.tosspayments.com".equals(endpoint.getHost())&&endpoint.getPort()==-1;
  boolean local="http".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost())&&endpoint.getPort()>0;
  if((!official&&!local)||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null||(endpoint.getPath()!=null&&!endpoint.getPath().isEmpty()&&!endpoint.getPath().equals("/"))||secret==null||!secret.matches("test_sk_[A-Za-z0-9_-]+")||merchant==null||merchant.isBlank()||version==null||!version.matches("\\d{4}-\\d{2}-\\d{2}")||timeout==null||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(3))>0) { throw new IllegalArgumentException("INVALID_TOSS_REFUND_TEST_CONFIGURATION"); }
  this.endpoint=endpoint;this.authorization="Basic "+java.util.Base64.getEncoder().encodeToString((secret+":").getBytes(StandardCharsets.UTF_8));this.merchant=merchant;this.version=version;this.bindings=bindings;this.timeout=timeout;
  client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 @Override public boolean supports(String mode) { return "SANDBOX".equals(mode); }
 @Override public Outcome submit(Request request) { return execute(request,true); }
 @Override public Outcome query(Request request) { return execute(request,false); }
 private Outcome unknown(Request request,String evidence) { return new Outcome("UNKNOWN",request.refundAttemptId(),request.amount(),null,null,evidence); }
 private Outcome execute(Request request,boolean submit) {
  if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("REFUND_PROVIDER_INSIDE_TRANSACTION"); }
  if(request==null) { throw new IllegalArgumentException("REFUND_REQUEST_REQUIRED"); }
  if(!supports(request.mode())||request.amount()<=0||request.marker()==null||!request.marker().equals("F-CUSTOMER-"+request.refundAttemptId())||request.fingerprint()==null||!request.fingerprint().matches("[0-9a-f]{64}")||!request.idempotencyKey().equals("customer-refund:"+request.refundAttemptId())) { return unknown(request,"{}"); }
  Binding binding=bindings.apply(request.paymentId());if(binding==null||binding.paymentKey()==null||binding.paymentKey().isBlank()||binding.paymentKey().length()>200||!request.orderId().toString().equals(binding.orderId())||binding.amount()<request.amount()) { return unknown(request,"{}"); }
  if(!request.fingerprint().equals(fingerprint(request,binding,merchant,version))){return unknown(request,"{}");}
  try {
   String path="/v1/payments/"+URLEncoder.encode(binding.paymentKey(),StandardCharsets.UTF_8).replace("+","%20");var builder=HttpRequest.newBuilder(endpoint.resolve(path+(submit?"/cancel":""))).timeout(timeout).header("Authorization",authorization).header("Accept","application/json");
   if(submit){var body=new LinkedHashMap<String,Object>();body.put("cancelAmount",request.amount());body.put("cancelReason",request.marker());builder.header("Content-Type","application/json").header("Idempotency-Key",request.idempotencyKey()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));}else{builder.GET();}
   var future=client.sendAsync(builder.build(),ignored->new LimitedBody());HttpResponse<byte[]> response;try{response=future.get(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}finally{if(!future.isDone()){future.cancel(true);}}
   if(response.statusCode()!=200){return unknown(request,"{}");}byte[] bytes=response.body();var root=json.readTree(bytes);for(String field:java.util.List.of("paymentKey","orderId","mId","version","currency","method","status")){if(!root.path(field).isTextual()){return unknown(request,"{}");}}if(submit&&!root.path("lastTransactionKey").isTextual()){return unknown(request,"{}");}
   if(!binding.paymentKey().equals(root.path("paymentKey").asText())||!binding.orderId().equals(root.path("orderId").asText())||!merchant.equals(root.path("mId").asText())||!version.equals(root.path("version").asText())||!"KRW".equals(root.path("currency").asText())||!java.util.List.of("카드","간편결제").contains(root.path("method").asText())||!root.path("totalAmount").isIntegralNumber()||!root.path("totalAmount").canConvertToLong()||root.path("totalAmount").asLong()!=binding.amount()||!root.path("balanceAmount").isIntegralNumber()||!root.path("balanceAmount").canConvertToLong()||root.path("balanceAmount").asLong()<0||root.path("balanceAmount").asLong()>binding.amount()||!root.path("cancels").isArray()||!java.util.List.of("PARTIAL_CANCELED","CANCELED").contains(root.path("status").asText())){return unknown(request,"{}");}
   var transactions=new HashSet<String>();var marked=new ArrayList<tools.jackson.databind.JsonNode>();var candidates=new ArrayList<Map<String,Object>>();boolean duplicate=false;
   for(var cancel:root.path("cancels")){if(!cancel.isObject()||!cancel.path("transactionKey").isTextual()||!cancel.path("cancelReason").isTextual()||!cancel.path("cancelStatus").isTextual()||!cancel.path("cancelAmount").isIntegralNumber()||!cancel.path("cancelAmount").canConvertToLong()||cancel.path("cancelAmount").asLong()<0){return unknown(request,"{}");}String key=cancel.path("transactionKey").asText();if(key.isBlank()||key.length()>200||!transactions.add(key)){duplicate=true;}if(cancel.path("cancelReason").asText().equals(request.marker())){marked.add(cancel);}if(cancel.path("cancelAmount").isIntegralNumber()&&cancel.path("cancelAmount").canConvertToLong()){candidates.add(Map.of("transactionKey",key,"amount",Long.toString(cancel.path("cancelAmount").asLong()),"state",cancel.path("cancelStatus").asText(),"matchesServerMarker",cancel.path("cancelReason").asText().equals(request.marker())));}}
   String evidence=json.writeValueAsString(Map.of("provider","TOSS","mode",request.mode(),"paymentKeyHash",sha(binding.paymentKey()),"orderId",binding.orderId(),"merchant",merchant,"currency","KRW","originalAmount",Long.toString(binding.amount()),"responseHash",sha(bytes),"lastTransactionKey",root.path("lastTransactionKey").asText(),"cancels",candidates));
   if(duplicate||marked.size()>1){return unknown(request,evidence);}tools.jackson.databind.JsonNode selected=null;
   if(submit){if(marked.size()==1){var candidate=marked.getFirst();if(candidate.path("transactionKey").asText().equals(root.path("lastTransactionKey").asText())){selected=candidate;}}}
   else if(request.transactionKey()!=null){for(var cancel:root.path("cancels")){if(request.transactionKey().equals(cancel.path("transactionKey").asText())){selected=cancel;}}}
   if(selected==null||!selected.path("cancelAmount").isIntegralNumber()||!selected.path("cancelAmount").canConvertToLong()||selected.path("cancelAmount").asLong()!=request.amount()||!"DONE".equals(selected.path("cancelStatus").asText())||!request.marker().equals(selected.path("cancelReason").asText())){return unknown(request,evidence);}
   String transaction=selected.path("transactionKey").asText();var receipt=new Receipt("TOSS","SANDBOX",request.paymentId(),request.orderId(),request.amount(),binding.amount(),merchant,"KRW",transaction,request.fingerprint(),sha(bytes),evidence);return new Outcome("SUCCEEDED",request.refundAttemptId(),request.amount(),transaction,receipt,evidence);
  }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return unknown(request,"{}");}catch(Exception unavailable){return unknown(request,"{}");}
 }
 static String fingerprint(Request request,Binding binding,String merchant,String version) {
  return sha("TOSS|"+request.mode()+"|"+request.paymentId()+"|"+request.orderId()+"|"+request.refundAttemptId()+"|"+request.idempotencyKey()+"|"+request.amount()+"|"+request.marker()+"|"+sha(binding.paymentKey())+"|"+binding.orderId()+"|"+binding.amount()+"|"+merchant+"|"+version+"|KRW");
 }
 static String sha(String value){return sha(value.getBytes(StandardCharsets.UTF_8));}
 private static String sha(byte[] bytes){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();private java.util.concurrent.Flow.Subscription subscription;
  public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
  public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;value.request(1);}
  public void onNext(java.util.List<java.nio.ByteBuffer> buffers){for(var buffer:buffers){if((long)bytes.size()+buffer.remaining()>65536){subscription.cancel();result.completeExceptionally(new IllegalStateException("REFUND_RESPONSE_TOO_LARGE"));return;}byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}subscription.request(1);}
  public void onError(Throwable failure){result.completeExceptionally(new IllegalStateException("REFUND_RESPONSE_UNAVAILABLE"));}
  public void onComplete(){result.complete(bytes.toByteArray());}
 }
}
