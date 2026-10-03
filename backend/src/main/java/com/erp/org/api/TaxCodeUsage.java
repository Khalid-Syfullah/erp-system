package com.erp.org.api;

import java.util.UUID;

/**
 * Port through which document modules report that a tax code has been used. A used tax code keeps
 * its rate, scope and exemption flag forever (PRODUCT_SPEC.md §4.2); changes need a new code with
 * validity dates. Sales, Procurement and Accounting implement it when they arrive.
 */
public interface TaxCodeUsage {

    boolean isUsed(UUID companyId, UUID taxCodeId);
}
