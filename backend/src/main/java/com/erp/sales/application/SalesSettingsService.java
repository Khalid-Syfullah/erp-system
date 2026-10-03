package com.erp.sales.application;

import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.sales.persistence.SalesSettingsRepository;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales settings (DATABASE.md §5.7): the default invoice policy (SAL-5), the credit check mode
 * (SAL-2), the quotation validity, reservation on confirm (SAL-3) and the discount threshold above
 * which a line discount needs {@code sales.order.discount_high} (SAL-1).
 */
@Service
public class SalesSettingsService {

    static final SalesViews.Settings DEFAULTS = new SalesViews.Settings("DELIVERED", "WARN", 30, true, null, 0);

    private final SalesSettingsRepository settings;
    private final AuditPort audit;

    SalesSettingsService(SalesSettingsRepository settings, AuditPort audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SalesViews.Settings get() {
        return current(CurrentContext.requireCompany());
    }

    SalesViews.Settings current(UUID companyId) {
        return settings.find(companyId).orElse(DEFAULTS);
    }

    @Transactional
    public SalesViews.Settings replace(@Nullable String ifMatch, SalesViews.Settings next) {
        UUID companyId = CurrentContext.requireCompany();
        SalesViews.Settings current = current(companyId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!settings.save(
                companyId,
                current.version(),
                next,
                CurrentContext.requireActor().userId())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The settings were modified concurrently.");
        }
        SalesViews.Settings after = current(companyId);
        audit.record(AuditEvent.builder("CONFIG_CHANGE", "sales")
                .entity("sales_settings", companyId, null)
                .change("defaultInvoicePolicy", current.defaultInvoicePolicy(), after.defaultInvoicePolicy())
                .change("creditCheckMode", current.creditCheckMode(), after.creditCheckMode())
                .change("quotationValidityDays", current.quotationValidityDays(), after.quotationValidityDays())
                .change("reserveOnConfirm", current.reserveOnConfirm(), after.reserveOnConfirm())
                .change(
                        "discountApprovalThresholdPercent",
                        Objects.toString(current.discountApprovalThresholdPercent(), null),
                        Objects.toString(after.discountApprovalThresholdPercent(), null))
                .build());
        return after;
    }
}
