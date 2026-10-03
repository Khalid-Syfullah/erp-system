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
}
