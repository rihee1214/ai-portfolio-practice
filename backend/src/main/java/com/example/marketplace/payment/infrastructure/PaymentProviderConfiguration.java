package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.PaymentProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class PaymentProviderConfiguration {
 public record Settings(String clientKey,String secretKey,String mid,String apiVersion,String webhookSecurityKey,String endpoint) {
  public boolean ready() { return clientKey!=null&&clientKey.matches("test_ck_[A-Za-z0-9_-]+")&&secretKey!=null&&secretKey.matches("test_sk_[A-Za-z0-9_-]+")&&mid!=null&&!mid.isBlank()&&apiVersion!=null&&apiVersion.matches("\\d{4}-\\d{2}-\\d{2}"); }
  @Override public String toString() { return "TossSettings[redacted,ready="+ready()+"]"; }
 }
 @Bean Settings tossSettings(@Value("${marketplace.toss.client-key:${TOSS_CLIENT_KEY:}}") String client,@Value("${marketplace.toss.secret-key:${TOSS_SECRET_KEY:}}") String secret,@Value("${marketplace.toss.mid:${TOSS_MID:}}") String mid,@Value("${marketplace.toss.api-version:${TOSS_API_VERSION:}}") String version,@Value("${marketplace.toss.webhook-security-key:${TOSS_WEBHOOK_SECURITY_KEY:}}") String webhook,@Value("${marketplace.toss.endpoint:https://api.tosspayments.com}") String endpoint) {
  if(client.startsWith("live_")||secret.startsWith("live_")) { throw new IllegalArgumentException("LIVE_PAYMENT_KEYS_DISABLED"); }if(!endpoint.equals("https://api.tosspayments.com")) { throw new IllegalArgumentException("UNTRUSTED_TOSS_ENDPOINT"); }return new Settings(client,secret,mid,version,webhook,endpoint);
 }
 @Bean PaymentProvider paymentProvider(MockPaymentProvider demo,Settings settings,NamedParameterJdbcTemplate jdbc) {
  PaymentProvider sandbox=settings.ready()?new TossPaymentProvider(URI.create(settings.endpoint()),settings.secretKey(),settings.mid(),settings.apiVersion(),id->{var rows=jdbc.query("SELECT provider_payment_key,provider_order_id,amount FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",id),(r,i)->new TossPaymentProvider.Binding(r.getString(1),r.getString(2),r.getLong(3)));return rows.isEmpty()?null:rows.getFirst();},Duration.ofSeconds(3)):null;
  return new PaymentProvider() {
   private PaymentProvider select(Request request) { return "DEMO".equals(request.mode())?demo:"SANDBOX".equals(request.mode())?sandbox:null; }
   @Override public Result approve(Request request) { var selected=select(request);return selected==null?Result.UNKNOWN:selected.approve(request); }
   @Override public Result query(Request request) { var selected=select(request);return selected==null?Result.UNKNOWN:selected.query(request); }
   @Override public Result cancel(Request request) { var selected=select(request);return selected==null?Result.UNKNOWN:selected.cancel(request); }
   @Override public Result queryCancellation(Request request) { var selected=select(request);return selected==null?Result.UNKNOWN:selected.queryCancellation(request); }
  };
 }
}
