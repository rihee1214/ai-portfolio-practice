package com.example.marketplace.platform.api;

/** Deployment capability policy, independent of HTTP and business data. */
public record RuntimePolicy(String mode) {
    public RuntimePolicy {
        if (!"local-full".equals(mode) && !"public-demo".equals(mode)) {
            throw new IllegalArgumentException("RUNTIME_CONFIGURATION_REJECTED");
        }
    }
    public boolean restricted() { return "public-demo".equals(mode); }
    public void requirePaymentMode(String paymentMode) {
        if (restricted() && !"DEMO".equals(paymentMode)) { throw new DemoFeatureUnavailable(); }
    }
}
