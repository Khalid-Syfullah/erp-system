package com.erp.platform.web.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private static final byte[] KEY = randomKey();
    private static final byte[] FINGERPRINT = Arrays.copyOf("fingerprint-bytes!".getBytes(), 16);

    private final CursorCodec codec = new CursorCodec(KEY);

    @Test
    void roundTripsValuesIncludingNullsAndUnicode() {
        List<String> values = new ArrayList<>(Arrays.asList("Côte d’Ivoire", null, "42", "a.b|c"));

        String cursor = codec.encode(FINGERPRINT, values);

        assertThat(cursor).doesNotContain("Côte").matches("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
        CursorCodec.Position position = codec.decode(cursor).orElseThrow();
        assertThat(position.values()).containsExactlyElementsOf(values);
        assertThat(position.fingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void rejectsTamperedPayload() {
        String cursor = codec.encode(FINGERPRINT, List.of("USD", "USD"));
        String payload = cursor.substring(0, cursor.indexOf('.'));
        byte[] bytes = Base64.getUrlDecoder().decode(payload);
        bytes[bytes.length - 1] ^= 1;
        String forged =
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + cursor.substring(cursor.indexOf('.'));

        assertThat(codec.decode(forged)).isEmpty();
    }

    @Test
    void rejectsCursorSignedWithAnotherKey() {
        String cursor = new CursorCodec(randomKey()).encode(FINGERPRINT, List.of("x"));

        assertThat(codec.decode(cursor)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(codec.decode("not-a-cursor")).isEmpty();
        assertThat(codec.decode("a.b.c")).isEmpty();
        assertThat(codec.decode("%%%.###")).isEmpty();
        assertThat(codec.decode("x".repeat(CursorCodec.MAX_CURSOR_LENGTH + 1))).isEmpty();
    }

    static byte[] randomKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void requiresAStrongKey() {
        assertThatThrownBy(() -> new CursorCodec(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
