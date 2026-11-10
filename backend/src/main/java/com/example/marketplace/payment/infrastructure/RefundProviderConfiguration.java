package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.payment.api.RefundProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class RefundProviderConfiguration {
 @Bean @Primary RefundProvider customerRefundProvider(MockRefundProvider demo,PaymentProviderConfiguration.Settings settings,NamedParameterJdbcTemplate jdbc) {
  RefundProvider sandbox=settings.ready()?new TossRefundProvider(URI.create(settings.endpoint()),settings.secretKey(),settings.mid(),settings.apiVersion(),id->{var rows=jdbc.query("SELECT provider_payment_key,provider_order_id,amount FROM marketplace.payment_toss_binding WHERE attempt_id=:id",Map.of("id",id),(r,n)->new TossRefundProvider.Binding(r.getString(1),r.getString(2),r.getLong(3)));return rows.size()==1?rows.getFirst():null;},Duration.ofSeconds(3)):null;
  return new RefundProvider(){
   private RefundProvider select(String mode){return "DEMO".equals(mode)?demo:"SANDBOX".equals(mode)?sandbox:null;}
   public boolean supports(String mode){return select(mode)!=null;}
   public Outcome submit(Request request){var selected=select(request.mode());return selected==null?new Outcome("UNKNOWN",request.refundAttemptId(),request.amount(),null):selected.submit(request);}
   public Outcome query(Request request){var selected=select(request.mode());return selected==null?new Outcome("UNKNOWN",request.refundAttemptId(),request.amount(),null):selected.query(request);}
  };
 }
}
