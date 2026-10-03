package com.erp.inventory.application;

import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.org.api.OrganizationUsage;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A branch with active warehouses cannot be deactivated (PRODUCT_SPEC.md §4.2, ADR-034). */
@Component
class InventoryOrganizationUsage implements OrganizationUsage {

    private final WarehouseRepository warehouses;

    InventoryOrganizationUsage(WarehouseRepository warehouses) {
        this.warehouses = warehouses;
    }

    @Override
    public List<String> branchUsage(UUID companyId, UUID branchId) {
        List<String> codes = warehouses.activeCodesInBranch(companyId, branchId);
        return codes.isEmpty() ? List.of() : List.of("active warehouses " + String.join(", ", codes));
    }

    @Override
    public List<String> departmentUsage(UUID companyId, UUID departmentId) {
        return List.of();
    }
}
