package com.erp.org.application;

import com.erp.org.api.CompanySummary;
import com.erp.org.api.OrgFacade;
import com.erp.org.persistence.CompanyRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link OrgFacade} implementation. Companies are not company-scoped (no RLS), so no context is needed. */
@Service
class OrgDirectory implements OrgFacade {

    private final CompanyRepository companies;

    OrgDirectory(CompanyRepository companies) {
        this.companies = companies;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanySummary> findCompany(UUID companyId) {
        return companies.summaries(List.of(companyId)).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompanySummary> findCompanies(Collection<UUID> companyIds) {
        return companies.summaries(companyIds);
    }
}
