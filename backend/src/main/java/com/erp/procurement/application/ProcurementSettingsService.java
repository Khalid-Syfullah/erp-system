package com.erp.procurement.application;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.procurement.persistence.SettingsRepository;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procurement settings (DATABASE.md §5.6): the PO approval threshold (above it approval needs
 * {@code approve_high}) and the three-way match tolerances. Receipt before bill is fixed in v1.
 */
@Service
public class ProcurementSettingsService {

    static final ProcurementViews.Settings DEFAULTS =
            new ProcurementViews.Settings(null, BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4), true, 0);

    private final SettingsRepository settings;
    private final AuditPort audit;

    ProcurementSettingsService(SettingsRepository settings, AuditPort audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ProcurementViews.Settings get() {
        return current(CurrentContext.requireCompany());
    }

    ProcurementViews.Settings current(UUID companyId) {
        return settings.find(companyId).orElse(DEFAULTS);
    }

    @Transactional
    public ProcurementViews.Settings replace(
            @Nullable String ifMatch,
            @Nullable BigDecimal threshold,
            BigDecimal priceTolerancePercent,
            BigDecimal qtyTolerancePercent) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Settings current = current(companyId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!settings.save(
                companyId,
                current.version(),
                threshold,
                priceTolerancePercent,
                qtyTolerancePercent,
                CurrentContext.requireActor().userId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The settings were modified concurrently.");
        }
        ProcurementViews.Settings after = current(companyId);
        audit.record(AuditEvent.builder("CONFIG_CHANGE", "procurement")
                .entity("procurement_settings", companyId, null)
                .change(
                        "poApprovalThresholdBase",
                        Objects.toString(current.poApprovalThresholdBase(), null),
                        Objects.toString(after.poApprovalThresholdBase(), null))
                .change(
                        "priceMatchTolerancePercent",
                        current.priceMatchTolerancePercent().toPlainString(),
                        after.priceMatchTolerancePercent().toPlainString())
                .change(
                        "qtyMatchTolerancePercent",
                        current.qtyMatchTolerancePercent().toPlainString(),
                        after.qtyMatchTolerancePercent().toPlainString())
                .build());
        return after;
    }
}
