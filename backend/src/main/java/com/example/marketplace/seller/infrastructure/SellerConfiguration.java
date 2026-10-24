package com.example.marketplace.seller.infrastructure;

import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.SellerMembership;
import com.example.marketplace.seller.api.SellerApplications;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("db")
public class SellerConfiguration {
    @Bean SellerApplications sellerApplications(NamedParameterJdbcTemplate jdbc, SellerMembership members, AccessPolicy access, PlatformTransactionManager manager) {
        return new JdbcSellerApplications(jdbc, members, access, manager);
    }
}
