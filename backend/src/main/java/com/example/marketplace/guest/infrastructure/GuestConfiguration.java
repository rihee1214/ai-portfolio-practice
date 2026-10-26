package com.example.marketplace.guest.infrastructure;
import com.example.marketplace.phone.api.PhoneVerifications;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration(proxyBeanMethods = false) @Profile("db")
public class GuestConfiguration {
    @Bean JdbcGuestAccess guestAccess(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, JdbcGuestPurchases purchases, com.example.marketplace.identity.api.PasswordHashes hashes, ObjectProvider<PhoneVerifications> provider) {
        return new JdbcGuestAccess(jdbc,manager,purchases,hashes,Clock.systemUTC(),provider.getIfAvailable()!=null);
    }
    @Bean JdbcGuestPurchases guestPurchases(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, ObjectProvider<PhoneVerifications> provider) {
        return new JdbcGuestPurchases(jdbc, manager, Clock.systemUTC(), provider.getIfAvailable());
    }
}
