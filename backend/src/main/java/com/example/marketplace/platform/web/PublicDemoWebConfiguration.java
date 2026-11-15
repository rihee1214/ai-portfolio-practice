package com.example.marketplace.platform.web;

import com.example.marketplace.platform.api.RuntimePolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class PublicDemoWebConfiguration implements WebMvcConfigurer {
    private final RuntimePolicy policy;
    public PublicDemoWebConfiguration(RuntimePolicy policy) { this.policy = policy; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicDemoBoundary(policy)).addPathPatterns("/api/**");
    }
}
