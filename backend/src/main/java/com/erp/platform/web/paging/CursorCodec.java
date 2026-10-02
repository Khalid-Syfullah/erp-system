package com.erp.platform.web.paging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Opaque, tamper-evident keyset cursors (API.md §8.1): {@code base64url(payload).base64url(hmac)}
 * with HMAC-SHA256. The payload holds a format version, the query fingerprint and the sort-key values
 * of the last row of the page. Cursors are not encrypted; they contain only values the client has
 * already seen.
 */
public final class CursorCodec {

    static final int FINGERPRINT_BYTES = 16;
    static final int MAX_CURSOR_LENGTH = 2048;
    private static final byte FORMAT_VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** Decoded cursor content. */
    public record Position(byte[] fingerprint, List<@Nullable String> values) {}

    private final SecretKeySpec key;

    public CursorCodec(byte[] key) {
        if (key.length < 32) {
            throw new IllegalArgumentException("Cursor signing key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), ALGORITHM);
    }

    public String encode(byte[] fingerprint, List<@Nullable String> values) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FORMAT_VERSION);
            out.write(fingerprint, 0, FINGERPRINT_BYTES);
            out.writeShort(values.size());
            for (String value : values) {
                out.writeBoolean(value != null);
                if (value != null) {
                    out.writeUTF(value);
                }
            }
            out.flush();
            byte[] payload = bytes.toByteArray();
            return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The verified position, or empty if the cursor is malformed, forged or from another key. */
    public Optional<Position> decode(String cursor) {
        if (cursor == null || cursor.length() > MAX_CURSOR_LENGTH) {
            return Optional.empty();
        }
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot != cursor.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(cursor.substring(0, dot));
            byte[] signature = DECODER.decode(cursor.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), signature)) {
                return Optional.empty();
            }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (in.readByte() != FORMAT_VERSION) {
                    return Optional.empty();
                }
                byte[] fingerprint = in.readNBytes(FINGERPRINT_BYTES);
                int count = in.readUnsignedShort();
                List<String> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(in.readBoolean() ? in.readUTF() : null);
                }
                if (in.available() != 0 || fingerprint.length != FINGERPRINT_BYTES) {
                    return Optional.empty();
                }
                return Optional.of(new Position(fingerprint, values));
            }
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    public static boolean sameFingerprint(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
