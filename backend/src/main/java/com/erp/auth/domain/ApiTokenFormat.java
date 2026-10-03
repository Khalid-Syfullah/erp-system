package com.erp.auth.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API token format (SECURITY.md §3.6): {@code erp_pat_<8-char public prefix>_<43-char base64url
 * secret>}. The fixed {@code erp_pat_} prefix lets secret scanners recognise leaked tokens.
 */
public final class ApiTokenFormat {

    public static final String PREFIX = "erp_pat_";
    private static final Pattern FORMAT = Pattern.compile("^erp_pat_([A-Za-z0-9]{8})_([A-Za-z0-9_-]{43})$");

    private ApiTokenFormat() {}

    /** A freshly generated token: the full secret (shown once) and its public prefix. */
    public record Generated(String token, String publicPrefix) {}

    public static Generated generate() {
        String prefix = SecureTokens.alphanumeric(8);
        return new Generated(PREFIX + prefix + "_" + SecureTokens.newSecret(), prefix);
    }

    /** The public prefix of a well-formed token. */
    public static Optional<String> publicPrefix(String token) {
        if (token == null || token.length() > 64) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(token);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
