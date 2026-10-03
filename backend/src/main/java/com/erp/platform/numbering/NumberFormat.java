package com.erp.platform.numbering;

import java.util.regex.Pattern;

/**
 * A number format: prefix template plus zero-padded counter, e.g. {@code SM-{FY}-} with padding 6
 * renders {@code SM-2026-000042}.
 */
public record NumberFormat(String prefix, int padding) {

    public static final String FISCAL_YEAR = "{FY}";
    public static final int MAX_PREFIX_LENGTH = 20;
    private static final Pattern TEMPLATE = Pattern.compile("^[A-Z0-9/_.-]*(\\{FY})?[A-Z0-9/_.-]*$");

    public NumberFormat {
        validate(prefix, padding);
    }

    /** Throws {@link IllegalArgumentException} with a user-facing message when the format is invalid. */
    public static void validate(String prefix, int padding) {
        if (prefix == null || !TEMPLATE.matcher(prefix).matches()) {
            throw new IllegalArgumentException("prefix may contain A-Z, 0-9, / _ . - and one {FY} placeholder");
        }
        if (prefix.replace(FISCAL_YEAR, "0000").length() > MAX_PREFIX_LENGTH) {
            throw new IllegalArgumentException("prefix must render to at most " + MAX_PREFIX_LENGTH + " characters");
        }
        if (padding < 1 || padding > 12) {
            throw new IllegalArgumentException("padding must be between 1 and 12");
        }
    }

    public String prefixFor(String fiscalYear) {
        return prefix.replace(FISCAL_YEAR, fiscalYear);
    }

    public String render(String fiscalYear, long value) {
        String digits = Long.toString(value);
        return prefixFor(fiscalYear) + "0".repeat(Math.max(0, padding - digits.length())) + digits;
    }
}
