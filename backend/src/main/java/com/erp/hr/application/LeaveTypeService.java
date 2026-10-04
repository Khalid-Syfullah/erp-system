package com.erp.hr.application;

import com.erp.hr.persistence.LeaveTypeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Leave types with their entitlement and accrual rules (PRODUCT_SPEC.md §10.1). */
@Service
public class LeaveTypeService {

    static final Set<String> PATCHABLE = Set.of(
            "name",
            "isPaid",
            "annualEntitlementDays",
            "accrualMethod",
            "maxCarryForwardDays",
            "allowNegativeBalance",
            "isActive");
    private static final BigDecimal MAX_DAYS = BigDecimal.valueOf(366);

    private final LeaveTypeRepository types;
    private final HrContext context;
    private final AuditPort audit;

    LeaveTypeService(LeaveTypeRepository types, HrContext context, AuditPort audit) {
        this.types = types;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<HrViews.LeaveType> list(ListQuery query) {
        return types.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public HrViews.LeaveType get(UUID id) {
        return types.find(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public HrViews.LeaveType create(HrCommands.LeaveType command) {
        UUID id = types.insert(context.companyId(), command, context.actor());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("leave_type", id, command.code())
                .detail("annualEntitlementDays", command.annualEntitlementDays().toPlainString())
                .detail("accrualMethod", command.accrualMethod())
                .build());
        return get(id);
    }

    @Transactional
    public HrViews.LeaveType patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        HrViews.LeaveType current = types.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var paid = patch.bool("isPaid");
        var entitlement = patch.decimal("annualEntitlementDays", BigDecimal.ZERO, MAX_DAYS, 2);
        var method = patch.text(
                "accrualMethod",
                true,
                10,
                v -> v.equals("ANNUAL") || v.equals("MONTHLY") ? null : "must be ANNUAL or MONTHLY");
        var carry = patch.decimal("maxCarryForwardDays", BigDecimal.ZERO, MAX_DAYS, 2);
        var negative = patch.bool("allowNegativeBalance");
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        HrCommands.LeaveType next = new HrCommands.LeaveType(
                current.code(),
                name.orElse(current.name()),
                Boolean.TRUE.equals(paid.orElse(current.paid())),
                entitlement.orElse(current.annualEntitlementDays()),
                method.orElse(current.accrualMethod()),
                carry.orElse(current.maxCarryForwardDays()),
                Boolean.TRUE.equals(negative.orElse(current.allowNegativeBalance())),
                Boolean.TRUE.equals(active.orElse(current.active())));
        if (!types.update(companyId, id, current.version(), context.actor(), next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The leave type was modified concurrently.");
        }
        HrViews.LeaveType after = get(id);
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("leave_type", id, current.code())
                .change("name", current.name(), after.name())
                .change("annualEntitlementDays", current.annualEntitlementDays(), after.annualEntitlementDays())
                .change("accrualMethod", current.accrualMethod(), after.accrualMethod())
                .change("maxCarryForwardDays", current.maxCarryForwardDays(), after.maxCarryForwardDays())
                .change("allowNegativeBalance", current.allowNegativeBalance(), after.allowNegativeBalance())
                .change("isActive", current.active(), after.active())
                .build());
        return after;
    }
}
