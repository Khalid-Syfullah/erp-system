package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.POSITIONS;

import com.erp.db.hr.tables.records.PositionsRecord;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.PositionView;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Positions (designations / job titles) per company (DATABASE.md §5.9). */
@Repository
public class PositionRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.POSITIONS)
            .field("code", POSITIONS.CODE)
            .field("title", POSITIONS.TITLE)
            .field("createdAt", POSITIONS.CREATED_AT)
            .field("departmentId", POSITIONS.DEPARTMENT_ID)
            .field("grade", POSITIONS.GRADE)
            .field("isActive", POSITIONS.IS_ACTIVE)
            .tiebreaker(POSITIONS.ID)
            .search(List.of(POSITIONS.CODE, POSITIONS.TITLE))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PositionRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, HrCommands.Position c, UUID actor) {
        return dsl.insertInto(POSITIONS)
                .set(POSITIONS.COMPANY_ID, companyId)
                .set(POSITIONS.CODE, c.code())
                .set(POSITIONS.TITLE, c.title())
                .set(POSITIONS.DEPARTMENT_ID, c.departmentId())
                .set(POSITIONS.GRADE, c.grade())
                .set(POSITIONS.CREATED_BY, actor)
                .set(POSITIONS.UPDATED_BY, actor)
                .returning(POSITIONS.ID)
                .fetchOne(POSITIONS.ID);
    }

    public Optional<PositionView> find(UUID companyId, UUID id) {
        return dsl.selectFrom(POSITIONS)
                .where(POSITIONS.COMPANY_ID.eq(companyId))
                .and(POSITIONS.ID.eq(id))
                .fetchOptional()
                .map(PositionRepository::toView);
    }

    public Optional<PositionView> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(POSITIONS)
                .where(POSITIONS.COMPANY_ID.eq(companyId))
                .and(POSITIONS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(PositionRepository::toView);
    }

    /** {@code FOR SHARE}: a concurrent deactivation or department change waits for the caller. */
    public Optional<PositionView> lockForUse(UUID companyId, UUID id) {
        return dsl.selectFrom(POSITIONS)
                .where(POSITIONS.COMPANY_ID.eq(companyId))
                .and(POSITIONS.ID.eq(id))
                .forShare()
                .fetchOptional()
                .map(PositionRepository::toView);
    }

    public PageResponse<PositionView> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, POSITIONS, POSITIONS.COMPANY_ID.eq(companyId), query, BINDING, PositionRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, HrCommands.Position c) {
        return dsl.update(POSITIONS)
                        .set(POSITIONS.TITLE, c.title())
                        .set(POSITIONS.DEPARTMENT_ID, c.departmentId())
                        .set(POSITIONS.GRADE, c.grade())
                        .set(POSITIONS.IS_ACTIVE, c.active())
                        .set(POSITIONS.UPDATED_AT, OffsetDateTime.now())
                        .set(POSITIONS.UPDATED_BY, actor)
                        .set(POSITIONS.VERSION, expectedVersion + 1)
                        .where(POSITIONS.COMPANY_ID.eq(companyId))
                        .and(POSITIONS.ID.eq(id))
                        .and(POSITIONS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public List<String> activeCodesInDepartment(UUID companyId, UUID departmentId) {
        return dsl.select(POSITIONS.CODE)
                .from(POSITIONS)
                .where(POSITIONS.COMPANY_ID.eq(companyId))
                .and(POSITIONS.DEPARTMENT_ID.eq(departmentId))
                .and(POSITIONS.IS_ACTIVE.isTrue())
                .orderBy(POSITIONS.CODE)
                .limit(20)
                .fetch(POSITIONS.CODE);
    }

    static PositionView toView(PositionsRecord r) {
        return new PositionView(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getTitle(),
                r.getDepartmentId(),
                r.getGrade(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
