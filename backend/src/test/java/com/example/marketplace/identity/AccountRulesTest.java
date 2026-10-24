package com.example.marketplace.identity;

import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccountOperations.AddressInput;
import com.example.marketplace.identity.domain.AccountRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountRulesTest {
    @Test void validatesCodePointsAndRejectsControlAndBrokenUnicode() {
        assertEquals("😀".repeat(80), AccountRules.text("😀".repeat(80), 1, 80));
        assertThrows(AccountException.class, () -> AccountRules.text("😀".repeat(81), 1, 80));
        assertThrows(AccountException.class, () -> AccountRules.text("a\u0000b", 1, 80));
        assertThrows(AccountException.class, () -> AccountRules.text("a\uD800", 1, 80));
    }
    @Test void validatesPhoneAndKoreanPostalCode() {
        assertNull(AccountRules.phone("  ", false));
        assertEquals("010-1234-5678", AccountRules.phone(" 010-1234-5678 ", true));
        assertThrows(AccountException.class, () -> AccountRules.phone("abcdefg", true));
        assertThrows(AccountException.class, () -> AccountRules.phone("-------", true));
        assertThrows(AccountException.class, () -> AccountRules.address(new AddressInput(
                "집", "이름", "01012345678", "１２３４５", "주소", "", false)));
    }
}
