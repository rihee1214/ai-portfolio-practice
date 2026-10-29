package com.example.marketplace.payment.web;
import com.example.marketplace.payment.infrastructure.PaymentCoordinator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
@RestController @Profile("db")
public final class PaymentWebhookController {
 private final PaymentCoordinator payments;
 public PaymentWebhookController(PaymentCoordinator payments) { this.payments=payments; }
 @PostMapping("/api/payments/webhooks/toss") public ResponseEntity<?> receive(jakarta.servlet.http.HttpServletRequest request,@RequestHeader(name="tosspayments-webhook-transmission-time",required=false) String time,@RequestHeader(name="tosspayments-webhook-signature",required=false) String signature,@RequestHeader(name="tosspayments-webhook-transmission-id",required=false) String id) throws java.io.IOException { byte[] payload=request.getInputStream().readNBytes(65537);payments.webhook(payload,time,signature,id);return ResponseEntity.ok().header("Cache-Control","no-store").build(); }
}
