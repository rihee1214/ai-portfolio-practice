package com.example.marketplace.platform.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Validate effective settings before DataSource/Flyway singleton creation. */
@Configuration(proxyBeanMethods = false)
@Profile("db")
public class DatabaseSettingsConfiguration {
    @Bean
    static BeanFactoryPostProcessor requiredDatabaseSettings(Environment environment) {
        return beanFactory -> {
            List<String> missing = new ArrayList<>();
            require(environment, "spring.datasource.url", "DB_URL", missing);
            require(environment, "spring.datasource.username", "DB_USERNAME", missing);
            require(environment, "spring.datasource.password", "DB_PASSWORD", missing);
            if (!missing.isEmpty()) {
                // Never include values or the placeholder resolver's cause in startup diagnostics.
                throw new IllegalStateException("Required database settings are missing, blank or unresolved: "
                        + String.join(", ", missing));
            }
        };
    }

    private static void require(Environment environment, String key, String variable, List<String> missing) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (IllegalArgumentException unresolved) {
            missing.add(key + " (" + variable + ")");
            return;
        }
        if (value == null || value.isBlank() || value.contains("${")) {
            missing.add(key + " (" + variable + ")");
        }
    }
}
