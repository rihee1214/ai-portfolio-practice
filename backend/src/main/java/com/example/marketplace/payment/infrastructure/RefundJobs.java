package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.platform.api.JobHandler;
import com.example.marketplace.platform.api.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods=false) @Profile("db")
public class RefundJobs {
 @Bean JobHandler refundAutomaticQuery(JdbcRefunds refunds) { return new JobHandler() { public String type(){return RefundQuerySchedule.TYPE;}public Result handle(Jobs.Job job){return refunds.process(job);} }; }
 @Bean JobHandler refundSubmit(JdbcRefunds refunds) { return new JobHandler() {
  public String type() { return "REFUND_SUBMIT.v1"; }
  public Result handle(Jobs.Job job) { return refunds.process(job); }
 }; }
}
