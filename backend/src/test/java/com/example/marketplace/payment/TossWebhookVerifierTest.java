package com.example.marketplace.payment;
import com.example.marketplace.payment.infrastructure.TossWebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class TossWebhookVerifierTest {
 private static final String KEY="fixture-webhook-security-key";
 private static final Instant NOW=Instant.parse("2026-10-28T08:00:00Z");
 private static String sign(byte[] body,String time) throws Exception { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update(body);return "v1:"+Base64.getEncoder().encodeToString(mac.doFinal((":"+time).getBytes(StandardCharsets.UTF_8))); }
 @Test void signedRawPayloadAndRotationAreRequired() throws Exception {
  var verifier=new TossWebhookVerifier(KEY,Clock.fixed(NOW,ZoneOffset.UTC));byte[] body="{\"eventType\":\"PAYMENT_STATUS_CHANGED\"}".getBytes(StandardCharsets.UTF_8);String time=NOW.toString(),sig=sign(body,time);
  assertTrue(verifier.authentic(body,time,sig));assertTrue(verifier.authentic(body,time,"v1:invalid,"+sig));assertTrue(verifier.authentic(body,NOW.minusSeconds(299).toString(),sign(body,NOW.minusSeconds(299).toString())));
  // A retry may carry an old event createdAt but must have a fresh signed transmission time.
  assertTrue(verifier.authentic(body,NOW.plusSeconds(60).toString(),sign(body,NOW.plusSeconds(60).toString())));
  assertFalse(verifier.authentic((new String(body,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),time,sig));assertFalse(verifier.authentic(body,time,"v1:invalid"));assertFalse(verifier.authentic(body,NOW.minusSeconds(301).toString(),sign(body,NOW.minusSeconds(301).toString())));assertFalse(verifier.authentic(body,"bad",sig));assertFalse(verifier.authentic(body,time,null));assertFalse(new TossWebhookVerifier("",Clock.fixed(NOW,ZoneOffset.UTC)).authentic(body,time,sig));
 }
}
