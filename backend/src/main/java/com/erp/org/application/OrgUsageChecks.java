package com.erp.org.application;

import com.erp.org.api.OrganizationUsage;
import com.erp.org.api.TaxCodeUsage;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Asks the downstream modules (through the {@link OrganizationUsage} and {@link TaxCodeUsage} ports)
 * whether an organizational unit is still in use. Callers lock the unit first, so a concurrent new use
 * either completes before the check or waits and then sees the unit inactive.
 */
@Component
class OrgUsageChecks {

    private final ObjectProvider<OrganizationUsage> organizationUsages;
    private final ObjectProvider<TaxCodeUsage> taxCodeUsages;

    OrgUsageChecks(ObjectProvider<OrganizationUsage> organizationUsages, ObjectProvider<TaxCodeUsage> taxCodeUsages) {
        this.organizationUsages = organizationUsages;
        this.taxCodeUsages = taxCodeUsages;
    }

    void requireBranchUnused(UUID companyId, UUID branchId, List<String> ownUses) {
        List<String> uses = new java.util.ArrayList<>(ownUses);
        organizationUsages.orderedStream().forEach(u -> uses.addAll(u.branchUsage(companyId, branchId)));
        requireNone("branch", uses);
    }

    void requireDepartmentUnused(UUID companyId, UUID departmentId, List<String> ownUses) {
        List<String> uses = new java.util.ArrayList<>(ownUses);
        organizationUsages.orderedStream().forEach(u -> uses.addAll(u.departmentUsage(companyId, departmentId)));
        requireNone("department", uses);
    }

    boolean isTaxCodeUsed(UUID companyId, UUID taxCodeId) {
        return taxCodeUsages.orderedStream().anyMatch(u -> u.isUsed(companyId, taxCodeId));
    }

    private static void requireNone(String unit, List<String> uses) {
        if (!uses.isEmpty()) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE,
                    "The " + unit + " is still in use and cannot be deactivated: " + String.join("; ", uses) + ".");
        }
    }
}
