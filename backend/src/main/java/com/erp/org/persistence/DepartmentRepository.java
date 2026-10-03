package com.erp.org.persistence;

import static com.erp.db.org.Tables.DEPARTMENTS;

import com.erp.db.org.tables.records.DepartmentsRecord;
import com.erp.org.application.DepartmentCommands;
import com.erp.org.application.DepartmentView;
import com.erp.org.application.OrgListings;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Departments of a company: a tree with an optional branch per node (DATABASE.md §5.2). */
@Repository
public class DepartmentRepository {

    /** The whole tree is returned by {@code GET …/departments/tree}; larger trees must be paged. */
    public static final int MAX_TREE_SIZE = 5_000;

    private static final ListBinding BINDING = ListBinding.builder(OrgListings.DEPARTMENTS)
            .field("code", DEPARTMENTS.CODE)
            .field("name", DEPARTMENTS.NAME)
            .field("createdAt", DEPARTMENTS.CREATED_AT)
            .field("isActive", DEPARTMENTS.IS_ACTIVE)
            .field("parentId", DEPARTMENTS.PARENT_ID)
            .field("branchId", DEPARTMENTS.BRANCH_ID)
            .tiebreaker(DEPARTMENTS.ID)
            .search(List.of(DEPARTMENTS.CODE, DEPARTMENTS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public DepartmentRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, DepartmentCommands.Create c, UUID actor) {
        return dsl.insertInto(DEPARTMENTS)
                .set(DEPARTMENTS.COMPANY_ID, companyId)
                .set(DEPARTMENTS.CODE, c.code())
                .set(DEPARTMENTS.NAME, c.name())
                .set(DEPARTMENTS.PARENT_ID, c.parentId())
                .set(DEPARTMENTS.BRANCH_ID, c.branchId())
                .set(DEPARTMENTS.CREATED_BY, actor)
                .set(DEPARTMENTS.UPDATED_BY, actor)
                .returning(DEPARTMENTS.ID)
                .fetchOne(DEPARTMENTS.ID);
    }

    public Optional<DepartmentView> find(UUID companyId, UUID id) {
        return dsl.selectFrom(DEPARTMENTS)
                .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENTS.ID.eq(id))
                .fetchOptional()
                .map(DepartmentRepository::toView);
    }

    /** Locks the row against concurrent changes ({@code FOR NO KEY UPDATE}) before a state change. */
    public Optional<DepartmentView> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(DEPARTMENTS)
                .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(DepartmentRepository::toView);
    }

    /** Locks the row {@code FOR SHARE}: a concurrent deactivation waits until the caller commits. */
    public Optional<DepartmentView> lockForUse(UUID companyId, UUID id) {
        return dsl.selectFrom(DEPARTMENTS)
                .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENTS.ID.eq(id))
                .forShare()
                .fetchOptional()
                .map(DepartmentRepository::toView);
    }

    public PageResponse<DepartmentView> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, DEPARTMENTS, DEPARTMENTS.COMPANY_ID.eq(companyId), query, BINDING, DepartmentRepository::toView);
    }

    /** All departments of the company ordered by code, at most {@link #MAX_TREE_SIZE} + 1 rows. */
    public List<DepartmentView> all(UUID companyId, boolean includeInactive) {
        return dsl.selectFrom(DEPARTMENTS)
                .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                .and(includeInactive ? DSL.noCondition() : DEPARTMENTS.IS_ACTIVE.isTrue())
                .orderBy(DEPARTMENTS.CODE)
                .limit(MAX_TREE_SIZE + 1)
                .fetch(DepartmentRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, DepartmentCommands.Update c) {
        return dsl.update(DEPARTMENTS)
                        .set(DEPARTMENTS.NAME, c.name())
                        .set(DEPARTMENTS.PARENT_ID, c.parentId())
                        .set(DEPARTMENTS.BRANCH_ID, c.branchId())
                        .set(DEPARTMENTS.IS_ACTIVE, c.active())
                        .set(DEPARTMENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(DEPARTMENTS.UPDATED_BY, actor)
                        .set(DEPARTMENTS.VERSION, expectedVersion + 1)
                        .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                        .and(DEPARTMENTS.ID.eq(id))
                        .and(DEPARTMENTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    /**
     * Whether {@code candidate} is {@code ancestor} itself or lies below it, found by walking up from
     * the candidate (trees are shallow; the walk is bounded). The database trigger is the backstop.
     */
    public boolean isSelfOrDescendant(UUID companyId, UUID ancestor, UUID candidate) {
        UUID current = candidate;
        for (int depth = 0; current != null && depth < 1000; depth++) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = dsl.select(DEPARTMENTS.PARENT_ID)
                    .from(DEPARTMENTS)
                    .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                    .and(DEPARTMENTS.ID.eq(current))
                    .fetchOne(DEPARTMENTS.PARENT_ID);
        }
        return false;
    }

    public int countActiveChildren(UUID companyId, UUID parentId) {
        return dsl.fetchCount(
                DEPARTMENTS,
                DEPARTMENTS
                        .COMPANY_ID
                        .eq(companyId)
                        .and(DEPARTMENTS.PARENT_ID.eq(parentId))
                        .and(DEPARTMENTS.IS_ACTIVE.isTrue()));
    }

    public List<String> activeCodesInBranch(UUID companyId, UUID branchId) {
        return dsl.select(DEPARTMENTS.CODE)
                .from(DEPARTMENTS)
                .where(DEPARTMENTS.COMPANY_ID.eq(companyId))
                .and(DEPARTMENTS.BRANCH_ID.eq(branchId))
                .and(DEPARTMENTS.IS_ACTIVE.isTrue())
                .orderBy(DEPARTMENTS.CODE)
                .limit(20)
                .fetch(DEPARTMENTS.CODE);
    }

    static DepartmentView toView(DepartmentsRecord r) {
        return new DepartmentView(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getParentId(),
                r.getBranchId(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
