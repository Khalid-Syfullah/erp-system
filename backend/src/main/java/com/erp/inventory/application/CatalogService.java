package com.erp.inventory.application;

import com.erp.inventory.persistence.AttributeRepository;
import com.erp.inventory.persistence.CategoryRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
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
 * Product categories (a cycle-free tree; the ltree path is kept by a trigger) and product attributes
 * with their values. A category with active sub-categories or products cannot be deactivated.
 */
@Service
public class CatalogService {

    static final Set<String> CATEGORY_PATCHABLE = Set.of("name", "parentId");

    /** An attribute value as entered. */
    public record ValueInput(String code, String name, int sortOrder) {}

    private final CategoryRepository categories;
    private final AttributeRepository attributes;
    private final AuditPort audit;

    CatalogService(CategoryRepository categories, AttributeRepository attributes, AuditPort audit) {
        this.categories = categories;
        this.attributes = attributes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Category> categories(ListQuery query) {
        return categories.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.Category category(UUID id) {
        return categories.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public InventoryViews.Category createCategory(InventoryCommands.Category command) {
        UUID companyId = CurrentContext.requireCompany();
        if (command.parentId() != null) {
            requireActiveParent(companyId, command.parentId());
        }
        UUID id = categories.insert(
                companyId, command, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("product_category", id, command.code())
                .detail("name", command.name())
                .detail("parentId", command.parentId())
                .build());
        return category(id);
    }

    @Transactional
    public InventoryViews.Category patchCategory(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Category current =
                categories.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, CATEGORY_PATCHABLE);
        var name = patch.text("name", true, 100);
        var parent = patch.uuid("parentId", false);
        patch.throwIfInvalid();
        UUID newParent = parent.orElse(current.parentId());
        if (newParent != null && !newParent.equals(current.parentId())) {
            if (categories.isSelfOrDescendant(companyId, id, newParent)) {
                throw ApiException.validationFailed(
                        "The category is invalid.",
                        List.of(FieldViolation.atPointer(
                                "/parentId", "CYCLE", "must not be the category itself or one of its sub-categories")));
            }
            if (current.active()) {
                requireActiveParent(companyId, newParent);
            }
        }
        update(
                current,
                new InventoryCommands.Category(
                        current.code(), name.orElse(current.name()), newParent, current.active()));
        InventoryViews.Category after = category(id);
        audit.record(AuditEvent.builder("UPDATE", "inventory")
                .entity("product_category", id, current.code())
                .change("name", current.name(), after.name())
                .change("parentId", current.parentId(), after.parentId())
                .build());
        return after;
    }

    @Transactional
    public InventoryViews.Category setCategoryActive(UUID id, @Nullable String ifMatch, boolean active) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Category current =
                categories.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        if (current.active() == active) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The category is already " + (active ? "active." : "inactive."));
        }
        if (active && current.parentId() != null) {
            requireActiveParent(companyId, current.parentId());
        }
        if (!active
                && (categories.countActiveChildren(companyId, id) > 0
                        || categories.countActiveProducts(companyId, id) > 0)) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE, "The category still has active sub-categories or products.");
        }
        update(current, new InventoryCommands.Category(current.code(), current.name(), current.parentId(), active));
        audit.record(AuditEvent.builder("STATE_CHANGE", "inventory")
                .entity("product_category", id, current.code())
                .transition(current.active() ? "ACTIVE" : "INACTIVE", active ? "ACTIVE" : "INACTIVE")
                .build());
        return category(id);
    }

    // ------------------------------------------------------------------------- attributes

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.Attribute> attributes(ListQuery query) {
        return attributes.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public InventoryViews.Attribute attribute(UUID id) {
        return attributes.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public InventoryViews.Attribute createAttribute(String code, String name, List<ValueInput> values) {
        UUID companyId = CurrentContext.requireCompany();
        UUID actor = CurrentContext.requireActor().userId();
        UUID id = attributes.insert(companyId, code, name, actor);
        values.forEach(v -> attributes.insertValue(companyId, id, v.code(), v.name(), v.sortOrder(), actor));
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("product_attribute", id, code)
                .detail("values", values.stream().map(ValueInput::code).toList().toString())
                .build());
        return attribute(id);
    }

    @Transactional
    public InventoryViews.Attribute addValue(UUID attributeId, ValueInput value) {
        UUID companyId = CurrentContext.requireCompany();
        InventoryViews.Attribute attribute = attribute(attributeId);
        UUID id = attributes.insertValue(
                companyId,
                attributeId,
                value.code(),
                value.name(),
                value.sortOrder(),
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "inventory")
                .entity("product_attribute_value", id, attribute.code() + "=" + value.code())
                .build());
        return attribute(attributeId);
    }

    private void requireActiveParent(UUID companyId, UUID parentId) {
        var parent = categories.lockForUse(companyId, parentId);
        if (parent.isEmpty()) {
            throw ApiException.validationFailed(
                    "The category is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/parentId", "UNKNOWN_CATEGORY", "is not a category of the company")));
        }
        if (!parent.get().active()) {
            throw ApiException.validationFailed(
                    "The category is invalid.",
                    List.of(FieldViolation.atPointer("/parentId", "INACTIVE", "must be an active category")));
        }
    }

    private void update(InventoryViews.Category current, InventoryCommands.Category next) {
        if (!categories.update(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                next)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The category was modified concurrently.");
        }
    }
}
