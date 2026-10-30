package com.example.marketplace.order.infrastructure;
import org.springframework.stereotype.Component;
/** Server-owned immutable policy values; no operational policy editor is exposed. */
@Component
public final class AftercarePolicySource {
 public record Policy(String version,long confirmationDelaySeconds,long claimWindowSeconds) { public Policy { if(version==null||version.isBlank()||confirmationDelaySeconds<=0||claimWindowSeconds<=0) { throw new IllegalArgumentException("INVALID_AFTERCARE_POLICY"); } } }
 public Policy current() { return new Policy("P09-B-1",604800,604800); }
}
