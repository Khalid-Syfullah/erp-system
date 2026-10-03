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

    /** IDs of all companies (for per-company maintenance jobs). */
    List<UUID> allCompanyIds();

    Optional<CompanyProfile> companyProfile(UUID companyId);

    Optional<TaxCodeSummary> taxCode(UUID companyId, UUID taxCodeId);

    /** Branches of the company by ID (unknown IDs are skipped). */
    java.util.Map<UUID, BranchSummary> branches(UUID companyId, Collection<UUID> branchIds);

    /**
     * The exchange rate (1 unit of the currency = rate × base currency) for a document date: the latest
     * rate on or before it (G-13); {@code 1} for the base currency; empty when no rate exists.
     */
    Optional<java.math.BigDecimal> exchangeRate(UUID companyId, String currencyCode, java.time.LocalDate date);

    Optional<CurrencyInfo> currency(String currencyCode);

    Optional<PaymentTermsSummary> paymentTerms(UUID companyId, UUID paymentTermsId);

    boolean countryExists(String countryCode);
}
