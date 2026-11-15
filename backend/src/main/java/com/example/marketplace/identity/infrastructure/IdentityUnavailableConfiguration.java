package com.example.marketplace.identity.infrastructure;

import com.example.marketplace.identity.api.PasswordRecoveryOperations;
import com.example.marketplace.identity.api.SignupOperations;
import com.example.marketplace.platform.api.DemoFeatureUnavailable;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** No external identity issuer is installed. Existing member login remains independent. */
@Configuration(proxyBeanMethods = false)
@Profile("db & !local-mailbox")
public class IdentityUnavailableConfiguration {
    @Bean SignupOperations unavailableSignupOperations() {
        return new SignupOperations() {
            public UUID register(String email, String password) { throw new DemoFeatureUnavailable(); }
            public void verifyEmail(String token) { throw new DemoFeatureUnavailable(); }
        };
    }
    @Bean PasswordRecoveryOperations unavailablePasswordRecoveryOperations() {
        return new PasswordRecoveryOperations() {
            public void request(String email) { throw new DemoFeatureUnavailable(); }
            public void reset(String token, String password) { throw new DemoFeatureUnavailable(); }
        };
    }
}
