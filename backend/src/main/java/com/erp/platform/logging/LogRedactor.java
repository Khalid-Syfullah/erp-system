package com.erp.platform.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Last line of defence against secrets in logs (SECURITY.md §7.4). The primary control is never
 * passing secrets or personal data to a logger; this masks common shapes that slip through anyway:
 * {@code key=value} / {@code "key":"value"} pairs with sensitive keys, bearer tokens, ERP API tokens
 * and passwords embedded in connection URIs.
 */
public final class LogRedactor {

    static final String MASK = "***";

    private static final String SENSITIVE_KEYS = "password|passwd|pwd|secret|token|api[_-]?key|authorization"
            + "|cookie|session|national[_-]?id|account[_-]?number|iban|date[_-]?of[_-]?birth|signing[_-]?key";

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            // "password": "x" / password=x / token: x  (the key may be part of a longer name, e.g. appPassword)
            // An auth scheme after the key is kept: "Authorization: Bearer x" -> "Authorization: Bearer ***".
            new Rule(
                    Pattern.compile("(?i)([\\w.-]*(?:" + SENSITIVE_KEYS + ")[\\w.-]*\"?\\s*[:=]\\s*\"?)"
                            + "((?:Bearer|Basic)\\s+)?([^\"\\s,;&}\\]]+)"),
                    "$1$2" + MASK),
            new Rule(Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+"), "$1 " + MASK),
            new Rule(Pattern.compile("erp_pat_[A-Za-z0-9]{8}_[A-Za-z0-9_-]+"), "erp_pat_" + MASK),
            // scheme://user:password@host
            new Rule(Pattern.compile("(://[^:/@\\s]+:)([^@\\s/]+)(@)"), "$1" + MASK + "$3"));

    private LogRedactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }
}
