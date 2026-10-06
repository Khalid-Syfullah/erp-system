package com.erp.reporting.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.security.PermissionCheck;
import com.erp.platform.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The reporting services' view of the request: company, actor, business date, branch scope, permissions. */
@Component
public class ReportingContext {

    private final OrgFacade org;
    private final ObjectProvider<PermissionCheck> permissions;
    private final Clock clock;

    ReportingContext(OrgFacade org, ObjectProvider<PermissionCheck> permissions, Clock clock) {
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

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** The company's business date ("today" in its timezone). */
    public LocalDate today(UUID companyId) {
        String zone =
                org.findCompany(companyId).orElseThrow(ApiException::notFound).timezone();
        return LocalDate.now(clock.withZone(ZoneId.of(zone)));
    }

    public String companyName(UUID companyId) {
        return org.findCompany(companyId).map(c -> c.displayName()).orElse("");
    }

    /** The caller's scope: the active company, its branch scope and business date. */
    public ReportScope scope() {
        RequestContext context = CurrentContext.require();
        UUID companyId = companyId();
        return new ReportScope(companyId, context.branchScope(), today(companyId));
    }

    public boolean isGranted(String permission) {
        PermissionCheck check = permissions.getIfAvailable();
        return CurrentContext.get().map(c -> c.actor() != null).orElse(false)
                && check != null
                && check.isGranted(CurrentContext.require(), permission);
    }

    public boolean isGrantedAll(Collection<String> codes) {
        return codes.stream().allMatch(this::isGranted);
    }
}
