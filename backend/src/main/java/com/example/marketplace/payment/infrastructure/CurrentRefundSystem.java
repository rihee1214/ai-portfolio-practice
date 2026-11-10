package com.example.marketplace.payment.infrastructure;

import com.example.marketplace.identity.api.AccessArea;
import com.example.marketplace.identity.api.AccessPolicy;
import com.example.marketplace.identity.api.HistorySessions;
import com.example.marketplace.order.api.OrderRejected;
import com.example.marketplace.payment.api.Payments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("db")
public final class CurrentRefundSystem {
 private final HistorySessions sessions;private final AccessPolicy access;
 public CurrentRefundSystem(HistorySessions sessions,AccessPolicy access){this.sessions=sessions;this.access=access;}
 public void preliminary(Payments.Authority a){if(a==null||a.member()==null||a.browser()==null||a.guestToken()!=null){throw new OrderRejected(401);}if(!access.allowed(a.member(),AccessArea.SYSTEM)){throw new OrderRejected(403);}}
 public void require(Payments.Authority a){preliminary(a);if(!sessions.lockReadable(a.browser(),a.member(),a.version())){throw new OrderRejected(401);}if(!access.allowed(a.member(),AccessArea.SYSTEM)){throw new OrderRejected(403);}}
}
