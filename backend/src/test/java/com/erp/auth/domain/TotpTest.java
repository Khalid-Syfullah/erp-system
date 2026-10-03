package com.erp.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

    /** RFC 6238 appendix B test secret for HMAC-SHA1. */
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 appendix B vectors (SHA1), truncated to the last 6 of the 8 published digits. */
    @ParameterizedTest
    @CsvSource({"59, 287082", "1111111109, 081804", "1111111111, 050471", "1234567890, 005924", "2000000000, 279037"})
    void matchesRfc6238Vectors(long epochSeconds, String expected) {
        assertThat(Totp.code(SECRET, Totp.stepAt(Instant.ofEpochSecond(epochSeconds))))
                .isEqualTo(expected);
    }

    @Test
    void acceptsAdjacentStepsOnly() {
        Instant now = Instant.ofEpochSecond(1_111_111_111L);
        long step = Totp.stepAt(now);

        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step - 1), now, null)).hasValue(step - 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step + 1), now, null)).hasValue(step + 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step - 2), now, null)).isEmpty();
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step + 2), now, null)).isEmpty();
    }

    @Test
    void rejectsReplayOfUsedOrEarlierSteps() {
        Instant now = Instant.ofEpochSecond(1_234_567_890L);
        long step = Totp.stepAt(now);
        String code = Totp.code(SECRET, step);

        OptionalLong first = Totp.verify(SECRET, code, now, step - 1);

        assertThat(first).hasValue(step);
        assertThat(Totp.verify(SECRET, code, now, step)).isEmpty();
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step - 1), now, step)).isEmpty();
    }

    @Test
    void rejectsMalformedCodes() {
        Instant now = Instant.now();
        for (String bad : new String[] {null, "", "12345", "1234567", "abcdef", " 12345"}) {
            assertThat(Totp.verify(SECRET, bad, now, null))
                    .as(String.valueOf(bad))
                    .isEmpty();
        }
    }

    @Test
    void buildsProvisioningUri() {
        String uri = Totp.provisioningUri("ERP Prod", "amy@example.test", SECRET);

        assertThat(uri)
                .startsWith("otpauth://totp/ERP%20Prod:amy%40example.test?secret=")
                .contains("secret=" + Base32.encode(SECRET))
                .contains("issuer=ERP%20Prod", "algorithm=SHA1", "digits=6", "period=30");
    }
}
