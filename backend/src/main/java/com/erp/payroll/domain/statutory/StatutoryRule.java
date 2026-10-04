package com.erp.payroll.domain.statutory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The extension point for country-specific payroll rules (PRODUCT_SPEC.md §11.1): a {@code STATUTORY}
 * component names a rule by its {@link #code()}, and the calculation engine asks the rule for the
 * component's amount. A rule is a pure function of its {@link Context} — no clock, no database — so
 * a payroll calculation stays deterministic and testable. New rules (income tax tables, social
 * security with ceilings, …) are added as further Spring beans implementing this interface, in their
 * own package; nothing else in the engine changes.
 */
public interface StatutoryRule {

    /** The code components refer to ({@code ^[A-Z0-9_]{1,40}$}). */
    String code();

    /** A short description for administrators. */
    String description();

    /**
     * The unrounded amount for one employee and period; the engine rounds it to the currency.
     *
     * @return a non-negative amount
     */
    BigDecimal calculate(Context context);

    /**
     * What a rule may use.
     *
     * @param gross the gross pay of the payslip (sum of earnings)
     * @param taxableGross the sum of taxable earnings
     * @param base the prorated base pay
     * @param rate the component's resolved rate (a percentage), if any
     * @param amount the component's resolved amount (for example a ceiling), if any
     * @param countryCode the company's country (ISO 3166)
     * @param prorationFactor days paid ÷ days of the period
     */
    record Context(
            UUID employeeId,
            String componentCode,
            LocalDate periodStart,
            LocalDate periodEnd,
            String currencyCode,
            String countryCode,
            BigDecimal gross,
            BigDecimal taxableGross,
            BigDecimal base,
            @Nullable BigDecimal rate,
            @Nullable BigDecimal amount,
            BigDecimal prorationFactor) {}
}
