package com.example.marketplace.content.domain;

import java.util.List;

public final class ReviewEligibility {
    private ReviewEligibility() { }

    /** Both facts describe the same original purchase unit, including after an exchange. */
    public record Unit(boolean confirmed, boolean refundApplied) { }

    public static boolean mayWrite(List<Unit> units) {
        return units.stream().anyMatch(unit -> unit.confirmed() && !unit.refundApplied());
    }
}
