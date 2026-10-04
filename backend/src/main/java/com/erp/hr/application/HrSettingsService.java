package com.erp.hr.application;

import com.erp.hr.persistence.HrSettingsRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HR settings of the company: weekend days (PRODUCT_SPEC.md §10.1) and the standard working day. */
@Service
public class HrSettingsService {

    private final HrSettingsRepository settings;
    private final HrContext context;
    private final AuditPort audit;

    HrSettingsService(HrSettingsRepository settings, HrContext context, AuditPort audit) {
        this.settings = settings;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public HrViews.Settings get() {
        return settings.find(context.companyId())
                .orElse(new HrViews.Settings(WorkCalendarService.DEFAULT_WEEKEND, 480, 0));
    }

    @Transactional
    public HrViews.Settings replace(@Nullable String ifMatch, List<Integer> weekendDays, int standardWorkMinutes) {
        UUID companyId = context.companyId();
        if (weekendDays.size() > 6
                || new HashSet<>(weekendDays).size() != weekendDays.size()
                || weekendDays.stream().anyMatch(d -> d < 1 || d > 7)) {
            throw ApiException.validationFailed(
                    "The settings are invalid.",
                    List.of(FieldViolation.atPointer(
                            "/weekendDays", "INVALID_VALUE", "must be distinct ISO days 1–7, at most six")));
        }
        settings.ensure(companyId);
        HrViews.Settings current = settings.find(companyId).orElseThrow();
        EntityTags.requireMatch(ifMatch, current.version());
        List<Integer> sorted = weekendDays.stream().sorted().toList();
        if (!settings.update(companyId, current.version(), context.actor(), sorted, standardWorkMinutes)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The settings were modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("hr_settings", companyId, null)
                .change("weekendDays", current.weekendDays().toString(), sorted.toString())
                .change("standardWorkMinutes", current.standardWorkMinutes(), standardWorkMinutes)
                .build());
        return get();
    }
}
