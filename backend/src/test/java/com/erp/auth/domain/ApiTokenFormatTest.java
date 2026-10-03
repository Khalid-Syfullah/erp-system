package com.erp.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiTokenFormatTest {

    @Test
    void generatesRecognizableHighEntropyTokens() {
        ApiTokenFormat.Generated a = ApiTokenFormat.generate();
        ApiTokenFormat.Generated b = ApiTokenFormat.generate();

        assertThat(a.token())
                .matches("^erp_pat_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43}$")
                .isNotEqualTo(b.token());
        assertThat(ApiTokenFormat.publicPrefix(a.token())).hasValue(a.publicPrefix());
    }

    @Test
    void rejectsMalformedTokens() {
        for (String bad : new String[] {
            null,
            "",
            "erp_pat_short_x",
            "Bearer erp_pat_",
            "erp_pat_ABCDEFGH_" + "x".repeat(42),
            "xyz_pat_ABCDEFGH_" + "x".repeat(43)
        }) {
            assertThat(ApiTokenFormat.publicPrefix(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void secureTokensAreUniqueAndHashed() {
        String secret = SecureTokens.newSecret();

        assertThat(secret).hasSize(43).isNotEqualTo(SecureTokens.newSecret());
        assertThat(SecureTokens.sha256(secret)).hasSize(32).isEqualTo(SecureTokens.sha256(secret));
        assertThat(SecureTokens.recoveryCode()).matches("^[a-z2-9]{5}-[a-z2-9]{5}$");
        assertThat(SecureTokens.normalizeRecoveryCode("ABCDE-fghjk")).isEqualTo("abcdefghjk");
    }
}
