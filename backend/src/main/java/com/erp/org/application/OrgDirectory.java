package com.erp.org.application;

import com.erp.org.api.BranchSummary;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.CompanySummary;
import com.erp.org.api.CurrencyInfo;
import com.erp.org.api.DepartmentSummary;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.PaymentTermsSummary;
import com.erp.org.api.TaxCodeSummary;
import com.erp.org.persistence.BranchRepository;
import com.erp.org.persistence.CompanyRepository;
import com.erp.org.persistence.DepartmentRepository;
import com.erp.org.persistence.ExchangeRateRepository;
import com.erp.org.persistence.PaymentTermsRepository;
import com.erp.org.persistence.TaxCodeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final ExchangeRateRepository exchangeRates;
    private final PaymentTermsRepository paymentTerms;

    OrgDirectory(
            CompanyRepository companies,
            BranchRepository branches,
            DepartmentRepository departments,
            TaxCodeRepository taxCodes,
            ExchangeRateRepository exchangeRates,
            PaymentTermsRepository paymentTerms) {
        this.companies = companies;
        this.branches = branches;
        this.departments = departments;
        this.taxCodes = taxCodes;
        this.exchangeRates = exchangeRates;
        this.paymentTerms = paymentTerms;
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
                .map(t -> new TaxCodeSummary(
                        t.id(),
                        t.code(),
                        t.scope(),
                        t.ratePercent(),
                        t.exempt(),
                        t.validFrom(),
                        t.validTo(),
                        t.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, BranchSummary> branches(UUID companyId, Collection<UUID> branchIds) {
        java.util.Map<UUID, BranchSummary> result = new java.util.HashMap<>();
        branches.findAll(companyId, branchIds)
                .forEach(b -> result.put(b.id(), new BranchSummary(b.id(), b.code(), b.name(), b.active())));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> exchangeRate(UUID companyId, String currencyCode, LocalDate date) {
        Optional<CompanyProfile> company = companies.profile(companyId);
        if (company.isEmpty()) {
            return Optional.empty();
        }
        if (company.get().baseCurrency().equals(currencyCode)) {
            return Optional.of(BigDecimal.ONE);
        }
        return exchangeRates.latestOnOrBefore(companyId, currencyCode, date).map(ExchangeRateView::rate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CurrencyInfo> currency(String currencyCode) {
        return companies.currency(currencyCode);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentTermsSummary> paymentTerms(UUID companyId, UUID paymentTermsId) {
        return paymentTerms
                .find(companyId, paymentTermsId)
                .map(t -> new PaymentTermsSummary(t.id(), t.code(), t.name(), t.dueDays(), t.dueBasis(), t.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean countryExists(String countryCode) {
        return companies.countryExists(countryCode);
    }
}
