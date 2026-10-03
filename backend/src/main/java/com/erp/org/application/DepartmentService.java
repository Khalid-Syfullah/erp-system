package com.erp.org.application;

import com.erp.org.persistence.BranchRepository;
import com.erp.org.persistence.DepartmentRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Departments of the active company: a tree (cycle-free, enforced here and by a trigger) whose nodes
 * may belong to one branch (PRODUCT_SPEC.md §4). Departments are company-wide resources; linking one
 * to a branch requires that branch to be in the caller's branch scope.
 *
 * <p>Consistency rules: a new or moved department needs an active parent and branch; a department
 * with active sub-departments, or one still used by another module ({@code OrganizationUsage}),
 * cannot be deactivated; reactivation needs an active parent and branch. Every check runs after the
 * rows involved are locked (DATABASE.md §9).
 */
@Service
public class DepartmentService {

    static final Set<String> PATCHABLE = Set.of("name", "parentId", "branchId");

    /** A node of {@code GET …/departments/tree}. */
    public record TreeNode(DepartmentView department, List<TreeNode> children) {}

    private final DepartmentRepository departments;
    private final BranchRepository branches;
    private final OrgUsageChecks usage;
    private final AuditPort audit;

    public DepartmentService(
            DepartmentRepository departments, BranchRepository branches, OrgUsageChecks usage, AuditPort audit) {
        this.departments = departments;
        this.branches = branches;
        this.usage = usage;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<DepartmentView> list(ListQuery query) {
        return departments.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public DepartmentView get(UUID departmentId) {
        return departments.find(CurrentContext.requireCompany(), departmentId).orElseThrow(ApiException::notFound);
    }

    /** The company's department tree; roots and children ordered by code. */
    @Transactional(readOnly = true)
    public List<TreeNode> tree(boolean includeInactive) {
        List<DepartmentView> all = departments.all(CurrentContext.requireCompany(), includeInactive);
        if (all.size() > DepartmentRepository.MAX_TREE_SIZE) {
            throw ApiException.badRequest(
                    "The company has more than " + DepartmentRepository.MAX_TREE_SIZE
                            + " departments; use the paged list with filter[parentId].",
                    List.of());
        }
        Map<UUID, List<DepartmentView>> byParent = new LinkedHashMap<>();
        List<DepartmentView> roots = new ArrayList<>();
        Set<UUID> present = new java.util.HashSet<>();
        all.forEach(d -> present.add(d.id()));
        for (DepartmentView d : all) {
            // An inactive parent is hidden when inactive nodes are excluded; its active children
            // cannot exist (deactivation requires inactive children), so this is only defensive.
            if (d.parentId() == null || !present.contains(d.parentId())) {
                roots.add(d);
            } else {
                byParent.computeIfAbsent(d.parentId(), k -> new ArrayList<>()).add(d);
            }
        }
        return roots.stream().map(r -> node(r, byParent)).toList();
    }

    private static TreeNode node(DepartmentView department, Map<UUID, List<DepartmentView>> byParent) {
        List<TreeNode> children = byParent.getOrDefault(department.id(), List.of()).stream()
                .sorted(Comparator.comparing(DepartmentView::code))
                .map(c -> node(c, byParent))
                .toList();
        return new TreeNode(department, children);
    }

    @Transactional
    public DepartmentView create(DepartmentCommands.Create command) {
        UUID companyId = CurrentContext.requireCompany();
        List<FieldViolation> violations = new ArrayList<>();
        if (command.parentId() != null) {
            parentViolation(companyId, command.parentId(), true).ifPresent(violations::add);
        }
        if (command.branchId() != null) {
            branchViolation(companyId, command.branchId()).ifPresent(violations::add);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The department is invalid.", violations);
        }
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = departments.insert(companyId, command, actor);
        audit.record(AuditEvent.builder("CREATE", "org")
                .entity("department", id, command.code())
                .detail("code", command.code())
                .detail("name", command.name())
                .detail("parentId", command.parentId())
                .detail("branchId", command.branchId())
                .build());
        return get(id);
    }

    @Transactional
    public DepartmentView patch(UUID departmentId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        DepartmentView current = lock(companyId, departmentId);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        MergePatch.Member<String> name = patch.text("name", true, 100);
        MergePatch.Member<UUID> parent = patch.uuid("parentId", false);
        MergePatch.Member<UUID> branch = patch.uuid("branchId", false);
        patch.throwIfInvalid();

        List<FieldViolation> violations = new ArrayList<>();
        if (parent.present() && parent.value() != null && !parent.value().equals(current.parentId())) {
            if (departments.isSelfOrDescendant(companyId, departmentId, parent.value())) {
                violations.add(FieldViolation.atPointer(
                        "/parentId", "CYCLE", "must not be the department itself or one of its sub-departments"));
            } else {
                // An inactive department may sit below an inactive parent.
                parentViolation(companyId, parent.value(), current.active()).ifPresent(violations::add);
            }
        }
        if (branch.present() && branch.value() != null && !branch.value().equals(current.branchId())) {
            branchViolation(companyId, branch.value())
                    .filter(v -> current.active() || !"INACTIVE".equals(v.code()))
                    .ifPresent(violations::add);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The department is invalid.", violations);
        }
        update(
                current,
                new DepartmentCommands.Update(
                        name.orElse(current.name()),
                        parent.orElse(current.parentId()),
                        branch.orElse(current.branchId()),
                        current.active()));
        DepartmentView after = get(departmentId);
        audit.record(AuditEvent.builder("UPDATE", "org")
                .entity("department", departmentId, after.code())
                .change("name", current.name(), after.name())
                .change("parentId", current.parentId(), after.parentId())
                .change("branchId", current.branchId(), after.branchId())
                .build());
        return after;
    }

    @Transactional
    public DepartmentView setActive(UUID departmentId, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        DepartmentView current = lock(companyId, departmentId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The department is already " + (active ? "active." : "inactive."));
        }
        if (active) {
            if (current.parentId() != null
                    && !departments
                            .lockForUse(companyId, current.parentId())
                            .map(DepartmentView::active)
                            .orElse(false)) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "Activate the parent department first.");
            }
            if (current.branchId() != null
                    && !branches.lockForUse(companyId, current.branchId(), null)
                            .map(BranchView::active)
                            .orElse(false)) {
                throw new ApiException(PlatformErrorCode.INVALID_STATE, "Activate the department's branch first.");
            }
        } else {
            int children = departments.countActiveChildren(companyId, departmentId);
            usage.requireDepartmentUnused(
                    companyId,
                    departmentId,
                    children == 0 ? List.of() : List.of(children + " active sub-department(s)"));
        }
        update(current, new DepartmentCommands.Update(current.name(), current.parentId(), current.branchId(), active));
        audit.record(AuditEvent.builder("STATE_CHANGE", "org")
                .entity("department", departmentId, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return get(departmentId);
    }

    private DepartmentView lock(UUID companyId, UUID departmentId) {
        return departments.lockForChange(companyId, departmentId).orElseThrow(ApiException::notFound);
    }

    private java.util.Optional<FieldViolation> parentViolation(UUID companyId, UUID parentId, boolean requireActive) {
        var parent = departments.lockForUse(companyId, parentId);
        if (parent.isEmpty()) {
            return java.util.Optional.of(
                    FieldViolation.atPointer("/parentId", "UNKNOWN_DEPARTMENT", "is not a department of the company"));
        }
        if (requireActive && !parent.get().active()) {
            return java.util.Optional.of(
                    FieldViolation.atPointer("/parentId", "INACTIVE", "must be an active department"));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<FieldViolation> branchViolation(UUID companyId, UUID branchId) {
        RequestContext context = CurrentContext.require();
        var branch = branches.lockForUse(companyId, branchId, context.branchScope());
        if (branch.isEmpty()) {
            return java.util.Optional.of(
                    FieldViolation.atPointer("/branchId", "UNKNOWN_BRANCH", "is not a branch of the company"));
        }
        if (!branch.get().active()) {
            return java.util.Optional.of(FieldViolation.atPointer("/branchId", "INACTIVE", "must be an active branch"));
        }
        return java.util.Optional.empty();
    }

    private void update(DepartmentView current, DepartmentCommands.Update state) {
        UUID actor = CurrentContext.requireActor().userId();
        if (!departments.update(current.companyId(), current.id(), current.version(), actor, state)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The department was modified concurrently.");
        }
    }
}
