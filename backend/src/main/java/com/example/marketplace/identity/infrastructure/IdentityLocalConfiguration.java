package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.identity.application.SignupService;
import com.example.marketplace.identity.domain.IdentityStore;
import com.example.marketplace.identity.domain.PasswordHasher;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("db & local-mailbox")
public class IdentityLocalConfiguration {
    @Bean
    Clock identityClock() { return Clock.systemUTC(); }

    @Bean
    PasswordHasher identityPasswordHasher() { return new ArgonPasswordHasher(); }

    @Bean
    IdentityStore identityStore(NamedParameterJdbcTemplate jdbc) { return new JdbcIdentityStore(jdbc); }

    @Bean
    LocalTestMailbox localTestMailbox(Clock identityClock) { return new LocalTestMailbox(identityClock); }

    @Bean
    SignupOperations signupOperations(IdentityStore store, PasswordHasher hasher, LocalTestMailbox mailbox,
            Clock identityClock, PlatformTransactionManager manager) {
        return new SignupService(store, hasher, mailbox, identityClock, manager);
    }
}
