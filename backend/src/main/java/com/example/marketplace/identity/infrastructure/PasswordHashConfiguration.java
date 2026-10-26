package com.example.marketplace.identity.infrastructure;
import com.example.marketplace.identity.api.PasswordHashes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
@Configuration(proxyBeanMethods = false) @Profile("db")
public class PasswordHashConfiguration {
    @Bean PasswordHashes sharedPasswordHashes() { return new SharedPasswordHashes(); }
}
