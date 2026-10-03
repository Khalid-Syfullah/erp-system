package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.DEPARTMENT_HEADS;

import com.erp.db.hr.tables.records.DepartmentHeadsRecord;
import com.erp.hr.application.DepartmentHeadView;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.domain.EffectivePeriod;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Department heads, one per department at any date (DATABASE.md §5.9). */
@Repository
public class DepartmentHeadRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.DEPARTMENT_HEADS)
            .field("effectiveFrom", DEPARTMENT_HEADS.EFFECTIVE_FROM)
            .field("createdAt", DEPARTMENT_HEADS.CREATED_AT)
            .field("departmentId", DEPARTMENT_HEADS.DEPARTMENT_ID)
            .field("employeeId", DEPARTMENT_HEADS.EMPLOYEE_ID)
            .tiebreaker(DEPARTMENT_HEADS.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public DepartmentHeadRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, HrCommands.DepartmentHead c, UUID actor) {
        return dsl.insertInto(DEPARTMENT_HEADS)
                .set(DEPARTMENT_HEADS.COMPANY_ID, companyId)
                .set(DEPARTMENT_HEADS.DEPARTMENT_ID, c.departmentId())
                .set(DEPARTMENT_HEADS.EMPLOYEE_ID, c.employeeId())
                .set(DEPARTMENT_HEADS.EFFECTIVE_FROM, c.effectiveFrom())
                .set(DEPARTMENT_HEADS.EFFECTIVE_TO, c.effectiveTo())
                .set(DEPARTMENT_HEADS.CREATED_BY, actor)
                .set(DEPARTMENT_HEADS.UPDATED_BY, actor)
                .returning(DEPARTMENT_HEADS.ID)
                .fetchOne(DEPARTMENT_HEADS.ID);
    }

    public Optional<DepartmentHeadView> find(UUID companyId, UUID id) {
        return dsl.selectFrom(DEPARTMENT_HEADS)
                .where(DEPARTMENT_HEADS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENT_HEADS.ID.eq(id))
                .fetchOptional()
                .map(DepartmentHeadRepository::toView);
    }

    public Optional<DepartmentHeadView> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(DEPARTMENT_HEADS)
                .where(DEPARTMENT_HEADS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENT_HEADS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(DepartmentHeadRepository::toView);
    }

    public PageResponse<DepartmentHeadView> list(UUID companyId, @Nullable LocalDate asOf, ListQuery query) {
        Condition scope = DEPARTMENT_HEADS.COMPANY_ID.eq(companyId);
        if (asOf != null) {
            scope = scope.and(DEPARTMENT_HEADS.EFFECTIVE_FROM.le(asOf)).and(reachesOrPasses(asOf));
        }
        return paginator.fetch(dsl, DEPARTMENT_HEADS, scope, query, BINDING, DepartmentHeadRepository::toView);
    }

    public List<DepartmentHeadView> overlapping(
            UUID companyId, UUID departmentId, EffectivePeriod period, @Nullable UUID excludeId) {
        return dsl.selectFrom(DEPARTMENT_HEADS)
                .where(DEPARTMENT_HEADS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENT_HEADS.DEPARTMENT_ID.eq(departmentId))
                .and(period.to() == null ? DSL.noCondition() : DEPARTMENT_HEADS.EFFECTIVE_FROM.le(period.to()))
                .and(reachesOrPasses(period.from()))
                .and(excludeId == null ? DSL.noCondition() : DEPARTMENT_HEADS.ID.ne(excludeId))
                .fetch(DepartmentHeadRepository::toView);
    }

    /** Headships of the employee that are effective on or after {@code date}. */
    public List<DepartmentHeadView> forEmployeeFrom(UUID companyId, UUID employeeId, LocalDate date) {
        return dsl.selectFrom(DEPARTMENT_HEADS)
                .where(DEPARTMENT_HEADS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENT_HEADS.EMPLOYEE_ID.eq(employeeId))
                .and(reachesOrPasses(date))
                .fetch(DepartmentHeadRepository::toView);
    }

    public Optional<LocalDate> earliestStart(UUID companyId, UUID employeeId) {
        return Optional.ofNullable(dsl.select(DSL.min(DEPARTMENT_HEADS.EFFECTIVE_FROM))
                .from(DEPARTMENT_HEADS)
                .where(DEPARTMENT_HEADS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENT_HEADS.EMPLOYEE_ID.eq(employeeId))
                .fetchOne(0, LocalDate.class));
    }

    public int countCurrentOrFutureInDepartment(UUID companyId, UUID departmentId, LocalDate today) {
        return dsl.fetchCount(
                DEPARTMENT_HEADS,
                DEPARTMENT_HEADS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(DEPARTMENT_HEADS.DEPARTMENT_ID.eq(departmentId))
                        .and(reachesOrPasses(today)));
    }

    public boolean updateEnd(
            UUID companyId, UUID id, int expectedVersion, UUID actor, @Nullable LocalDate effectiveTo) {
        return dsl.update(DEPARTMENT_HEADS)
                        .set(DEPARTMENT_HEADS.EFFECTIVE_TO, effectiveTo)
                        .set(DEPARTMENT_HEADS.UPDATED_AT, OffsetDateTime.now())
                        .set(DEPARTMENT_HEADS.UPDATED_BY, actor)
                        .set(DEPARTMENT_HEADS.VERSION, expectedVersion + 1)
                        .where(DEPARTMENT_HEADS.COMPANY_ID.eq(companyId))
                        .and(DEPARTMENT_HEADS.ID.eq(id))
                        .and(DEPARTMENT_HEADS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    private static Condition reachesOrPasses(LocalDate date) {
        return DEPARTMENT_HEADS.EFFECTIVE_TO.isNull().or(DEPARTMENT_HEADS.EFFECTIVE_TO.ge(date));
    }

    static DepartmentHeadView toView(DepartmentHeadsRecord r) {
        return new DepartmentHeadView(
                r.getId(),
                r.getCompanyId(),
                r.getDepartmentId(),
                r.getEmployeeId(),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
