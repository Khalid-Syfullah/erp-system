package com.erp.inventory.application;

import com.erp.inventory.persistence.ReferenceRepository;
import com.erp.inventory.persistence.UomRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Units of measure (global), reason codes and inventory settings. */
@Service
public class InventoryReferenceService {

    /** Settings of a company without a stored row. */
    static final InventoryViews.Settings DEFAULTS =
            new InventoryViews.Settings("MOVING_AVERAGE", false, BigDecimal.ZERO.setScale(4), null, 0);

    private final UomRepository uoms;
    private final ReferenceRepository references;
    private final AuditPort audit;

    InventoryReferenceService(UomRepository uoms, ReferenceRepository references, AuditPort audit) {
        this.uoms = uoms;
        this.references = references;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<InventoryViews.UomCategory> uomCategories() {
        return uoms.categories();
    }

    @Transactional(readOnly = true)
    public List<InventoryViews.Uom> uoms() {
        return uoms.all();
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.ReasonCode> reasonCodes(ListQuery query) {
        return references.listReasonCodes(CurrentContext.requireCompany(), query);
    }

    @Transactional
    public InventoryViews.ReasonCode createReasonCode(String code, String name, String appliesTo) {
        UUID companyId = CurrentContext.requireCompany();
        UUID id = references.insertReasonCode(
                companyId, code, name, appliesTo, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("reason_code", id, code)
                .detail("appliesTo", appliesTo)
                .build());
        return references.findReasonCode(companyId, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public InventoryViews.Settings settings() {
        return references.settings(CurrentContext.requireCompany()).orElse(DEFAULTS);
    }

    /** Replaces the settings (costing method and negative stock are fixed in v1: DATABASE.md §5.5). */
    @Transactional
    public InventoryViews.Settings replaceSettings(
            @Nullable String ifMatch,
            BigDecimal overReceiptTolerancePercent,
            @Nullable BigDecimal adjustmentApprovalThreshold) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Settings current = settings();
        EntityTags.requireMatch(ifMatch, current.version());
        if (!references.saveSettings(
                companyId,
                current.version(),
                overReceiptTolerancePercent,
                adjustmentApprovalThreshold,
                CurrentContext.requireActor().userId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The settings were modified concurrently.");
        }
        InventoryViews.Settings after = settings();
        audit.record(AuditEvent.builder("CONFIG_CHANGE", "inventory")
                .entity("inventory_settings", companyId, null)
                .change(
                        "overReceiptTolerancePercent",
                        current.overReceiptTolerancePercent().toPlainString(),
                        after.overReceiptTolerancePercent().toPlainString())
                .change(
                        "adjustmentApprovalThreshold",
                        Objects.toString(current.adjustmentApprovalThreshold(), null),
                        Objects.toString(after.adjustmentApprovalThreshold(), null))
                .build());
        return after;
    }
}
