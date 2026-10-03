package com.erp.org.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** An exchange rate: 1 unit of {@code currencyCode} = {@code rate} units of the company's base currency. */
public record ExchangeRateView(
        UUID id,
        UUID companyId,
        String currencyCode,
        LocalDate rateDate,
        BigDecimal rate,
        String source,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
