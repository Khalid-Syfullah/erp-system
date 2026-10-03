package com.erp.platform.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field-level encryption for restricted data (SECURITY.md §7.2): AES-256-GCM from the JDK's
 * standard provider, a random 96-bit nonce per value and a 128-bit tag. Stored format:
 * {@code version (1 byte) || nonce (12) || ciphertext || tag (16)}.
 *
 * <p>The associated data binds a ciphertext to its location (e.g. {@code auth.mfa_totp.secret:<user
 * id>}), so values cannot be swapped between rows or columns. New values use the highest key version;
 * older versions remain readable until rotation re-encrypts them.
 */
public final class FieldEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final NavigableMap<Integer, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(Map<Integer, byte[]> keysByVersion) {
        if (keysByVersion.isEmpty()) {
            throw new IllegalArgumentException("At least one encryption key is required");
        }
        TreeMap<Integer, SecretKey> sorted = new TreeMap<>();
        keysByVersion.forEach((version, key) -> {
            if (version < 1 || version > 255) {
                throw new IllegalArgumentException("Key versions must be between 1 and 255");
            }
            if (key.length != 32) {
                throw new IllegalArgumentException("Key version " + version + " must be 32 bytes (AES-256)");
            }
            sorted.put(version, new SecretKeySpec(key.clone(), "AES"));
        });
        this.keys = sorted;
    }

    /** The key version used for new values. */
    public int activeKeyVersion() {
        return keys.lastKey();
    }

    public byte[] encrypt(byte[] plaintext, String associatedData) {
        Objects.requireNonNull(plaintext, "plaintext");
        int version = activeKeyVersion();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(version), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
                    .put((byte) version)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * @throws IllegalStateException if the value was tampered with, belongs to other associated data,
     *     or was encrypted with an unknown key version
     */
    public byte[] decrypt(byte[] stored, String associatedData) {
        if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Encrypted value is malformed");
        }
        int version = Byte.toUnsignedInt(stored[0]);
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new IllegalStateException("Unknown encryption key version " + version);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed: value tampered or wrong associated data", e);
        }
    }

    /** Key version a stored value was encrypted with. */
    public static int keyVersionOf(byte[] stored) {
        return Byte.toUnsignedInt(stored[0]);
    }
}
