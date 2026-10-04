package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.SETTINGS;

import com.erp.accounting.application.AccountingViews;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Accounting settings: one row per company, written when the company's accounting is set up. */
@Repository
public class AccountingSettingsRepository {

    private final DSLContext dsl;

    public AccountingSettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<AccountingViews.Settings> find(UUID companyId) {
        return dsl.selectFrom(SETTINGS)
                .where(SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new AccountingViews.Settings(
                        r.getRetainedEarningsAccountId(),
                        r.getAllowManualEntriesInSoftClosed(),
                        r.getMaxRoundingDifferenceMinorUnits(),
                        r.getManualEntryApprovalThresholdBase(),
                        r.getCoaTemplate(),
                        r.getVersion()));
    }

    /** Inserts the settings unless they exist; false when another transaction set the company up. */
    public boolean insert(UUID companyId, UUID retainedEarningsAccountId, @Nullable UUID actor) {
        return dsl.insertInto(SETTINGS)
                        .set(SETTINGS.COMPANY_ID, companyId)
                        .set(SETTINGS.RETAINED_EARNINGS_ACCOUNT_ID, retainedEarningsAccountId)
                        .set(SETTINGS.CREATED_BY, actor)
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, 1)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    /** Bumps the settings version: the ETag of the company's account mappings (ADR-038). */
    public boolean touch(UUID companyId, int version, UUID actor) {
        return dsl.update(SETTINGS)
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, version + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean update(
            UUID companyId,
            int version,
            UUID retainedEarningsAccountId,
            boolean allowSoftClosed,
            int maxRounding,
            @Nullable BigDecimal threshold,
            UUID actor) {
        return dsl.update(SETTINGS)
                        .set(SETTINGS.RETAINED_EARNINGS_ACCOUNT_ID, retainedEarningsAccountId)
                        .set(SETTINGS.ALLOW_MANUAL_ENTRIES_IN_SOFT_CLOSED, allowSoftClosed)
                        .set(SETTINGS.MAX_ROUNDING_DIFFERENCE_MINOR_UNITS, maxRounding)
                        .set(SETTINGS.MANUAL_ENTRY_APPROVAL_THRESHOLD_BASE, threshold)
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, version + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(version))
                        .execute()
                == 1;
    }
}
