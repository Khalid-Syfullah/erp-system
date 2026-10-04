package com.erp.accounting.application;

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

/** Company profile, base currency rounding, exchange rates, the actor and permission checks. */
@Component
class AccountingContext {

    private final OrgFacade org;
    private final ObjectProvider<PermissionCheck> permissions;
    private final Clock clock;

    AccountingContext(OrgFacade org, ObjectProvider<PermissionCheck> permissions, Clock clock) {
        this.org = org;
        this.permissions = permissions;
        this.clock = clock;
    }

    CompanyProfile profile() {
        return org.companyProfile(CurrentContext.requireCompany()).orElseThrow(ApiException::notFound);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of(profile().timezone())));
    }

    /** Rounding of base currency amounts (its minor units, the company mode). */
    RoundingPolicy baseRounding() {
        CompanyProfile profile = profile();
        return RoundingPolicy.of(profile.baseCurrencyMinorUnits(), profile.roundingMode());
    }

    RoundingPolicy rounding(String currencyCode) {
        CompanyProfile profile = profile();
        int minorUnits = org.currency(currencyCode).map(c -> c.minorUnits()).orElse(profile.baseCurrencyMinorUnits());
        return RoundingPolicy.of(minorUnits, profile.roundingMode());
    }

    boolean currencyUsable(String currencyCode) {
        return org.currency(currencyCode).map(c -> c.active()).orElse(false);
    }

    /** The exchange rate on the date (G-13), or {@code 422 EXCHANGE_RATE_MISSING}. */
    BigDecimal exchangeRate(String currencyCode, LocalDate date) {
        return org.exchangeRate(CurrentContext.requireCompany(), currencyCode, date)
                .orElseThrow(() -> new ApiException(
                        AccountingErrorCode.EXCHANGE_RATE_MISSING,
                        "No exchange rate for " + currencyCode + " on or before " + date + ".",
                        List.of(FieldViolation.atPointer(
                                "/currencyCode", "EXCHANGE_RATE_MISSING", "has no exchange rate on " + date))));
    }

    boolean isGranted(String permission) {
        PermissionCheck check = permissions.getIfAvailable();
        return CurrentContext.get().map(c -> c.actor() != null).orElse(false)
                && check != null
                && check.isGranted(CurrentContext.require(), permission);
    }

    void require(String permission, String what) {
        if (!isGranted(permission)) {
            throw new ApiException(PlatformErrorCode.FORBIDDEN, what + " requires the permission " + permission + ".");
        }
    }

    UUID actor() {
        return CurrentContext.requireActor().userId();
    }

    /** The acting user, or null for system work (jobs, seeding). */
    @Nullable UUID actorOrNull() {
        return CurrentContext.get()
                .filter(c -> c.actor() != null)
                .map(c -> c.actor().userId())
                .orElse(null);
    }

    UUID companyId() {
        return CurrentContext.requireCompany();
    }
}
