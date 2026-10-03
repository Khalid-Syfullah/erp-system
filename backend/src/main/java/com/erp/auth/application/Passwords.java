package com.erp.auth.application;

import com.erp.auth.domain.PasswordPolicy;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing and policy (SECURITY.md §3.2): Argon2id with m = 19 MiB, t = 2, p = 1, 16-byte salt
 * and 32-byte hash (OWASP minimum), via Spring Security's encoder (Bouncy Castle). Hashes are PHC
 * strings ({@code $argon2id$v=19$m=19456,t=2,p=1$...}). Passwords are NFKC-normalized before hashing.
 */
@Component
public class Passwords {

    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder =
            new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    private final PasswordPolicy policy;
    /** Verified for unknown accounts so that response time does not reveal whether an email exists. */
    private final String dummyHash;

    public Passwords() {
        this.policy = new PasswordPolicy(loadBreachedPasswords());
        this.dummyHash = encoder.encode("dummy-password-for-timing-equalization");
    }

    public String hash(String rawPassword) {
        return encoder.encode(PasswordPolicy.normalize(rawPassword));
    }

    public boolean matches(String rawPassword, String hash) {
        return hash != null && encoder.matches(PasswordPolicy.normalize(rawPassword), hash);
    }

    /** True when the hash was produced with weaker parameters than the current ones. */
    public boolean needsRehash(String hash) {
        return encoder.upgradeEncoding(hash);
    }

    /** Spends the same work as a real verification (unknown or inactive accounts). */
    public void equalizeTiming(String rawPassword) {
        encoder.matches(PasswordPolicy.normalize(rawPassword == null ? "" : rawPassword), dummyHash);
    }

    /** @throws ApiException 422 with one violation per broken rule at {@code pointer} */
    public void requireAcceptable(String rawPassword, String email, String displayName, String pointer) {
        List<PasswordPolicy.Violation> violations = policy.check(rawPassword, email, displayName);
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed(
                    "The password does not meet the password policy.",
                    violations.stream()
                            .map(v -> FieldViolation.atPointer(pointer, v.code(), v.message()))
                            .toList());
        }
    }

    private static Set<String> loadBreachedPasswords() {
        try (InputStream in = Passwords.class.getResourceAsStream("/auth/breached-passwords.txt")) {
            if (in == null) {
                throw new IllegalStateException("auth/breached-passwords.txt is missing");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
