package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class ClaimJobs {
 @Bean JobHandler refundResult(JdbcFinancialResults results) { return new JobHandler() {
  public String type() { return "REFUND_RESULT_RECORDED.v1"; }
  public Result handle(Jobs.Job job) { return results.result(job); }
 }; }
 @Bean JobHandler claimRecorded() { return new JobHandler() {
  public String type() { return "CLAIM_RECORDED.v1"; }
  public Result handle(Jobs.Job job) { if(!java.util.UUID.fromString(job.command().payload()).toString().equals(job.command().causeKey())) { throw new IllegalArgumentException("CLAIM_EVENT_IDENTITY"); }return Result.COMPLETE; }
 }; }
}
