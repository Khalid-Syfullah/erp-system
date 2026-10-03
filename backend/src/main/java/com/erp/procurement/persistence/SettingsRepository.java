package com.erp.procurement.persistence;

import static com.erp.db.procurement.Tables.SETTINGS;

import com.erp.procurement.application.ProcurementViews;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Procurement settings (one row per company once changed; defaults otherwise). */
@Repository
public class SettingsRepository {

    private final DSLContext dsl;

    public SettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<ProcurementViews.Settings> find(UUID companyId) {
        return dsl.selectFrom(SETTINGS)
                .where(SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new ProcurementViews.Settings(
                        r.getPoApprovalThresholdBase(),
                        r.getPriceMatchTolerancePercent(),
                        r.getQtyMatchTolerancePercent(),
                        r.getRequireReceiptBeforeBill(),
                        r.getVersion()));
    }

    /** Inserts (expected version 0) or updates the settings; false on a version conflict. */
    public boolean save(
            UUID companyId,
            int expectedVersion,
            @Nullable BigDecimal threshold,
            BigDecimal priceTolerance,
            BigDecimal qtyTolerance,
            UUID actor) {
        if (expectedVersion == 0) {
            return dsl.insertInto(SETTINGS)
                            .set(SETTINGS.COMPANY_ID, companyId)
                            .set(SETTINGS.PO_APPROVAL_THRESHOLD_BASE, threshold)
                            .set(SETTINGS.PRICE_MATCH_TOLERANCE_PERCENT, priceTolerance)
                            .set(SETTINGS.QTY_MATCH_TOLERANCE_PERCENT, qtyTolerance)
                            .set(SETTINGS.CREATED_BY, actor)
                            .set(SETTINGS.UPDATED_BY, actor)
                            .set(SETTINGS.VERSION, 1)
                            .onConflictDoNothing()
                            .execute()
                    == 1;
        }
        return dsl.update(SETTINGS)
                        .set(SETTINGS.PO_APPROVAL_THRESHOLD_BASE, threshold)
                        .set(SETTINGS.PRICE_MATCH_TOLERANCE_PERCENT, priceTolerance)
                        .set(SETTINGS.QTY_MATCH_TOLERANCE_PERCENT, qtyTolerance)
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, expectedVersion + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }
}
