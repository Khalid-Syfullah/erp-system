package com.erp.partners.domain;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Normalization and validation of bank account numbers and IBANs (ISO 13616). */
public final class BankAccountNumbers {

    private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9]{4,34}$");
    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private BankAccountNumbers() {}

    /** Upper case without spaces or dashes; {@code null} stays {@code null}. */
    public static @Nullable String normalize(@Nullable String value) {
        return value == null ? null : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValidAccountNumber(String normalized) {
        return ACCOUNT.matcher(normalized).matches();
    }

    /** Format and the mod-97 check of ISO 13616. */
    public static boolean isValidIban(String normalized) {
        if (!IBAN.matcher(normalized).matches()) {
            return false;
        }
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
    }

    public static String last4(String normalized) {
        return normalized.substring(normalized.length() - 4);
    }

    /** The masked form shown by default (SECURITY.md §7.4). */
    public static String masked(String last4) {
        return "****" + last4;
    }
}
