package com.erp.org.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A tax code: one rate, line-level, for sales, purchases or both (ADR-020). */
public record TaxCodeView(
        UUID id,
        UUID companyId,
        String code,
        String name,
        String scope,
        BigDecimal ratePercent,
        boolean exempt,
        @Nullable LocalDate validFrom,
        @Nullable LocalDate validTo,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
