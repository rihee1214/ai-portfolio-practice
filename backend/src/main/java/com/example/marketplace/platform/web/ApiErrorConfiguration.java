package com.example.marketplace.platform.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class ApiErrorConfiguration {
    @Bean
    FilterRegistrationBean<ApiErrorFilter> apiErrorFilter(JsonMapper mapper) {
        var registration = new FilterRegistrationBean<>(new ApiErrorFilter(mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setDispatcherTypes(DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }
}
