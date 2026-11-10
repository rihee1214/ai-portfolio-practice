package com.example.marketplace.settlement.infrastructure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration @Profile("db")
public class SettlementConfiguration {
    @Bean("settlementClock") @ConditionalOnMissingBean(name="settlementClock")
    Clock settlementClock() { return Clock.systemUTC(); }
}
