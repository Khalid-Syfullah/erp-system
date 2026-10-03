package com.erp.org.application;

import com.erp.org.persistence.BranchRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Branches of the active company (API.md §17.3). Users with a restricted branch scope see and change
 * only their branches; others are answered with 404 (SECURITY.md §4.4).
 */
@Service
public class BranchService {

    static final Set<String> PATCHABLE =
            Set.of("name", "addressLine1", "addressLine2", "city", "region", "postalCode", "countryCode");

    private final BranchRepository branches;
    private final AuditPort audit;

    public BranchService(BranchRepository branches, AuditPort audit) {
        this.branches = branches;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<BranchView> list(ListQuery query) {
        RequestContext context = CurrentContext.require();
        return branches.list(CurrentContext.requireCompany(), context.branchScope(), query);
    }

    @Transactional(readOnly = true)
    public BranchView get(UUID branchId) {
        RequestContext context = CurrentContext.require();
        return branches.find(CurrentContext.requireCompany(), branchId, context.branchScope())
                .orElseThrow(ApiException::notFound);
    }

    /** Only users with access to all branches may create branches (a new branch is outside any scope). */
    @Transactional
    public BranchView create(CompanyCommands.CreateBranch command) {
        RequestContext context = CurrentContext.require();
        if (context.branchScope() != null) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN, "Creating branches requires access to all branches of the company.");
        }
        if (command.countryCode() != null && !branches.countryExists(command.countryCode())) {
            throw ApiException.validationFailed(
                    "The branch is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/countryCode", "UNKNOWN_COUNTRY", "is not a known country code")));
        }
        UUID companyId = CurrentContext.requireCompany();
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = branches.insert(companyId, command, actor);
        audit.record(AuditEvent.builder("CREATE", "org")
                .entity("branch", id, command.code())
                .detail("code", command.code())
                .detail("name", command.name())
                .build());
        return get(id);
    }

    @Transactional
    public BranchView patch(UUID branchId, @Nullable String ifMatch, JsonNode document) {
        BranchView current = get(branchId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<String> name = patch.text("name", true, 100);
        MergePatch.Member<String> line1 = patch.text("addressLine1", false, 200);
        MergePatch.Member<String> line2 = patch.text("addressLine2", false, 200);
        MergePatch.Member<String> city = patch.text("city", false, 100);
        MergePatch.Member<String> region = patch.text("region", false, 100);
        MergePatch.Member<String> postalCode = patch.text("postalCode", false, 20);
        MergePatch.Member<String> country = patch.text(
                "countryCode", false, 2, c -> branches.countryExists(c) ? null : "is not a known country code");
        patch.throwIfInvalid();
        update(
                current,
                new CompanyCommands.UpdateBranch(
                        name.orElse(current.name()),
                        line1.orElse(current.addressLine1()),
                        line2.orElse(current.addressLine2()),
                        city.orElse(current.city()),
                        region.orElse(current.region()),
                        postalCode.orElse(current.postalCode()),
                        country.orElse(current.countryCode()),
                        current.active()));
        BranchView after = get(branchId);
        audit.record(AuditEvent.builder("UPDATE", "org")
                .entity("branch", branchId, after.code())
                .change("name", current.name(), after.name())
                .change("addressLine1", current.addressLine1(), after.addressLine1())
                .change("addressLine2", current.addressLine2(), after.addressLine2())
                .change("city", current.city(), after.city())
                .change("region", current.region(), after.region())
                .change("postalCode", current.postalCode(), after.postalCode())
                .change("countryCode", current.countryCode(), after.countryCode())
                .build());
        return after;
    }

    @Transactional
    public BranchView setActive(UUID branchId, @Nullable String ifMatch, boolean active) {
        BranchView current = get(branchId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The branch is already " + (active ? "active." : "inactive."));
        }
        update(
                current,
                new CompanyCommands.UpdateBranch(
                        current.name(),
                        current.addressLine1(),
                        current.addressLine2(),
                        current.city(),
                        current.region(),
                        current.postalCode(),
                        current.countryCode(),
                        active));
        audit.record(AuditEvent.builder("STATE_CHANGE", "org")
                .entity("branch", branchId, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return get(branchId);
    }

    private void update(BranchView current, CompanyCommands.UpdateBranch state) {
        UUID actor = CurrentContext.requireActor().userId();
        if (!branches.update(current.companyId(), current.id(), current.version(), actor, state)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The branch was modified concurrently.");
        }
    }
}
