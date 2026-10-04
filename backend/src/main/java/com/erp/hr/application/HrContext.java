package com.erp.hr.application;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.security.PermissionCheck;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.PlatformErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The HR services' view of the request: company, actor, business date and permissions. */
@Component
public class HrContext {

    private final HrCalendar calendar;
    private final ObjectProvider<PermissionCheck> permissions;
    private final Clock clock;

    HrContext(HrCalendar calendar, ObjectProvider<PermissionCheck> permissions, Clock clock) {
        this.calendar = calendar;
        this.permissions = permissions;
        this.clock = clock;
    }

    public UUID companyId() {
        return CurrentContext.requireCompany();
    }

    public UUID actor() {
        return CurrentContext.requireActor().userId();
    }

    public LocalDate today() {
        return calendar.today(companyId());
    }

    public LocalDate today(UUID companyId) {
        return calendar.today(companyId);
    }

    public Clock clock() {
        return clock;
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
