package com.example.marketplace.operations.api;

public interface ModerationTargets {
    record State(long edition,long version,boolean hidden) {}
    /** Locks the current target, returning null if a reported child belongs to an earlier review edition. */
    State lock(ReportTargets.Ref ref);
    void change(ReportTargets.Ref ref,long version,boolean hidden);
}
