package com.erp.org.events;

import com.erp.platform.events.DomainEvent;
import java.util.UUID;

/**
 * {@code org.company.created} (ARCHITECTURE.md §7), schema version 1: published in the transaction
 * that creates the company. Accounting seeds the company's chart of accounts, mappings, journals and
 * first fiscal year from it synchronously (ADR-038).
 */
public record CompanyCreated(
        EventMetadata metadata,
        UUID companyId,
        String code,
        String baseCurrency,
        String countryCode,
        int fiscalYearStartMonth,
        String timezone)
        implements DomainEvent {

    public static final String TYPE = "org.company.created";
    public static final int SCHEMA_VERSION = 1;
}
