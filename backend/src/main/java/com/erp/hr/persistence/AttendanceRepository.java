package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.ATTENDANCE_RECORDS;
import static com.erp.db.hr.Tables.EMPLOYEES;

import com.erp.db.hr.tables.records.AttendanceRecordsRecord;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.hr.domain.AttendanceStatus;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Daily attendance records (ADR-039). */
@Repository
public class AttendanceRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.ATTENDANCE)
            .field("workDate", ATTENDANCE_RECORDS.WORK_DATE)
            .field("createdAt", ATTENDANCE_RECORDS.CREATED_AT)
            .field("employeeId", ATTENDANCE_RECORDS.EMPLOYEE_ID)
            .field("status", ATTENDANCE_RECORDS.STATUS)
            .tiebreaker(ATTENDANCE_RECORDS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public AttendanceRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public Optional<HrViews.Attendance> lock(UUID companyId, UUID employeeId, LocalDate date) {
        return dsl.selectFrom(ATTENDANCE_RECORDS)
                .where(ATTENDANCE_RECORDS.COMPANY_ID.eq(companyId))
                .and(ATTENDANCE_RECORDS.EMPLOYEE_ID.eq(employeeId))
                .and(ATTENDANCE_RECORDS.WORK_DATE.eq(date))
                .forNoKeyUpdate()
                .fetchOptional(AttendanceRepository::toView);
    }

    public UUID insert(
            UUID companyId,
            UUID employeeId,
            LocalDate date,
            AttendanceStatus status,
            @Nullable OffsetDateTime checkIn,
            @Nullable OffsetDateTime checkOut,
            @Nullable Integer workedMinutes,
            String source,
            @Nullable String note,
            UUID actor) {
        return dsl.insertInto(ATTENDANCE_RECORDS)
                .set(ATTENDANCE_RECORDS.COMPANY_ID, companyId)
                .set(ATTENDANCE_RECORDS.EMPLOYEE_ID, employeeId)
                .set(ATTENDANCE_RECORDS.WORK_DATE, date)
                .set(ATTENDANCE_RECORDS.STATUS, status.name())
                .set(ATTENDANCE_RECORDS.CHECK_IN, checkIn)
                .set(ATTENDANCE_RECORDS.CHECK_OUT, checkOut)
                .set(ATTENDANCE_RECORDS.WORKED_MINUTES, workedMinutes)
                .set(ATTENDANCE_RECORDS.SOURCE, source)
                .set(ATTENDANCE_RECORDS.NOTE, note)
                .set(ATTENDANCE_RECORDS.CREATED_BY, actor)
                .set(ATTENDANCE_RECORDS.UPDATED_BY, actor)
                .returning(ATTENDANCE_RECORDS.ID)
                .fetchSingle(ATTENDANCE_RECORDS.ID);
    }

    public boolean update(
            UUID companyId,
            UUID id,
            int expectedVersion,
            AttendanceStatus status,
            @Nullable OffsetDateTime checkIn,
            @Nullable OffsetDateTime checkOut,
            @Nullable Integer workedMinutes,
            String source,
            @Nullable String note,
            UUID actor) {
        return dsl.update(ATTENDANCE_RECORDS)
                        .set(ATTENDANCE_RECORDS.STATUS, status.name())
                        .set(ATTENDANCE_RECORDS.CHECK_IN, checkIn)
                        .set(ATTENDANCE_RECORDS.CHECK_OUT, checkOut)
                        .set(ATTENDANCE_RECORDS.WORKED_MINUTES, workedMinutes)
                        .set(ATTENDANCE_RECORDS.SOURCE, source)
                        .set(ATTENDANCE_RECORDS.NOTE, note)
                        .set(ATTENDANCE_RECORDS.UPDATED_AT, OffsetDateTime.now())
                        .set(ATTENDANCE_RECORDS.UPDATED_BY, actor)
                        .set(ATTENDANCE_RECORDS.VERSION, expectedVersion + 1)
                        .where(ATTENDANCE_RECORDS.COMPANY_ID.eq(companyId))
                        .and(ATTENDANCE_RECORDS.ID.eq(id))
                        .and(ATTENDANCE_RECORDS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(ATTENDANCE_RECORDS)
                .where(ATTENDANCE_RECORDS.COMPANY_ID.eq(companyId))
                .and(ATTENDANCE_RECORDS.ID.eq(id))
                .execute();
    }

    public PageResponse<HrViews.Attendance> list(
            UUID companyId,
            @Nullable Set<UUID> branchScope,
            LocalDate today,
            @Nullable Collection<UUID> employeeIds,
            ListQuery query) {
        Condition condition = ATTENDANCE_RECORDS.COMPANY_ID.eq(companyId);
        if (employeeIds != null) {
            condition = condition.and(ATTENDANCE_RECORDS.EMPLOYEE_ID.in(employeeIds));
        }
        if (branchScope != null) {
            condition = condition.and(DSL.exists(DSL.selectOne()
                    .from(EMPLOYEES)
                    .where(EMPLOYEES.COMPANY_ID.eq(ATTENDANCE_RECORDS.COMPANY_ID))
                    .and(EMPLOYEES.ID.eq(ATTENDANCE_RECORDS.EMPLOYEE_ID))
                    .and(EmployeeRepository.visible(branchScope, today))));
        }
        return paginator.fetch(dsl, ATTENDANCE_RECORDS, condition, query, BINDING, AttendanceRepository::toView);
    }

    /** Per employee: days per status and minutes worked in the range (visible employees only). */
    public List<HrViews.AttendanceSummary> summary(
            UUID companyId, @Nullable Set<UUID> branchScope, LocalDate today, LocalDate from, LocalDate to) {
        Map<UUID, HrViews.AttendanceSummary> result = new LinkedHashMap<>();
        dsl.select(
                        EMPLOYEES.ID,
                        EMPLOYEES.EMPLOYEE_NUMBER,
                        EMPLOYEES.FIRST_NAME,
                        EMPLOYEES.LAST_NAME,
                        ATTENDANCE_RECORDS.STATUS,
                        DSL.count(),
                        DSL.coalesce(DSL.sum(ATTENDANCE_RECORDS.WORKED_MINUTES), java.math.BigDecimal.ZERO))
                .from(ATTENDANCE_RECORDS)
                .join(EMPLOYEES)
                .on(EMPLOYEES
                        .COMPANY_ID
                        .eq(ATTENDANCE_RECORDS.COMPANY_ID)
                        .and(EMPLOYEES.ID.eq(ATTENDANCE_RECORDS.EMPLOYEE_ID)))
                .where(ATTENDANCE_RECORDS.COMPANY_ID.eq(companyId))
                .and(ATTENDANCE_RECORDS.WORK_DATE.between(from, to))
                .and(EmployeeRepository.visible(branchScope, today))
                .groupBy(
                        EMPLOYEES.ID,
                        EMPLOYEES.EMPLOYEE_NUMBER,
                        EMPLOYEES.FIRST_NAME,
                        EMPLOYEES.LAST_NAME,
                        ATTENDANCE_RECORDS.STATUS)
                .orderBy(EMPLOYEES.EMPLOYEE_NUMBER, ATTENDANCE_RECORDS.STATUS)
                .forEach(r -> {
                    HrViews.AttendanceSummary current = result.computeIfAbsent(
                            r.value1(),
                            id -> new HrViews.AttendanceSummary(
                                    id, r.value2(), r.value3() + " " + r.value4(), new LinkedHashMap<>(), 0));
                    current.days().put(r.value5(), r.value6());
                    result.put(
                            r.value1(),
                            new HrViews.AttendanceSummary(
                                    current.employeeId(),
                                    current.employeeNumber(),
                                    current.name(),
                                    current.days(),
                                    current.workedMinutes() + r.value7().intValue()));
                });
        return List.copyOf(result.values());
    }

    static HrViews.Attendance toView(AttendanceRecordsRecord r) {
        return new HrViews.Attendance(
                r.getId(),
                r.getEmployeeId(),
                r.getWorkDate(),
                AttendanceStatus.valueOf(r.getStatus()),
                r.getCheckIn(),
                r.getCheckOut(),
                r.getWorkedMinutes(),
                r.getSource(),
                r.getNote(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
