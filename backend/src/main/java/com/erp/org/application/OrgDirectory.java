package com.erp.org.application;

import com.erp.org.api.BranchSummary;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.CompanySummary;
import com.erp.org.api.DepartmentSummary;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCodeSummary;
import com.erp.org.persistence.BranchRepository;
import com.erp.org.persistence.CompanyRepository;
import com.erp.org.persistence.DepartmentRepository;
import com.erp.org.persistence.TaxCodeRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OrgFacade} implementation. Companies are not company-scoped (no RLS), so no context is
 * needed for them; branch and department lookups run in the caller's company transaction.
 */
@Service
class OrgDirectory implements OrgFacade {

    private final CompanyRepository companies;
    private final BranchRepository branches;
    private final DepartmentRepository departments;
    private final TaxCodeRepository taxCodes;

    OrgDirectory(
            CompanyRepository companies,
            BranchRepository branches,
            DepartmentRepository departments,
            TaxCodeRepository taxCodes) {
        this.companies = companies;
        this.branches = branches;
        this.departments = departments;
        this.taxCodes = taxCodes;
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

    @Override
    @Transactional
    public Optional<BranchSummary> branchForUse(UUID companyId, UUID branchId) {
        return branches.lockForUse(companyId, branchId, null)
                .map(b -> new BranchSummary(b.id(), b.code(), b.name(), b.active()));
    }

    @Override
    @Transactional
    public Optional<DepartmentSummary> departmentForUse(UUID companyId, UUID departmentId) {
        return departments
                .lockForUse(companyId, departmentId)
                .map(d -> new DepartmentSummary(d.id(), d.code(), d.name(), d.parentId(), d.branchId(), d.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> allCompanyIds() {
        return companies.allIds();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompanyProfile> companyProfile(UUID companyId) {
        return companies.profile(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaxCodeSummary> taxCode(UUID companyId, UUID taxCodeId) {
        return taxCodes.find(companyId, taxCodeId)
                .map(t -> new TaxCodeSummary(t.id(), t.code(), t.scope(), t.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, BranchSummary> branches(UUID companyId, Collection<UUID> branchIds) {
        java.util.Map<UUID, BranchSummary> result = new java.util.HashMap<>();
        branches.findAll(companyId, branchIds)
                .forEach(b -> result.put(b.id(), new BranchSummary(b.id(), b.code(), b.name(), b.active())));
        return result;
    }
}
