package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.LEAVE_LEDGER;

import com.erp.db.hr.tables.records.LeaveLedgerRecord;
import com.erp.hr.application.HrListings;
import com.erp.hr.application.HrViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** The append-only leave ledger (DATABASE.md §5.9); balances are sums over it. */
@Repository
public class LeaveLedgerRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.LEAVE_LEDGER)
            .field("createdAt", LEAVE_LEDGER.CREATED_AT)
            .field("employeeId", LEAVE_LEDGER.EMPLOYEE_ID)
            .field("leaveTypeId", LEAVE_LEDGER.LEAVE_TYPE_ID)
            .field("leaveYear", LEAVE_LEDGER.LEAVE_YEAR.cast(Long.class))
            .field("entryType", LEAVE_LEDGER.ENTRY_TYPE)
            .tiebreaker(LEAVE_LEDGER.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public LeaveLedgerRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    /**
     * Appends an entry. Accruals, carry-forwards and expiries are unique per employee, type and year
     * (and month): a repeated one is skipped and {@code false} returned.
     */
    public boolean append(
            UUID companyId,
            UUID employeeId,
            UUID leaveTypeId,
            int year,
            @Nullable Integer accrualMonth,
            String entryType,
            BigDecimal days,
            @Nullable UUID leaveRequestId,
            @Nullable String note,
            @Nullable UUID actor) {
        return dsl.insertInto(LEAVE_LEDGER)
                        .set(LEAVE_LEDGER.COMPANY_ID, companyId)
                        .set(LEAVE_LEDGER.EMPLOYEE_ID, employeeId)
                        .set(LEAVE_LEDGER.LEAVE_TYPE_ID, leaveTypeId)
                        .set(LEAVE_LEDGER.LEAVE_YEAR, (short) year)
                        .set(LEAVE_LEDGER.ACCRUAL_MONTH, accrualMonth == null ? null : accrualMonth.shortValue())
                        .set(LEAVE_LEDGER.ENTRY_TYPE, entryType)
                        .set(LEAVE_LEDGER.DAYS, days)
                        .set(LEAVE_LEDGER.LEAVE_REQUEST_ID, leaveRequestId)
                        .set(LEAVE_LEDGER.NOTE, note)
                        .set(LEAVE_LEDGER.CREATED_BY, actor)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    /** Sum per leave type and entry type of the employee's year. */
    public Map<UUID, Map<String, BigDecimal>> totals(UUID companyId, UUID employeeId, int year) {
        Map<UUID, Map<String, BigDecimal>> result = new HashMap<>();
        dsl.select(LEAVE_LEDGER.LEAVE_TYPE_ID, LEAVE_LEDGER.ENTRY_TYPE, DSL.sum(LEAVE_LEDGER.DAYS))
                .from(LEAVE_LEDGER)
                .where(LEAVE_LEDGER.COMPANY_ID.eq(companyId))
                .and(LEAVE_LEDGER.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_LEDGER.LEAVE_YEAR.eq((short) year))
                .groupBy(LEAVE_LEDGER.LEAVE_TYPE_ID, LEAVE_LEDGER.ENTRY_TYPE)
                .forEach(r ->
                        result.computeIfAbsent(r.value1(), k -> new HashMap<>()).put(r.value2(), r.value3()));
        return result;
    }

    public BigDecimal balance(UUID companyId, UUID employeeId, UUID leaveTypeId, int year) {
        BigDecimal sum = dsl.select(DSL.sum(LEAVE_LEDGER.DAYS))
                .from(LEAVE_LEDGER)
                .where(LEAVE_LEDGER.COMPANY_ID.eq(companyId))
                .and(LEAVE_LEDGER.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_LEDGER.LEAVE_TYPE_ID.eq(leaveTypeId))
                .and(LEAVE_LEDGER.LEAVE_YEAR.eq((short) year))
                .fetchOne(0, BigDecimal.class);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** Whether the employee has any entry of the type in the year. */
    public boolean hasEntries(UUID companyId, UUID employeeId, UUID leaveTypeId, int year) {
        return dsl.fetchExists(dsl.selectOne()
                .from(LEAVE_LEDGER)
                .where(LEAVE_LEDGER.COMPANY_ID.eq(companyId))
                .and(LEAVE_LEDGER.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_LEDGER.LEAVE_TYPE_ID.eq(leaveTypeId))
                .and(LEAVE_LEDGER.LEAVE_YEAR.eq((short) year)));
    }

    public PageResponse<HrViews.LedgerEntry> list(UUID companyId, @Nullable UUID employeeId, ListQuery query) {
        Condition condition = LEAVE_LEDGER.COMPANY_ID.eq(companyId);
        if (employeeId != null) {
            condition = condition.and(LEAVE_LEDGER.EMPLOYEE_ID.eq(employeeId));
        }
        return paginator.fetch(dsl, LEAVE_LEDGER, condition, query, BINDING, LeaveLedgerRepository::toView);
    }

    static HrViews.LedgerEntry toView(LeaveLedgerRecord r) {
        return new HrViews.LedgerEntry(
                r.getId(),
                r.getEmployeeId(),
                r.getLeaveTypeId(),
                r.getLeaveYear(),
                r.getAccrualMonth() == null ? null : r.getAccrualMonth().intValue(),
                r.getEntryType(),
                r.getDays(),
                r.getLeaveRequestId(),
                r.getNote(),
                r.getCreatedAt(),
                r.getCreatedBy());
    }
}
