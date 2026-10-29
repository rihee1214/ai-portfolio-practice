package com.example.marketplace.payment.domain;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
class PaymentRulesTest {
 @Test void uncertainIsNotFailure() { assertTrue(PaymentRules.uncertain("PROCESSING"));assertTrue(PaymentRules.uncertain("UNKNOWN"));assertFalse(PaymentRules.uncertain("FAILED"));assertFalse(PaymentRules.uncertain("SUCCEEDED")); }
 @Test void boundedQueriesRequireReviewAtExactBoundary() { var now=Instant.parse("2030-01-01T00:00:00Z");assertFalse(PaymentRules.review(4,now,now.plusSeconds(899)));assertTrue(PaymentRules.review(5,now,now));assertTrue(PaymentRules.review(0,now,now.plusSeconds(900))); }
}
