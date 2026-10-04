package com.erp.accounting.application;

import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.MappingKey.ScopeType;
import com.erp.accounting.persistence.MappingRepository;
import com.erp.inventory.api.InventoryFacade;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Account determination (PRODUCT_SPEC.md §8.6): a mapping key resolves through the scopes given, left
 * to right — a product category is tried itself, then each ancestor up the tree — and finally the
 * company DEFAULT. The first mapping found wins; none is {@code 422 ACCOUNT_MAPPING_MISSING}, which
 * rolls back the operational document being posted (ADR-005).
 */
@Component
class AccountDetermination {

    /** One scope to try. */
    record Scope(ScopeType type, UUID id) {}

    private final MappingRepository mappings;
    private final InventoryFacade inventory;

    AccountDetermination(MappingRepository mappings, InventoryFacade inventory) {
        this.mappings = mappings;
        this.inventory = inventory;
    }

    UUID resolve(MappingKey key, List<Scope> scopes) {
        UUID companyId = CurrentContext.requireCompany();
        for (Scope scope : scopes) {
            var found = mappings.account(companyId, key.name(), scope.type().name(), scope.id());
            if (found.isPresent()) {
                return found.get();
            }
        }
        return mappings.account(companyId, key.name(), ScopeType.DEFAULT.name(), null)
                .orElseThrow(() -> new ApiException(
                        AccountingErrorCode.ACCOUNT_MAPPING_MISSING,
                        "No account is mapped for " + key + ".",
                        List.of(new FieldViolation(
                                null,
                                null,
                                AccountingErrorCode.ACCOUNT_MAPPING_MISSING.code(),
                                "No account mapping for " + key,
                                Map.of(
                                        "mappingKey",
                                        key.name(),
                                        "scopes",
                                        scopes.stream()
                                                .map(s -> s.type() + ":" + s.id())
                                                .toList())))));
    }

    UUID resolve(MappingKey key) {
        return resolve(key, List.of());
    }

    /** The category and its ancestors, nearest first. */
    List<Scope> category(@Nullable UUID categoryId) {
        List<Scope> scopes = new ArrayList<>();
        if (categoryId != null) {
            inventory.categoryAncestry(categoryId).forEach(id -> scopes.add(new Scope(ScopeType.PRODUCT_CATEGORY, id)));
        }
        return scopes;
    }

    /** Category scopes, then the warehouse (INVENTORY_ASSET [category, warehouse]). */
    List<Scope> categoryThenWarehouse(@Nullable UUID categoryId, @Nullable UUID warehouseId) {
        List<Scope> scopes = category(categoryId);
        if (warehouseId != null) {
            scopes.add(new Scope(ScopeType.WAREHOUSE, warehouseId));
        }
        return scopes;
    }

    static List<Scope> of(ScopeType type, @Nullable UUID id) {
        return id == null ? List.of() : List.of(new Scope(type, id));
    }
}
