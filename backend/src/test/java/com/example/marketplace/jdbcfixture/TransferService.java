package com.example.marketplace.jdbcfixture;

import java.math.BigDecimal;
import org.springframework.transaction.annotation.Transactional;

public class TransferService {
    private final AccountRepository accounts;

    public TransferService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(rollbackFor = TransferRejectedException.class)
    public void transfer(long from, long to, BigDecimal amount, String reference)
            throws TransferRejectedException {
        if (from == to || amount == null || amount.signum() <= 0 || amount.scale() > 2
                || reference == null || reference.isBlank()) {
            throw new TransferRejectedException("Invalid transfer request");
        }
        if (accounts.changeBalance(from, amount.negate()) != 1) {
            throw new TransferRejectedException("Source account missing");
        }
        if (accounts.changeBalance(to, amount) != 1) {
            throw new TransferRejectedException("Recipient account missing after debit");
        }
        accounts.recordTransfer(from, to, amount, reference);
    }
}
