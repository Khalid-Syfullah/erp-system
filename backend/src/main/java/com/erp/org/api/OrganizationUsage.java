package com.erp.org.api;

import java.util.List;
import java.util.UUID;

/**
 * Port (dependency inversion, ARCHITECTURE.md §5.1) through which downstream modules report that they
 * still use a branch or department. Org refuses to deactivate a unit while any implementation reports
 * a use (PRODUCT_SPEC.md §4.2). Implementations run inside Org's transaction, after Org has locked the
 * unit, and must only read their own tables.
 */
public interface OrganizationUsage {

    /** Human-readable descriptions of current or future uses of the branch; empty when unused. */
    List<String> branchUsage(UUID companyId, UUID branchId);

    /** Human-readable descriptions of current or future uses of the department; empty when unused. */
    List<String> departmentUsage(UUID companyId, UUID departmentId);
}
