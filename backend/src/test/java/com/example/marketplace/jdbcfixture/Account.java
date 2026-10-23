package com.example.marketplace.jdbcfixture;

import java.math.BigDecimal;

public record Account(long id, String label, BigDecimal balance, String note) {
}
