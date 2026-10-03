package com.erp.org.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Inputs of the tax code and payment terms repositories. */
public final class ReferenceCommands {

    public record TaxCode(
            String code,
            String name,
            String scope,
            BigDecimal ratePercent,
            boolean exempt,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            boolean active) {}

    public record PaymentTerms(String code, String name, int dueDays, String dueBasis, boolean active) {}

    private ReferenceCommands() {}
}
