package com.erp.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldEncryptorTest {

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private final byte[] key1 = key();
    private final byte[] key2 = key();

    @Test
    void roundTripsWithRandomNonces() {
        FieldEncryptor encryptor = new FieldEncryptor(Map.of(1, key1));
        byte[] plain = "secret value".getBytes(StandardCharsets.UTF_8);

        byte[] a = encryptor.encrypt(plain, "t.c:1");
        byte[] b = encryptor.encrypt(plain, "t.c:1");

        assertThat(a).isNotEqualTo(b);
        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain("secret value");
        assertThat(encryptor.decrypt(a, "t.c:1")).isEqualTo(plain);
        assertThat(FieldEncryptor.keyVersionOf(a)).isEqualTo(1);
    }

    @Test
    void detectsTamperingAndWrongAssociatedData() {
        FieldEncryptor encryptor = new FieldEncryptor(Map.of(1, key1));
        byte[] sealed = encryptor.encrypt("x".getBytes(StandardCharsets.UTF_8), "t.c:1");

        assertThatThrownBy(() -> encryptor.decrypt(sealed, "t.c:2")).isInstanceOf(IllegalStateException.class);
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> encryptor.decrypt(sealed, "t.c:1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptor.decrypt(new byte[5], "t.c:1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotatesKeysWhileOldValuesStayReadable() {
        byte[] old = new FieldEncryptor(Map.of(1, key1)).encrypt("v".getBytes(StandardCharsets.UTF_8), "aad");
        FieldEncryptor rotated = new FieldEncryptor(Map.of(1, key1, 2, key2));

        assertThat(rotated.activeKeyVersion()).isEqualTo(2);
        assertThat(rotated.decrypt(old, "aad")).isEqualTo("v".getBytes(StandardCharsets.UTF_8));
        assertThat(FieldEncryptor.keyVersionOf(rotated.encrypt(new byte[] {1}, "aad")))
                .isEqualTo(2);
        assertThatThrownBy(() -> new FieldEncryptor(Map.of(2, key2)).decrypt(old, "aad"))
                .hasMessageContaining("Unknown encryption key version 1");
    }

    @Test
    void validatesKeys() {
        assertThatThrownBy(() -> new FieldEncryptor(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldEncryptor(Map.of(1, new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldEncryptor(Map.of(0, key1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesConfiguredKeys() {
        String configured = "1:" + Base64.getEncoder().encodeToString(key1) + ", 2:"
                + Base64.getEncoder().encodeToString(key2);

        assertThat(CryptoConfiguration.parse(configured)).containsOnlyKeys(1, 2);
        assertThatThrownBy(() -> CryptoConfiguration.parse("nokeyversion")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CryptoConfiguration.parse("1:abc,1:abc")).isInstanceOf(IllegalStateException.class);
    }
}
