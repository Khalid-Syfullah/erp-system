package com.erp.org.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Payment terms: due days counted from the document date or from the end of its month. */
public record PaymentTermsView(
        UUID id,
        UUID companyId,
        String code,
        String name,
        int dueDays,
        String dueBasis,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version) {}
