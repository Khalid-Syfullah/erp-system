package com.erp.procurement.application;

import com.erp.org.api.BranchSummary;
import com.erp.org.api.CompanyProfile;
import com.erp.org.api.OrgFacade;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.money.RoundingPolicy;
import com.erp.platform.security.PermissionCheck;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Company settings, currencies, exchange rates, branch visibility and permission checks. */
@Component
class ProcurementContext {

    private final OrgFacade org;
    private final ObjectProvider<PermissionCheck> permissions;
    private final Clock clock;

    ProcurementContext(OrgFacade org, ObjectProvider<PermissionCheck> permissions, Clock clock) {
        this.org = org;
        this.permissions = permissions;
        this.clock = clock;
    }

    CompanyProfile profile() {
        return org.companyProfile(CurrentContext.requireCompany()).orElseThrow(ApiException::notFound);
    }

    LocalDate today(CompanyProfile profile) {
        return LocalDate.now(clock.withZone(ZoneId.of(profile.timezone())));
    }

    /** Rounding of amounts in a document currency (its minor units, the company mode; G-14). */
    RoundingPolicy rounding(String currencyCode, CompanyProfile profile) {
        int minorUnits = org.currency(currencyCode).map(c -> c.minorUnits()).orElse(profile.baseCurrencyMinorUnits());
        return RoundingPolicy.of(minorUnits, profile.roundingMode());
    }

    RoundingPolicy baseRounding(CompanyProfile profile) {
        return RoundingPolicy.of(profile.baseCurrencyMinorUnits(), profile.roundingMode());
    }

    boolean currencyUsable(String currencyCode) {
        return org.currency(currencyCode).map(c -> c.active()).orElse(false);
    }

    /** The exchange rate on the date (G-13), or {@code 422 EXCHANGE_RATE_MISSING}. */
    BigDecimal exchangeRate(String currencyCode, LocalDate date) {
        return org.exchangeRate(CurrentContext.requireCompany(), currencyCode, date)
                .orElseThrow(() -> new ApiException(
                        ProcurementErrorCode.EXCHANGE_RATE_MISSING,
                        "No exchange rate for " + currencyCode + " on or before " + date + ".",
                        List.of(FieldViolation.atPointer(
                                "/currencyCode", "EXCHANGE_RATE_MISSING", "has no exchange rate on " + date))));
    }

    boolean canSeeBranch(UUID branchId) {
        return CurrentContext.require().canSeeBranch(branchId);
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

    /**
     * Checks a document's branch (active, visible to the caller, locked for use) and optional
     * department (active, and tied to no other branch); problems go to {@code violations}.
     */
    void checkBranch(
            UUID branchId, @Nullable UUID departmentId, String branchPointer, List<FieldViolation> violations) {
        UUID companyId = CurrentContext.requireCompany();
        var branch = canSeeBranch(branchId)
                ? org.branchForUse(companyId, branchId)
                : java.util.Optional.<BranchSummary>empty();
        if (branch.isEmpty()) {
            violations.add(FieldViolation.atPointer(branchPointer, "UNKNOWN_BRANCH", "is not a branch you can use"));
        } else if (!branch.get().active()) {
            violations.add(FieldViolation.atPointer(branchPointer, "INACTIVE", "must be an active branch"));
        }
        if (departmentId != null) {
            var department = org.departmentForUse(companyId, departmentId);
            if (department.isEmpty() || !department.get().active()) {
                violations.add(FieldViolation.atPointer(
                        "/departmentId", "INVALID_VALUE", "must be an active department of the company"));
            } else if (department.get().branchId() != null
                    && !department.get().branchId().equals(branchId)) {
                violations.add(
                        FieldViolation.atPointer("/departmentId", "BRANCH_MISMATCH", "belongs to another branch"));
            }
        }
    }

    UUID actor() {
        return CurrentContext.requireActor().userId();
    }
}
