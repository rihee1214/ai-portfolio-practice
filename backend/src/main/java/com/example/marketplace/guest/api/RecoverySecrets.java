package com.example.marketplace.guest.api;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
public final class RecoverySecrets {
    private RecoverySecrets() { }
    public static String generate() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public static boolean matches(String value,String expected) {
        return value!=null && value.matches("[A-Za-z0-9_-]{43}") && expected!=null
                && MessageDigest.isEqual(hash(value).getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));
    }
}
