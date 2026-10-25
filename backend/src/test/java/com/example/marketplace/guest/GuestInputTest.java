package com.example.marketplace.guest;

import com.example.marketplace.guest.api.GuestInput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuestInputTest {
    @Test void acceptsOnlyPurchaseAndNormalizesPhoneAndName() {
        var input = new GuestInput(" 가상 구매자 ", "010-1234-5678", "PURCHASE");
        assertEquals("가상 구매자", input.name());
        assertEquals("+821012345678", input.phone());
    }
    @Test void rejectsOtherPurposeInvalidPhoneAndUnsafeNames() {
        for (String purpose : new String[]{"RECOVERY", "purchase", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> new GuestInput("가상", "01012345678", purpose)); }
        for (String name : new String[]{"", " ", "a\nb", "<script>", "x".repeat(81)}) {
            assertThrows(IllegalArgumentException.class, () -> new GuestInput(name, "01012345678", "PURCHASE")); }
        assertThrows(IllegalArgumentException.class, () -> new GuestInput("가상", "01112345678", "PURCHASE"));
    }
}

