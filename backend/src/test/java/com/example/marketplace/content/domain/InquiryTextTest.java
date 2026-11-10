package com.example.marketplace.content.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InquiryTextTest {
    @Test void requiresOneToTwoThousandCharactersForQuestionAndAnswer() {
        assertEquals("가", InquiryText.normalize(" 가 "));
        String maximum = "😀".repeat(2000);
        assertEquals(maximum, InquiryText.normalize(maximum));
        assertThrows(IllegalArgumentException.class, () -> InquiryText.normalize(maximum + "가"));
        assertThrows(IllegalArgumentException.class, () -> InquiryText.normalize(" \n "));
        assertThrows(IllegalArgumentException.class, () -> InquiryText.normalize(null));
    }
}
