package com.erp.hr.application;

import com.erp.hr.persistence.PublicHolidayRepository;
import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Public holidays, for the whole company or one branch (PRODUCT_SPEC.md §10.1). They are excluded
 * from leave day counts; changing them does not recount leave already requested.
 */
@Service
public class PublicHolidayService {

    private final PublicHolidayRepository holidays;
    private final OrgFacade org;
    private final HrContext context;
    private final AuditPort audit;

    PublicHolidayService(PublicHolidayRepository holidays, OrgFacade org, HrContext context, AuditPort audit) {
        this.holidays = holidays;
        this.org = org;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<HrViews.Holiday> list(ListQuery query) {
        return holidays.list(context.companyId(), query);
    }

    @Transactional
    public HrViews.Holiday create(@Nullable UUID branchId, LocalDate date, String name) {
        UUID companyId = context.companyId();
        if (branchId != null && org.branchForUse(companyId, branchId).isEmpty()) {
            throw ApiException.validationFailed(
                    "The holiday is invalid.",
                    List.of(FieldViolation.atPointer("/branchId", "UNKNOWN_BRANCH", "is not an active branch")));
        }
        UUID id = holidays.insert(companyId, branchId, date, name.strip(), context.actor());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("public_holiday", id, date.toString())
                .detail("name", name.strip())
                .detail("branchId", branchId)
                .build());
        return holidays.find(companyId, id).orElseThrow();
    }

    @Transactional
    public HrViews.Holiday patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        HrViews.Holiday current = holidays.find(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, Set.of("date", "name"));
        var date = patch.date("date", true);
        var name = patch.text("name", true, 100);
        patch.throwIfInvalid();
        LocalDate nextDate = date.orElse(current.date());
        String nextName = name.orElse(current.name());
        if (!holidays.update(companyId, id, current.version(), context.actor(), nextDate, nextName)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The holiday was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("public_holiday", id, nextDate.toString())
                .change("date", current.date().toString(), nextDate.toString())
                .change("name", current.name(), nextName)
                .build());
        return holidays.find(companyId, id).orElseThrow();
    }

    @Transactional
    public void delete(UUID id) {
        UUID companyId = context.companyId();
        HrViews.Holiday current = holidays.find(companyId, id).orElseThrow(ApiException::notFound);
        holidays.delete(companyId, id);
        audit.record(AuditEvent.builder("DELETE", "hr")
                .entity("public_holiday", id, current.date().toString())
                .build());
    }
}
