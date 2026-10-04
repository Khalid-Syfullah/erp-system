package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.LEAVE_TYPES;

import com.erp.db.hr.tables.records.LeaveTypesRecord;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
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

/** Leave types (DATABASE.md §5.9). */
@Repository
public class LeaveTypeRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.LEAVE_TYPES)
            .field("code", LEAVE_TYPES.CODE)
            .field("name", LEAVE_TYPES.NAME)
            .field("createdAt", LEAVE_TYPES.CREATED_AT)
            .field("isActive", LEAVE_TYPES.IS_ACTIVE)
            .tiebreaker(LEAVE_TYPES.ID)
            .search(List.of(LEAVE_TYPES.CODE, LEAVE_TYPES.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public LeaveTypeRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, HrCommands.LeaveType c, UUID actor) {
        return dsl.insertInto(LEAVE_TYPES)
                .set(LEAVE_TYPES.COMPANY_ID, companyId)
                .set(LEAVE_TYPES.CODE, c.code())
                .set(LEAVE_TYPES.NAME, c.name())
                .set(LEAVE_TYPES.IS_PAID, c.paid())
                .set(LEAVE_TYPES.ANNUAL_ENTITLEMENT_DAYS, c.annualEntitlementDays())
                .set(LEAVE_TYPES.ACCRUAL_METHOD, c.accrualMethod())
                .set(LEAVE_TYPES.MAX_CARRY_FORWARD_DAYS, c.maxCarryForwardDays())
                .set(LEAVE_TYPES.ALLOW_NEGATIVE_BALANCE, c.allowNegativeBalance())
                .set(LEAVE_TYPES.IS_ACTIVE, c.active())
                .set(LEAVE_TYPES.CREATED_BY, actor)
                .set(LEAVE_TYPES.UPDATED_BY, actor)
                .returning(LEAVE_TYPES.ID)
                .fetchSingle(LEAVE_TYPES.ID);
    }

    public boolean update(UUID companyId, UUID id, int expectedVersion, UUID actor, HrCommands.LeaveType c) {
        return dsl.update(LEAVE_TYPES)
                        .set(LEAVE_TYPES.NAME, c.name())
                        .set(LEAVE_TYPES.IS_PAID, c.paid())
                        .set(LEAVE_TYPES.ANNUAL_ENTITLEMENT_DAYS, c.annualEntitlementDays())
                        .set(LEAVE_TYPES.ACCRUAL_METHOD, c.accrualMethod())
                        .set(LEAVE_TYPES.MAX_CARRY_FORWARD_DAYS, c.maxCarryForwardDays())
                        .set(LEAVE_TYPES.ALLOW_NEGATIVE_BALANCE, c.allowNegativeBalance())
                        .set(LEAVE_TYPES.IS_ACTIVE, c.active())
                        .set(LEAVE_TYPES.UPDATED_AT, OffsetDateTime.now())
                        .set(LEAVE_TYPES.UPDATED_BY, actor)
                        .set(LEAVE_TYPES.VERSION, expectedVersion + 1)
                        .where(LEAVE_TYPES.COMPANY_ID.eq(companyId))
                        .and(LEAVE_TYPES.ID.eq(id))
                        .and(LEAVE_TYPES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public Optional<HrViews.LeaveType> find(UUID companyId, UUID id) {
        return dsl.selectFrom(LEAVE_TYPES)
                .where(LEAVE_TYPES.COMPANY_ID.eq(companyId))
                .and(LEAVE_TYPES.ID.eq(id))
                .fetchOptional(LeaveTypeRepository::toView);
    }

    /** {@code FOR SHARE}: a change of the type waits for requests booked against it. */
    public Optional<HrViews.LeaveType> lockForUse(UUID companyId, UUID id) {
        return dsl.selectFrom(LEAVE_TYPES)
                .where(LEAVE_TYPES.COMPANY_ID.eq(companyId))
                .and(LEAVE_TYPES.ID.eq(id))
                .forShare()
                .fetchOptional(LeaveTypeRepository::toView);
    }

    public Optional<HrViews.LeaveType> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(LEAVE_TYPES)
                .where(LEAVE_TYPES.COMPANY_ID.eq(companyId))
                .and(LEAVE_TYPES.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(LeaveTypeRepository::toView);
    }

    public List<HrViews.LeaveType> all(UUID companyId) {
        return dsl.selectFrom(LEAVE_TYPES)
                .where(LEAVE_TYPES.COMPANY_ID.eq(companyId))
                .orderBy(LEAVE_TYPES.CODE)
                .fetch(LeaveTypeRepository::toView);
    }

    public PageResponse<HrViews.LeaveType> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, LEAVE_TYPES, LEAVE_TYPES.COMPANY_ID.eq(companyId), query, BINDING, LeaveTypeRepository::toView);
    }

    static HrViews.LeaveType toView(LeaveTypesRecord r) {
        return new HrViews.LeaveType(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getIsPaid(),
                r.getAnnualEntitlementDays(),
                r.getAccrualMethod(),
                r.getMaxCarryForwardDays(),
                r.getAllowNegativeBalance(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
