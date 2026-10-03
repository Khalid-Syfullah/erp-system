package com.erp.org.application;

import com.erp.org.api.BranchSummary;
import com.erp.org.api.CompanySummary;
import com.erp.org.api.DepartmentSummary;
import com.erp.org.api.OrgFacade;
import com.erp.org.persistence.BranchRepository;
import com.erp.org.persistence.CompanyRepository;
import com.erp.org.persistence.DepartmentRepository;
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

    OrgDirectory(CompanyRepository companies, BranchRepository branches, DepartmentRepository departments) {
        this.companies = companies;
        this.branches = branches;
        this.departments = departments;
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
}
