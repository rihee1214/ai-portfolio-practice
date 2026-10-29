package com.example.marketplace.platform;

import com.example.marketplace.platform.domain.JobRules;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRulesTest {
    @Test void retryAndLeaseBoundaries() {
        long[] seconds={5,15,60,300};
        for(int attempt=1;attempt<=4;attempt++) {
            assertFalse(JobRules.terminal(attempt));
            assertEquals(Duration.ofSeconds(seconds[attempt-1]),JobRules.retryDelay(attempt));
        }
        assertTrue(JobRules.terminal(5));assertTrue(JobRules.terminal(6));
    }
}
