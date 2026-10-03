package com.erp.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(Set.of("correcthorsebattery"));

    private Set<String> codes(String password) {
        return Set.copyOf(policy.check(password, "amy.lee@example.test", "Amy Lee-Watson").stream()
                .map(PasswordPolicy.Violation::code)
                .toList());
    }

    @Test
    void acceptsLongPassphrasesWithoutCompositionRules() {
        assertThat(codes("plain lowercase words here")).isEmpty();
        assertThat(codes("Ünïcödé pässwörd 🔐 ok")).isEmpty();
    }

    @Test
    void enforcesLengthInCodePointsAfterNormalization() {
        assertThat(codes("short-pass1")).contains("TOO_SHORT");
        assertThat(codes("x".repeat(64) + "y".repeat(65))).contains("TOO_LONG");
        // 12 emoji are 12 code points (24 UTF-16 chars): long enough.
        assertThat(codes("🔐🔑🗝🔒🔓🛡⚔🏰🧱🪨🧰🪛")).doesNotContain("TOO_SHORT");
    }

    @Test
    void rejectsBreachedPasswordsCaseInsensitively() {
        assertThat(codes("CorrectHorseBattery")).contains("BREACHED");
    }

    @Test
    void rejectsRepetitivePasswords() {
        assertThat(codes("aaaaaaaaaaaaaaaa")).contains("TOO_REPETITIVE");
        assertThat(codes("abababababababab")).contains("TOO_REPETITIVE");
    }

    @Test
    void rejectsPasswordsContainingEmailOrName() {
        assertThat(codes("my-amy.lee-password-2026")).contains("CONTAINS_PERSONAL_INFO");
        assertThat(codes("WATSON-forever-and-ever")).contains("CONTAINS_PERSONAL_INFO");
        // Name parts shorter than 4 characters ("Amy", "Lee") are not checked on their own.
        assertThat(codes("leeway is not a name 9")).doesNotContain("CONTAINS_PERSONAL_INFO");
    }

    @Test
    void normalizesWithNfkc() {
        assertThat(PasswordPolicy.normalize("ﬁle")).isEqualTo("file");
        assertThat(PasswordPolicy.normalize("Ａ１")).isEqualTo("A1");
    }
}
