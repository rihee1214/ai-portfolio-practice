package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.AccountOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("db")
public class AccountConfiguration {
    @Bean com.example.marketplace.identity.api.AccessPolicy accessPolicy(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAccessPolicy(jdbc);
    }
    @Bean AccountOperations accountOperations(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAccountOperations(jdbc, manager);
    }
}
