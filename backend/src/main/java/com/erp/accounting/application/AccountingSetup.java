package com.erp.accounting.application;

import com.erp.accounting.domain.FiscalCalendar;
import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.AccountingSettingsRepository;
import com.erp.accounting.persistence.CalendarRepository;
import com.erp.accounting.persistence.JournalRepository;
import com.erp.accounting.persistence.MappingRepository;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets up a company's accounting (PRODUCT_SPEC.md §8.2, ADR-038): the {@code STANDARD_SME} chart of
 * accounts, the default account mappings, the system journals, the settings and the fiscal year
 * containing the company's current date with its twelve periods. Idempotent: the settings row is
 * the marker, and a company already set up is left alone. Runs in the caller's transaction and the
 * company's context.
 */
@Component
class AccountingSetup {

    private final AccountingSettingsRepository settings;
    private final AccountRepository accounts;
    private final MappingRepository mappings;
    private final JournalRepository journals;
    private final CalendarRepository calendar;
    private final OrgFacade org;
    private final AuditPort audit;
    private final Clock clock;

    AccountingSetup(
            AccountingSettingsRepository settings,
            AccountRepository accounts,
            MappingRepository mappings,
            JournalRepository journals,
            CalendarRepository calendar,
            OrgFacade org,
            AuditPort audit,
            Clock clock) {
        this.settings = settings;
        this.accounts = accounts;
        this.mappings = mappings;
        this.journals = journals;
        this.calendar = calendar;
        this.org = org;
        this.audit = audit;
        this.clock = clock;
    }

    /** Whether the company's accounting is set up. */
    boolean isSetUp(UUID companyId) {
        return settings.find(companyId).isPresent();
    }

    /** Seeds the current company; returns false when it was set up already. */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean seed() {
        UUID companyId = CurrentContext.requireCompany();
        if (isSetUp(companyId)) {
            return false;
        }
        @Nullable UUID actor = CurrentContext.get()
                .filter(c -> c.actor() != null)
                .map(c -> c.actor().userId())
                .orElse(null);
        CompanyProfile profile = org.companyProfile(companyId).orElseThrow();
        Map<String, UUID> byCode = new HashMap<>();
        for (ChartTemplate.AccountRow row : ChartTemplate.ACCOUNTS) {
            byCode.put(
                    row.code(),
                    accounts.insert(
                            companyId,
                            new AccountRepository.Values(
                                    row.code(),
                                    row.name(),
                                    row.type(),
                                    row.subtype(),
                                    null,
                                    true,
                                    row.control(),
                                    true,
                                    null,
                                    null),
                            actor));
        }
        if (!settings.insert(companyId, byCode.get(ChartTemplate.RETAINED_EARNINGS), actor)) {
            throw new IllegalStateException("Accounting of company " + companyId + " was set up concurrently");
        }
        for (Map.Entry<MappingKey, String> mapping : ChartTemplate.DEFAULT_MAPPINGS.entrySet()) {
            mappings.upsert(
                    companyId,
                    mapping.getKey().name(),
                    MappingKey.ScopeType.DEFAULT.name(),
                    null,
                    byCode.get(mapping.getValue()),
                    actor);
        }
        for (ChartTemplate.JournalRow journal : ChartTemplate.JOURNALS) {
            journals.insert(companyId, journal.code(), journal.name(), journal.type(), true, actor);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(profile.timezone())));
        LocalDate start = FiscalCalendar.yearStart(today, profile.fiscalYearStartMonth());
        UUID year =
                calendar.insertYear(companyId, FiscalCalendar.code(start), start, FiscalCalendar.yearEnd(start), actor);
        for (FiscalCalendar.Period period : FiscalCalendar.periods(start)) {
            calendar.insertPeriod(companyId, year, period.number(), period.start(), period.end(), actor);
        }
        audit.record(AuditEvent.builder("SETUP", "accounting")
                .entity("accounting_settings", companyId, "STANDARD_SME")
                .company(companyId)
                .detail("coaTemplate", "STANDARD_SME")
                .detail("accounts", ChartTemplate.ACCOUNTS.size())
                .detail("journals", ChartTemplate.JOURNALS.size())
                .detail("fiscalYear", FiscalCalendar.code(start))
                .build());
        return true;
    }
}
