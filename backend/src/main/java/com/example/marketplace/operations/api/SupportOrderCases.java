package com.example.marketplace.operations.api;

import java.util.UUID;

/** Transactional authority for a specific ORDER inquiry, retained through the caller's command. */
public interface SupportOrderCases {
    record OrderCase(String orderNumber, UUID sellerOrderId, long version) { }
    /** A routing hint only. Commands must lock and revalidate the assignment after acquiring the order. */
    OrderCase locateAssigned(UUID caseId,SupportAssignments.Actor actor);
    OrderCase lockAssigned(UUID caseId, SupportAssignments.Actor actor);
}
