package com.erp.auth.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords per RFC 6238 (HOTP, RFC 4226, over 30-second steps) with
 * HMAC-SHA1 and 6 digits, the parameters every authenticator app supports (SECURITY.md §3.5).
 * Verification accepts the current step ±1 and rejects steps at or before the last accepted one
 * (replay protection).
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    public static final int SECRET_BYTES = 20;
    private static final int TOLERANCE_STEPS = 1;

    private Totp() {}

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /** The code for one time step (RFC 4226 dynamic truncation). */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param lastUsedStep the step of the last accepted code, or {@code null}
     * @return the matching step, or empty if the code is wrong, outside the window or replayed
     */
    public static OptionalLong verify(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || !code.matches("^\\d{6}$")) {
            return OptionalLong.empty();
        }
        long current = stepAt(now);
        long matched = Long.MIN_VALUE;
        for (long step = current - TOLERANCE_STEPS; step <= current + TOLERANCE_STEPS; step++) {
            // Constant-time comparison of each candidate; no early exit on match.
            if (MessageDigest.isEqual(code(secret, step).getBytes(), code.getBytes()) && matched == Long.MIN_VALUE) {
                matched = step;
            }
        }
        if (matched == Long.MIN_VALUE || (lastUsedStep != null && matched <= lastUsedStep)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(matched);
    }

    /** {@code otpauth://} provisioning URI for authenticator apps (Key Uri Format). */
    public static String provisioningUri(String issuer, String account, byte[] secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
