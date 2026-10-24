package com.example.marketplace.identity.domain;

import com.example.marketplace.identity.api.AccountException;
import com.example.marketplace.identity.api.AccountOperations.AddressInput;

public final class AccountRules {
    private AccountRules() { }

    public static String text(String value, int minimum, int maximum) {
        String clean = value == null ? "" : value.strip();
        int size = clean.codePointCount(0, clean.length());
        if (size < minimum || size > maximum || clean.codePoints().anyMatch(cp ->
                Character.isISOControl(cp) || (cp >= 0xD800 && cp <= 0xDFFF))) {
            throw new AccountException("INVALID_ACCOUNT_INPUT");
        }
        return clean;
    }

    public static String phone(String value, boolean required) {
        String clean = text(value, required ? 7 : 0, 25);
        if (clean.isEmpty() && !required) { return null; }
        if (clean.chars().filter(cp -> cp >= '0' && cp <= '9').count() < 7 || !clean.matches("[0-9+() .-]+")) {
            throw new AccountException("INVALID_ACCOUNT_INPUT");
        }
        return clean;
    }

    public static AddressInput address(AddressInput value) {
        if (value == null) { throw new AccountException("INVALID_ACCOUNT_INPUT"); }
        String postal = text(value.postalCode(), 5, 5);
        if (!postal.matches("[0-9]{5}")) { throw new AccountException("INVALID_ACCOUNT_INPUT"); }
        return new AddressInput(text(value.label(), 1, 40), text(value.recipient(), 1, 80),
                phone(value.phone(), true), postal, text(value.addressLine1(), 1, 200),
                text(value.addressLine2(), 0, 200), value.isDefault());
    }
}
