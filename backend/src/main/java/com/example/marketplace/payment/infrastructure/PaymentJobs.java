package com.example.marketplace.payment.infrastructure;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
@Configuration(proxyBeanMethods=false) @Profile("db")
public class PaymentJobs {
 @Bean JobHandler paymentQuery(PaymentCoordinator coordinator) { return handler("PAYMENT_QUERY",coordinator); }
 @Bean JobHandler paymentCompensation(PaymentCoordinator coordinator) { return handler("PAYMENT_COMPENSATE",coordinator); }
 @Bean JobHandler paymentExpiry(PaymentCoordinator coordinator) { return handler("ORDER_EXPIRY",coordinator); }
 private JobHandler handler(String type,PaymentCoordinator coordinator) { return new JobHandler() {
  public String type() { return type; }
  public Result handle(Jobs.Job job) {
   if(type.equals("ORDER_EXPIRY")) { coordinator.expire(job.command().payload()); }
   else { var parts=job.command().payload().split(":");coordinator.query(parts[0],Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),type.equals("PAYMENT_COMPENSATE")); }
   return Result.COMPLETE;
  }
 }; }
}
