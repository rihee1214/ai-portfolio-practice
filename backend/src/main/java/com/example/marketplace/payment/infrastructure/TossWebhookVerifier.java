package com.example.marketplace.payment.infrastructure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/** Fail closed until the merchant's webhook security key is provisioned. */
public final class TossWebhookVerifier {
 private final String key;private final Clock clock;
 public TossWebhookVerifier(String key,Clock clock) { this.key=key;this.clock=clock; }
 public boolean authentic(byte[] payload,String time,String signature) {
  if(key==null||key.isBlank()||payload==null||payload.length>65536||time==null||time.length()>64||signature==null||signature.length()>2048) { return false; }
  try {
   if(Duration.between(Instant.parse(time),clock.instant()).abs().compareTo(Duration.ofSeconds(300))>0) { return false; }
   var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update(payload);byte[] expected=mac.doFinal((":"+time).getBytes(StandardCharsets.UTF_8));
   for(String part:signature.split(",")) { String candidate=part.trim();if(candidate.startsWith("v1:")) { try { if(MessageDigest.isEqual(expected,Base64.getDecoder().decode(candidate.substring(3)))) { return true; } }catch(IllegalArgumentException invalid) { /* A rotated candidate may still be valid. */ } } }
   return false;
  }catch(Exception invalid) { return false; }
 }
}
