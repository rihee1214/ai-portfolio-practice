package com.example.marketplace.platform.domain;

import java.time.Duration;

public final class JobRules {
    private JobRules() { }
    public static Duration retryDelay(int attempts) {
        return Duration.ofSeconds(switch(attempts) { case 1 -> 5; case 2 -> 15; case 3 -> 60; case 4 -> 300; default -> throw new IllegalArgumentException("No further retry"); });
    }
    public static boolean terminal(int attempts) { return attempts>=5; }
}
