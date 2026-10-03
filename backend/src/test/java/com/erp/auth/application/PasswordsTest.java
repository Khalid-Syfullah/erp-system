package com.erp.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import org.junit.jupiter.api.Test;

class PasswordsTest {

    private final Passwords passwords = new Passwords();

    @Test
    void hashesWithArgon2idAndUniqueSalts() {
        String hash = passwords.hash("Plausible-Granite-Teapot-1");

        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain("Plausible");
        assertThat(passwords.hash("Plausible-Granite-Teapot-1")).isNotEqualTo(hash);
        assertThat(passwords.matches("Plausible-Granite-Teapot-1", hash)).isTrue();
        assertThat(passwords.matches("Plausible-Granite-Teapot-2", hash)).isFalse();
        assertThat(passwords.matches("anything", null)).isFalse();
        assertThat(passwords.needsRehash(hash)).isFalse();
    }

    @Test
    void verifiesNfkcEquivalentInput() {
        String hash = passwords.hash("ﬁxed-Granite-Teapot");

        assertThat(passwords.matches("fixed-Granite-Teapot", hash)).isTrue();
    }

    @Test
    void flagsHashesWithWeakerParametersForUpgrade() {
        String weak = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 4096, 1)
                .encode("Plausible-Granite-Teapot-1");

        assertThat(passwords.matches("Plausible-Granite-Teapot-1", weak)).isTrue();
        assertThat(passwords.needsRehash(weak)).isTrue();
    }

    @Test
    void reportsPolicyViolationsAtThePointer() {
        ApiException ex = catchThrowableOfType(
                ApiException.class,
                () -> passwords.requireAcceptable("password1234", "a@b.test", "Ann", "/newPassword"));

        assertThat(ex.violations()).extracting(FieldViolation::pointer).containsOnly("/newPassword");
        assertThat(ex.violations()).extracting(FieldViolation::code).contains("BREACHED");
    }
}
