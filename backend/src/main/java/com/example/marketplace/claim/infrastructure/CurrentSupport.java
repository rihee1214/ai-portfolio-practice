package com.example.marketplace.claim.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.order.api.OrderQueries;
import com.example.marketplace.order.api.OrderRejected;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public final class CurrentSupport {
 private final HistorySessions sessions;private final AccessPolicy access;
 public CurrentSupport(HistorySessions sessions,AccessPolicy access) { this.sessions=sessions;this.access=access; }
 public void require(OrderQueries.Authority operator) {
  if(operator==null||operator.browser()==null||operator.member()==null||operator.guestToken()!=null||!sessions.lockReadable(operator.browser(),operator.member(),operator.version())) { throw new OrderRejected(401); }
  if(!access.allowed(operator.member(),AccessArea.SUPPORT)) { throw new OrderRejected(403); }
 }
}
