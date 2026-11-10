package com.example.marketplace.claim.infrastructure;
import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
@Configuration(proxyBeanMethods=false) @Profile("db")
public class ExchangeJobs {
 @Bean JobHandler exchangeRefundRecovery(ExchangeRefundRecoveryScanner scanner) { return new JobHandler() { public String type(){return ExchangeRefundRecoveryScanner.TYPE;}public Result handle(Jobs.Job job){return scanner.scan(job);} }; }
 @Bean JobHandler exchangeRefundResume(ExchangeRefundFinancialWorker exchanges) { return new JobHandler() { public String type() { return ExchangeRefundFinancialWorker.TYPE; }public Result handle(Jobs.Job job) { return exchanges.resume(job); } }; }
 @Bean JobHandler exchangeReservationDue(ExchangeReservationExpiry exchanges) { return new JobHandler() { public String type() { return ExchangeReservationExpiry.TYPE; }public Result handle(Jobs.Job job) { return exchanges.expire(job); } }; }
 @Bean JobHandler exchangeDelivered(ExchangeDeliveredWorker exchanges) { return new JobHandler() { public String type() { return ExchangeDeliveredWorker.TYPE; }public Result handle(Jobs.Job job) { return exchanges.complete(job); } }; }
 @Bean JobHandler exchangeReship(JdbcExchangeDispatches exchanges) { return new JobHandler() { public String type() { return JdbcExchangeDispatches.TYPE; }public Result handle(Jobs.Job job) { return exchanges.dispatch(job); } }; }
 @Bean JobHandler exchangeRecorded() { return new JobHandler() {
  public String type() { return "EXCHANGE_RECORDED.v1"; }
  public Result handle(Jobs.Job job) { if(!java.util.UUID.fromString(job.command().payload()).toString().equals(job.command().causeKey())) { throw new IllegalArgumentException("EXCHANGE_EVENT_IDENTITY"); }return Result.COMPLETE; }
 }; }
}
