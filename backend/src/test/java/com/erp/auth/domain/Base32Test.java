package com.erp.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Base32Test {

    /** RFC 4648 §10 test vectors (padding removed). */
    @ParameterizedTest
    @CsvSource({"f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
    void encodesAndDecodesRfc4648Vectors(String plain, String encoded) {
        assertThat(Base32.encode(plain.getBytes(StandardCharsets.US_ASCII))).isEqualTo(encoded);
        assertThat(new String(Base32.decode(encoded), StandardCharsets.US_ASCII))
                .isEqualTo(plain);
        assertThat(new String(Base32.decode(encoded.toLowerCase() + "=="), StandardCharsets.US_ASCII))
                .isEqualTo(plain);
    }

    @org.junit.jupiter.api.Test
    void rejectsInvalidCharacters() {
        assertThatThrownBy(() -> Base32.decode("MZ1W")).isInstanceOf(IllegalArgumentException.class);
    }
}
