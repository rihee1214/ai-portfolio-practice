package com.example.marketplace.operations.api;

import java.util.UUID;

/** Implemented by the module owning the content. The caller holds the investigation lock for reads. */
public interface ReportTargets {
    enum Kind { REVIEW, COMMENT, SELLER_REPLY, QUESTION, ANSWER }
    record Ref(Kind kind,UUID id,UUID parent,long edition) { }
    record Source(String body,boolean privateTarget) { }
    boolean submission(Ref ref,SupportAssignments.Actor actor,UUID asset);
    Source current(Ref ref,SupportAssignments.Actor actor,UUID asset);
    byte[] image(Ref ref,SupportAssignments.Actor actor,UUID asset);
}
