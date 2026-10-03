package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.REASON_CODES;
import static com.erp.db.inventory.Tables.SETTINGS;

import com.erp.db.inventory.tables.records.ReasonCodesRecord;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Reason codes and inventory settings of a company. */
@Repository
public class ReferenceRepository {

    private static final ListBinding REASON_BINDING = ListBinding.builder(InventoryListings.REASON_CODES)
            .field("code", REASON_CODES.CODE)
            .field("name", REASON_CODES.NAME)
            .field("appliesTo", REASON_CODES.APPLIES_TO)
            .field("isActive", REASON_CODES.IS_ACTIVE)
            .tiebreaker(REASON_CODES.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ReferenceRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insertReasonCode(UUID companyId, String code, String name, String appliesTo, UUID actor) {
        return dsl.insertInto(REASON_CODES)
                .set(REASON_CODES.COMPANY_ID, companyId)
                .set(REASON_CODES.CODE, code)
                .set(REASON_CODES.NAME, name)
                .set(REASON_CODES.APPLIES_TO, appliesTo)
                .set(REASON_CODES.CREATED_BY, actor)
                .set(REASON_CODES.UPDATED_BY, actor)
                .returning(REASON_CODES.ID)
                .fetchOne(REASON_CODES.ID);
    }

    public Optional<InventoryViews.ReasonCode> findReasonCode(UUID companyId, UUID id) {
        return dsl.selectFrom(REASON_CODES)
                .where(REASON_CODES.COMPANY_ID.eq(companyId))
                .and(REASON_CODES.ID.eq(id))
                .fetchOptional(ReferenceRepository::toReason);
    }

    public PageResponse<InventoryViews.ReasonCode> listReasonCodes(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                REASON_CODES,
                REASON_CODES.COMPANY_ID.eq(companyId),
                query,
                REASON_BINDING,
                ReferenceRepository::toReason);
    }

    public Optional<InventoryViews.Settings> settings(UUID companyId) {
        return dsl.selectFrom(SETTINGS)
                .where(SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new InventoryViews.Settings(
                        r.getCostingMethod(),
                        r.getAllowNegativeStock(),
                        r.getOverReceiptTolerancePercent(),
                        r.getAdjustmentApprovalThreshold(),
                        r.getVersion()));
    }

    /** Creates (expected version 0) or updates the settings row. */
    public boolean saveSettings(
            UUID companyId,
            int expectedVersion,
            BigDecimal overReceiptTolerancePercent,
            @Nullable BigDecimal adjustmentApprovalThreshold,
            UUID actor) {
        if (expectedVersion == 0) {
            return dsl.insertInto(SETTINGS)
                            .set(SETTINGS.COMPANY_ID, companyId)
                            .set(SETTINGS.OVER_RECEIPT_TOLERANCE_PERCENT, overReceiptTolerancePercent)
                            .set(SETTINGS.ADJUSTMENT_APPROVAL_THRESHOLD, adjustmentApprovalThreshold)
                            .set(SETTINGS.VERSION, 1)
                            .set(SETTINGS.CREATED_BY, actor)
                            .set(SETTINGS.UPDATED_BY, actor)
                            .onConflictDoNothing()
                            .execute()
                    == 1;
        }
        return dsl.update(SETTINGS)
                        .set(SETTINGS.OVER_RECEIPT_TOLERANCE_PERCENT, overReceiptTolerancePercent)
                        .set(SETTINGS.ADJUSTMENT_APPROVAL_THRESHOLD, adjustmentApprovalThreshold)
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, expectedVersion + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    static InventoryViews.ReasonCode toReason(ReasonCodesRecord r) {
        return new InventoryViews.ReasonCode(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getAppliesTo(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getVersion());
    }
}
