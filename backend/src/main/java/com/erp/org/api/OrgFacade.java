package com.erp.org.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to the organization structure for other modules. */
public interface OrgFacade {

    Optional<CompanySummary> findCompany(UUID companyId);

    /** Companies with the given IDs (unknown IDs are skipped), ordered by code. */
    List<CompanySummary> findCompanies(Collection<UUID> companyIds);

    /**
     * The branch, locked {@code FOR SHARE} until the caller's transaction ends, so that a concurrent
     * deactivation waits and then sees the caller's new use (DATABASE.md §9). Empty if the branch
     * does not belong to the company.
     */
    Optional<BranchSummary> branchForUse(UUID companyId, UUID branchId);

    /** Like {@link #branchForUse} for a department. */
    Optional<DepartmentSummary> departmentForUse(UUID companyId, UUID departmentId);
}
