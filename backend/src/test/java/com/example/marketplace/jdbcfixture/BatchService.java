package com.example.marketplace.jdbcfixture;

import java.math.BigDecimal;
import org.springframework.transaction.annotation.Transactional;

/** Demonstrates REQUIRED propagation across two distinct proxied service beans. */
public class BatchService {
    private final TransferService transfers;

    public BatchService(TransferService transfers) {
        this.transfers = transfers;
    }

    @Transactional
    public void transferThenCatchFailure() throws TransferRejectedException {
        transfers.transfer(61, 62, new BigDecimal("10.00"), "batch-first");
        try {
            transfers.transfer(61, 9999, new BigDecimal("5.00"), "batch-missing");
        } catch (TransferRejectedException expected) {
            // Catching an inner failure must not allow the outer transaction to commit.
        }
    }
}
