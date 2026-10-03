package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.SETTINGS;

import com.erp.sales.application.SalesViews;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Sales settings (one row per company once changed; defaults otherwise). */
@Repository
public class SalesSettingsRepository {

    private final DSLContext dsl;

    public SalesSettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SalesViews.Settings> find(UUID companyId) {
        return dsl.selectFrom(SETTINGS)
                .where(SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new SalesViews.Settings(
                        r.getDefaultInvoicePolicy(),
                        r.getCreditCheckMode(),
                        r.getQuotationValidityDays(),
                        r.getReserveOnConfirm(),
                        r.getDiscountApprovalThresholdPercent(),
                        r.getVersion()));
    }

    /** Inserts (expected version 0) or updates the settings; false on a version conflict. */
    public boolean save(UUID companyId, int expectedVersion, SalesViews.Settings s, UUID actor) {
        if (expectedVersion == 0) {
            return dsl.insertInto(SETTINGS)
                            .set(SETTINGS.COMPANY_ID, companyId)
                            .set(SETTINGS.DEFAULT_INVOICE_POLICY, s.defaultInvoicePolicy())
                            .set(SETTINGS.CREDIT_CHECK_MODE, s.creditCheckMode())
                            .set(SETTINGS.QUOTATION_VALIDITY_DAYS, s.quotationValidityDays())
                            .set(SETTINGS.RESERVE_ON_CONFIRM, s.reserveOnConfirm())
                            .set(SETTINGS.DISCOUNT_APPROVAL_THRESHOLD_PERCENT, s.discountApprovalThresholdPercent())
                            .set(SETTINGS.CREATED_BY, actor)
                            .set(SETTINGS.UPDATED_BY, actor)
                            .set(SETTINGS.VERSION, 1)
                            .onConflictDoNothing()
                            .execute()
                    == 1;
        }
        return dsl.update(SETTINGS)
                        .set(SETTINGS.DEFAULT_INVOICE_POLICY, s.defaultInvoicePolicy())
                        .set(SETTINGS.CREDIT_CHECK_MODE, s.creditCheckMode())
                        .set(SETTINGS.QUOTATION_VALIDITY_DAYS, s.quotationValidityDays())
                        .set(SETTINGS.RESERVE_ON_CONFIRM, s.reserveOnConfirm())
                        .set(SETTINGS.DISCOUNT_APPROVAL_THRESHOLD_PERCENT, s.discountApprovalThresholdPercent())
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, expectedVersion + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }
}
