package com.erp.reporting.domain;

import org.jspecify.annotations.Nullable;

/**
 * Neutralises formula injection in exported text (OWASP "CSV injection"): a cell that a spreadsheet
 * would evaluate — starting with {@code = + - @}, a tab or a carriage return — is prefixed with an
 * apostrophe. Numbers and dates are written as typed values and are never text cells.
 */
public final class SpreadsheetText {

    private SpreadsheetText() {}

    public static String neutralise(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value
                : value;
    }

    /** A CSV field (RFC 4180): neutralised, quoted when it contains a separator, quote or line break. */
    public static String csv(@Nullable String value) {
        String text = neutralise(value);
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
