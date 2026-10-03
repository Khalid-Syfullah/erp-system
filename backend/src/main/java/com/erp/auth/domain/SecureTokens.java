package com.erp.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random secrets for sessions, challenges, invitation/reset links and API tokens (SECURITY.md §3).
 * Every secret has 256 bits of entropy; only its SHA-256 hash is stored. A slow hash is unnecessary
 * for secrets of this strength.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** Recovery-code alphabet without look-alikes (0/o, 1/l/i). */
    private static final char[] RECOVERY = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private SecureTokens() {}

    /** 32 random bytes, base64url without padding (43 characters). */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String alphanumeric(int length) {
        return random(ALPHANUMERIC, length);
    }

    /** A recovery code formatted {@code xxxxx-xxxxx}. */
    public static String recoveryCode() {
        String raw = random(RECOVERY, 10);
        return raw.substring(0, 5) + "-" + raw.substring(5);
    }

    /** Canonical form used for hashing recovery codes: lower case, no separators or spaces. */
    public static String normalizeRecoveryCode(String code) {
        return code.replace("-", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(char[] alphabet, int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = alphabet[RANDOM.nextInt(alphabet.length)];
        }
        return new String(out);
    }
}
