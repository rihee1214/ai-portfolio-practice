package com.example.marketplace.jdbcfixture;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class FixtureConfiguration {
    @Bean
    NamedParameterJdbcTemplate jdbc(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    AccountRepository accounts(NamedParameterJdbcTemplate jdbc) {
        return new AccountRepository(jdbc);
    }

    @Bean
    TransferService transfers(AccountRepository accounts) {
        return new TransferService(accounts);
    }

    @Bean
    BatchService batches(TransferService transfers) {
        return new BatchService(transfers);
    }
}
