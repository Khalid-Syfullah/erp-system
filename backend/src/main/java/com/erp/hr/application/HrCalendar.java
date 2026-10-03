package com.erp.hr.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The company's business date ("today" in its timezone), which decides what is current (ARCHITECTURE.md §6.10). */
@Component
class HrCalendar {

    private final OrgFacade org;
    private final Clock clock;

    HrCalendar(OrgFacade org, Clock clock) {
        this.org = org;
        this.clock = clock;
    }

    LocalDate today(UUID companyId) {
        String zone =
                org.findCompany(companyId).orElseThrow(ApiException::notFound).timezone();
        return LocalDate.now(clock.withZone(ZoneId.of(zone)));
    }
}
