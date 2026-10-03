package com.erp.support;

import com.erp.org.api.TaxCodeUsage;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestComponent;

/** Stands in for the document modules (Sales, Procurement) that will report tax code use. */
@TestComponent
public class TestTaxCodeUsage implements TaxCodeUsage {

    private final Set<UUID> used = ConcurrentHashMap.newKeySet();

    public void markUsed(UUID taxCodeId) {
        used.add(taxCodeId);
    }

    @Override
    public boolean isUsed(UUID companyId, UUID taxCodeId) {
        return used.contains(taxCodeId);
    }
}
