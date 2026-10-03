package com.erp.inventory.application;

import com.erp.inventory.persistence.WarehouseRepository;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.money.RoundingPolicy;
import com.erp.platform.security.PermissionCheck;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Company settings, branch visibility and fine-grained permission checks for the Inventory services. */
@Component
class InventoryContext {

    private final OrgFacade org;
    private final WarehouseRepository warehouses;
    private final ObjectProvider<PermissionCheck> permissions;
    private final Clock clock;

    InventoryContext(
            OrgFacade org, WarehouseRepository warehouses, ObjectProvider<PermissionCheck> permissions, Clock clock) {
        this.org = org;
        this.warehouses = warehouses;
        this.permissions = permissions;
        this.clock = clock;
    }

    CompanyProfile profile(UUID companyId) {
        return org.companyProfile(companyId).orElseThrow(ApiException::notFound);
    }

    RoundingPolicy rounding(CompanyProfile profile) {
        return RoundingPolicy.of(profile.baseCurrencyMinorUnits(), profile.roundingMode());
    }

    LocalDate today(CompanyProfile profile) {
        return LocalDate.now(clock.withZone(ZoneId.of(profile.timezone())));
    }

    /** Warehouses the caller may see, or {@code null} when not branch-restricted (SECURITY.md §4.4). */
    @Nullable Set<UUID> visibleWarehouses() {
        RequestContext context = CurrentContext.require();
        return context.branchScope() == null
                ? null
                : warehouses.visibleIds(CurrentContext.requireCompany(), context.branchScope());
    }

    boolean isGranted(String permission) {
        PermissionCheck check = permissions.getIfAvailable();
        return check != null && check.isGranted(CurrentContext.require(), permission);
    }

    void require(String permission, String what) {
        if (!isGranted(permission)) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, what + " requires the permission " + permission + ".");
        }
    }
}
