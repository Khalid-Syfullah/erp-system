package com.erp.payroll.application;

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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The Payroll services' view of the request: company profile, actor, business date and permissions. */
@Component
public class PayrollContext {

    private final OrgFacade org;
    private final ObjectProvider<PermissionCheck> permissions;
    private final Clock clock;

    PayrollContext(OrgFacade org, ObjectProvider<PermissionCheck> permissions, Clock clock) {
        this.org = org;
        this.permissions = permissions;
        this.clock = clock;
    }

    public UUID companyId() {
        return CurrentContext.requireCompany();
    }

    public UUID actor() {
        return CurrentContext.requireActor().userId();
    }

    /** The acting user, or null in a background job. */
    public @Nullable UUID actorOrNull() {
        return CurrentContext.get()
                .map(RequestContext::actor)
                .map(a -> a.userId())
                .orElse(null);
    }

    public CompanyProfile profile() {
        return org.companyProfile(companyId()).orElseThrow(ApiException::notFound);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of(profile().timezone())));
    }

    public Clock clock() {
        return clock;
    }

    /** Payroll amounts are in the base currency (ADR-039): its minor units and the company's rounding. */
    public RoundingPolicy rounding() {
        CompanyProfile profile = profile();
        return RoundingPolicy.of(profile.baseCurrencyMinorUnits(), profile.roundingMode());
    }

    public String countryCode() {
        return profile().countryCode();
    }

    public boolean isGranted(String permission) {
        PermissionCheck check = permissions.getIfAvailable();
        return CurrentContext.get().map(c -> c.actor() != null).orElse(false)
                && check != null
                && check.isGranted(CurrentContext.require(), permission);
    }

    public void require(String permission, String what) {
        if (!isGranted(permission)) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, what + " requires the permission " + permission + ".");
        }
    }
}
