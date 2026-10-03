package com.erp.auth.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Password rules per NIST SP 800-63B (SECURITY.md §3.2): 12 to 128 characters after NFKC
 * normalization, no composition rules, no expiry; rejected when listed among breached passwords,
 * when built from too few distinct characters, or when containing the user's email local part or
 * name.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    static final int MIN_DISTINCT_CHARACTERS = 5;
    static final int MIN_PERSONAL_TOKEN_LENGTH = 4;

    /** A broken rule, with a stable code for the API. */
    public record Violation(String code, String message) {}

    private final Set<String> breachedPasswords;

    /** @param breachedPasswords NFKC-normalized, lower-cased breached passwords */
    public PasswordPolicy(Set<String> breachedPasswords) {
        this.breachedPasswords = Set.copyOf(breachedPasswords);
    }

    /** The form that is hashed and compared: Unicode NFKC normalization, nothing else. */
    public static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }

    public List<Violation> check(String password, String email, String displayName) {
        List<Violation> violations = new ArrayList<>();
        String normalized = normalize(password);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_LENGTH) {
            violations.add(new Violation("TOO_SHORT", "must be at least " + MIN_LENGTH + " characters"));
        } else if (length > MAX_LENGTH) {
            violations.add(new Violation("TOO_LONG", "must be at most " + MAX_LENGTH + " characters"));
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (breachedPasswords.contains(lower)) {
            violations.add(new Violation("BREACHED", "appears in a list of breached passwords"));
        }
        if (lower.codePoints().distinct().count() < MIN_DISTINCT_CHARACTERS) {
            violations.add(new Violation("TOO_REPETITIVE", "uses too few distinct characters"));
        }
        if (containsPersonalInformation(lower, email, displayName)) {
            violations.add(new Violation("CONTAINS_PERSONAL_INFO", "must not contain your email address or name"));
        }
        return violations;
    }

    private static boolean containsPersonalInformation(String password, String email, String displayName) {
        List<String> tokens = new ArrayList<>();
        if (email != null && email.contains("@")) {
            tokens.add(email.substring(0, email.indexOf('@')));
        }
        if (displayName != null) {
            tokens.addAll(List.of(displayName.split("[\\s.,'-]+")));
        }
        for (String token : tokens) {
            String candidate = normalize(token).toLowerCase(Locale.ROOT);
            if (candidate.length() >= MIN_PERSONAL_TOKEN_LENGTH && password.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
