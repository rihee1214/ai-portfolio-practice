package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class ReturnJobs {
 @Bean JobHandler returnRecoveryScan(ReturnRecoveryScanner scanner) { return new JobHandler() {
  public String type() { return ReturnRecoveryScanner.TYPE; }
  public Result handle(Jobs.Job job) { return scanner.scan(job); }
 }; }
 @Bean JobHandler confirmationRecorded(ConfirmationAftercareWorker worker) { return new JobHandler() {
  public String type() { return "ORDER_CONFIRMATION_RECORDED"; }
  public Result handle(Jobs.Job job) { return worker.recorded(job); }
 }; }
 @Bean JobHandler sellerAftercare(SellerAftercareWorker worker) { return new JobHandler() {
  public String type() { return SellerAftercareWorker.TYPE; }
  public Result handle(Jobs.Job job) { return worker.reevaluate(job); }
 }; }
 @Bean JobHandler returnFinancialResume(ReturnFinancialWorker worker) { return new JobHandler() {
  public String type() { return ReturnFinancialWorker.TYPE; }
  public Result handle(Jobs.Job job) { return worker.resume(job); }
 }; }
 @Bean JobHandler returnRecorded() { return new JobHandler() {
  public String type() { return "RETURN_RECORDED.v1"; }
  public Result handle(Jobs.Job job) {
   if(!java.util.UUID.fromString(job.command().payload()).toString().equals(job.command().causeKey())) { throw new IllegalArgumentException("RETURN_EVENT_IDENTITY"); }
   return Result.COMPLETE;
  }
 }; }
}
