package com.example.marketplace.phone.infrastructure;

import com.example.marketplace.phone.api.PhoneVerifications;
import com.example.marketplace.phone.application.PhoneProvider;
import java.time.Clock;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false) @Profile("db")
public class PhoneConfiguration {
    public PhoneConfiguration(Environment env) {
        LocalPhonePolicy.check(env.getProperty("marketplace.phone.mode", "disabled"), env.getProperty("server.address", ""), Set.of(env.getActiveProfiles()));
    }
    @Bean(destroyMethod = "close") @ConditionalOnProperty(name = "marketplace.phone.mode", havingValue = "local-test")
    FirebasePhoneGateway firebasePhoneGateway(Environment env) {
        return new FirebasePhoneGateway("local-test", env.getRequiredProperty("GCLOUD_PROJECT"), env.getRequiredProperty("FIREBASE_AUTH_EMULATOR_HOST"));
    }
    @Bean @ConditionalOnProperty(name = "marketplace.phone.mode", havingValue = "local-test")
    PhoneVerifications phoneVerifications(FirebasePhoneGateway gateway) { return new PhoneProvider("local-test", "127.0.0.1", Clock.systemUTC(), gateway); }
}
