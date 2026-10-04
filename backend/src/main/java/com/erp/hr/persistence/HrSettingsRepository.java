package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.SETTINGS;

import com.erp.hr.application.HrViews;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Company HR settings; a company without a row uses the defaults (DATABASE.md §5.9). */
@Repository
public class HrSettingsRepository {

    private final DSLContext dsl;

    public HrSettingsRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<HrViews.Settings> find(UUID companyId) {
        return dsl.selectFrom(SETTINGS)
                .where(SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new HrViews.Settings(
                        Arrays.stream(r.getWeekendDays()).map(Short::intValue).toList(),
                        r.getStandardWorkMinutes(),
                        r.getVersion()));
    }

    /** Creates the default row unless it exists. */
    public void ensure(UUID companyId) {
        dsl.insertInto(SETTINGS)
                .set(SETTINGS.COMPANY_ID, companyId)
                .onConflictDoNothing()
                .execute();
    }

    public boolean update(
            UUID companyId, int expectedVersion, @Nullable UUID actor, List<Integer> weekendDays, int standardMinutes) {
        return dsl.update(SETTINGS)
                        .set(
                                SETTINGS.WEEKEND_DAYS,
                                weekendDays.stream().map(Integer::shortValue).toArray(Short[]::new))
                        .set(SETTINGS.STANDARD_WORK_MINUTES, standardMinutes)
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, expectedVersion + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }
}
