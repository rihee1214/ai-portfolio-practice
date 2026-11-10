package com.example.marketplace.content.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewEligibilityTest {
    @Test void confirmationAndRefundMustReferToTheSameOriginalUnit() {
        var refundedPurchase = new ReviewEligibility.Unit(true, true);
        var unconfirmed = new ReviewEligibility.Unit(false, false);
        var retainedPurchase = new ReviewEligibility.Unit(true, false);
        assertFalse(ReviewEligibility.mayWrite(List.of(refundedPurchase, unconfirmed)));
        assertTrue(ReviewEligibility.mayWrite(List.of(refundedPurchase, retainedPurchase)));
        assertFalse(ReviewEligibility.mayWrite(List.of()));
    }
}
