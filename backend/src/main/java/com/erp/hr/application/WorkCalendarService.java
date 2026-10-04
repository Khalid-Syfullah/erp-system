package com.erp.hr.application;

import com.erp.hr.domain.WorkCalendar;
import com.erp.hr.persistence.HrSettingsRepository;
import com.erp.hr.persistence.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The company's working-day calendar for a branch (weekend setting plus public holidays). */
@Component
public class WorkCalendarService {

    static final List<Integer> DEFAULT_WEEKEND = List.of(6, 7);

    private final HrSettingsRepository settings;
    private final PublicHolidayRepository holidays;

    WorkCalendarService(HrSettingsRepository settings, PublicHolidayRepository holidays) {
        this.settings = settings;
        this.holidays = holidays;
    }

    public WorkCalendar calendar(UUID companyId, @Nullable UUID branchId, LocalDate from, LocalDate to) {
        return new WorkCalendar(weekend(companyId), holidays.dates(companyId, branchId, from, to));
    }

    public int workingDays(UUID companyId, @Nullable UUID branchId, LocalDate from, LocalDate to) {
        return calendar(companyId, branchId, from, to).workingDays(from, to);
    }

    Set<DayOfWeek> weekend(UUID companyId) {
        List<Integer> days =
                settings.find(companyId).map(HrViews.Settings::weekendDays).orElse(DEFAULT_WEEKEND);
        EnumSet<DayOfWeek> weekend = EnumSet.noneOf(DayOfWeek.class);
        days.forEach(d -> weekend.add(DayOfWeek.of(d)));
        return weekend;
    }
}
