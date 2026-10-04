package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.EXPENSES;
import static com.erp.db.accounting.Tables.EXPENSE_LINES;

import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.AccountingViews;
import com.erp.db.accounting.tables.records.ExpenseLinesRecord;
import com.erp.db.accounting.tables.records.ExpensesRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Expense vouchers and their lines (PRODUCT_SPEC.md §8.9). */
@Repository
public class ExpenseRepository {

    private static final ListBinding BINDING = ListBinding.builder(AccountingListings.EXPENSES)
            .field("expenseDate", EXPENSES.EXPENSE_DATE)
            .field("createdAt", EXPENSES.CREATED_AT)
            .field("status", EXPENSES.STATUS)
            .field("bankAccountId", EXPENSES.BANK_ACCOUNT_ID)
            .field("partnerId", EXPENSES.PARTNER_ID)
            .field("number", EXPENSES.NUMBER)
            .tiebreaker(EXPENSES.ID)
            .search(List.of(EXPENSES.NUMBER, EXPENSES.PAYEE_NAME, EXPENSES.REFERENCE))
            .build();

    public record Header(
            LocalDate expenseDate,
            LocalDate accountingDate,
            String payeeName,
            @Nullable UUID partnerId,
            UUID bankAccountId,
            String currencyCode,
            BigDecimal exchangeRate,
            boolean pricesIncludeTax,
            BigDecimal subtotal,
            BigDecimal taxTotal,
            BigDecimal total,
            BigDecimal totalBase,
            @Nullable String reference,
            @Nullable String notes) {}

    public record NewLine(
            int lineNo,
            UUID accountId,
            @Nullable String description,
            BigDecimal netAmount,
            @Nullable UUID taxCodeId,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            @Nullable UUID branchId,
            @Nullable UUID departmentId) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ExpenseRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, Header h, UUID actor) {
        return dsl.insertInto(EXPENSES)
                .set(EXPENSES.COMPANY_ID, companyId)
                .set(header(h))
                .set(EXPENSES.CREATED_BY, actor)
                .set(EXPENSES.UPDATED_BY, actor)
                .returning(EXPENSES.ID)
                .fetchOne(EXPENSES.ID);
    }

    public boolean updateDraft(UUID companyId, UUID id, int version, Header h, UUID actor) {
        return dsl.update(EXPENSES)
                        .set(header(h))
                        .set(EXPENSES.UPDATED_AT, OffsetDateTime.now())
                        .set(EXPENSES.UPDATED_BY, actor)
                        .set(EXPENSES.VERSION, version + 1)
                        .where(EXPENSES.COMPANY_ID.eq(companyId))
                        .and(EXPENSES.ID.eq(id))
                        .and(EXPENSES.VERSION.eq(version))
                        .and(EXPENSES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public void insertLines(UUID companyId, UUID expenseId, List<NewLine> lines, UUID actor) {
        for (NewLine l : lines) {
            dsl.insertInto(EXPENSE_LINES)
                    .set(EXPENSE_LINES.COMPANY_ID, companyId)
                    .set(EXPENSE_LINES.EXPENSE_ID, expenseId)
                    .set(EXPENSE_LINES.LINE_NO, l.lineNo())
                    .set(EXPENSE_LINES.ACCOUNT_ID, l.accountId())
                    .set(EXPENSE_LINES.DESCRIPTION, l.description())
                    .set(EXPENSE_LINES.NET_AMOUNT, l.netAmount())
                    .set(EXPENSE_LINES.TAX_CODE_ID, l.taxCodeId())
                    .set(EXPENSE_LINES.TAX_AMOUNT, l.taxAmount())
                    .set(EXPENSE_LINES.TOTAL_AMOUNT, l.totalAmount())
                    .set(EXPENSE_LINES.BRANCH_ID, l.branchId())
                    .set(EXPENSE_LINES.DEPARTMENT_ID, l.departmentId())
                    .set(EXPENSE_LINES.CREATED_BY, actor)
                    .set(EXPENSE_LINES.UPDATED_BY, actor)
                    .execute();
        }
    }

    public void deleteLines(UUID companyId, UUID expenseId) {
        dsl.deleteFrom(EXPENSE_LINES)
                .where(EXPENSE_LINES.COMPANY_ID.eq(companyId))
                .and(EXPENSE_LINES.EXPENSE_ID.eq(expenseId))
                .execute();
    }

    public boolean markPosted(UUID companyId, UUID id, int version, String number, UUID journalEntryId, UUID actor) {
        return dsl.update(EXPENSES)
                        .set(EXPENSES.STATUS, "POSTED")
                        .set(EXPENSES.NUMBER, number)
                        .set(EXPENSES.JOURNAL_ENTRY_ID, journalEntryId)
                        .set(EXPENSES.POSTED_AT, OffsetDateTime.now())
                        .set(EXPENSES.POSTED_BY, actor)
                        .set(EXPENSES.UPDATED_AT, OffsetDateTime.now())
                        .set(EXPENSES.UPDATED_BY, actor)
                        .set(EXPENSES.VERSION, version + 1)
                        .where(EXPENSES.COMPANY_ID.eq(companyId))
                        .and(EXPENSES.ID.eq(id))
                        .and(EXPENSES.VERSION.eq(version))
                        .and(EXPENSES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public boolean markReversed(UUID companyId, UUID id, int version, UUID reversalEntryId, String reason, UUID actor) {
        return dsl.update(EXPENSES)
                        .set(EXPENSES.STATUS, "REVERSED")
                        .set(EXPENSES.REVERSAL_ENTRY_ID, reversalEntryId)
                        .set(EXPENSES.REVERSAL_REASON, reason)
                        .set(EXPENSES.UPDATED_AT, OffsetDateTime.now())
                        .set(EXPENSES.UPDATED_BY, actor)
                        .set(EXPENSES.VERSION, version + 1)
                        .where(EXPENSES.COMPANY_ID.eq(companyId))
                        .and(EXPENSES.ID.eq(id))
                        .and(EXPENSES.VERSION.eq(version))
                        .and(EXPENSES.STATUS.eq("POSTED"))
                        .execute()
                == 1;
    }

    public boolean delete(UUID companyId, UUID id, int version) {
        return dsl.deleteFrom(EXPENSES)
                        .where(EXPENSES.COMPANY_ID.eq(companyId))
                        .and(EXPENSES.ID.eq(id))
                        .and(EXPENSES.VERSION.eq(version))
                        .and(EXPENSES.STATUS.eq("DRAFT"))
                        .execute()
                == 1;
    }

    public Optional<AccountingViews.Expense> find(UUID companyId, UUID id) {
        return dsl.selectFrom(EXPENSES)
                .where(EXPENSES.COMPANY_ID.eq(companyId))
                .and(EXPENSES.ID.eq(id))
                .fetchOptional(ExpenseRepository::toView);
    }

    public Optional<AccountingViews.Expense> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(EXPENSES)
                .where(EXPENSES.COMPANY_ID.eq(companyId))
                .and(EXPENSES.ID.eq(id))
                .forUpdate()
                .fetchOptional(ExpenseRepository::toView);
    }

    public PageResponse<AccountingViews.Expense> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, EXPENSES, EXPENSES.COMPANY_ID.eq(companyId), query, BINDING, ExpenseRepository::toView);
    }

    public List<AccountingViews.ExpenseLine> lines(UUID companyId, UUID expenseId) {
        return dsl.selectFrom(EXPENSE_LINES)
                .where(EXPENSE_LINES.COMPANY_ID.eq(companyId))
                .and(EXPENSE_LINES.EXPENSE_ID.eq(expenseId))
                .orderBy(EXPENSE_LINES.LINE_NO)
                .fetch(ExpenseRepository::toLine);
    }

    private static Map<Field<?>, Object> header(Header h) {
        Map<Field<?>, Object> values = new LinkedHashMap<>();
        values.put(EXPENSES.EXPENSE_DATE, h.expenseDate());
        values.put(EXPENSES.ACCOUNTING_DATE, h.accountingDate());
        values.put(EXPENSES.PAYEE_NAME, h.payeeName());
        values.put(EXPENSES.PARTNER_ID, h.partnerId());
        values.put(EXPENSES.BANK_ACCOUNT_ID, h.bankAccountId());
        values.put(EXPENSES.CURRENCY_CODE, h.currencyCode());
        values.put(EXPENSES.EXCHANGE_RATE, h.exchangeRate());
        values.put(EXPENSES.PRICES_INCLUDE_TAX, h.pricesIncludeTax());
        values.put(EXPENSES.SUBTOTAL, h.subtotal());
        values.put(EXPENSES.TAX_TOTAL, h.taxTotal());
        values.put(EXPENSES.TOTAL, h.total());
        values.put(EXPENSES.TOTAL_BASE, h.totalBase());
        values.put(EXPENSES.REFERENCE, h.reference());
        values.put(EXPENSES.NOTES, h.notes());
        return values;
    }

    static AccountingViews.Expense toView(ExpensesRecord r) {
        return new AccountingViews.Expense(
                r.getId(),
                r.getNumber(),
                r.getExpenseDate(),
                r.getAccountingDate(),
                r.getPayeeName(),
                r.getPartnerId(),
                r.getBankAccountId(),
                r.getCurrencyCode(),
                r.getExchangeRate(),
                r.getPricesIncludeTax(),
                r.getSubtotal(),
                r.getTaxTotal(),
                r.getTotal(),
                r.getTotalBase(),
                r.getReference(),
                r.getNotes(),
                r.getStatus(),
                r.getJournalEntryId(),
                r.getReversalEntryId(),
                r.getReversalReason(),
                r.getPostedAt(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static AccountingViews.ExpenseLine toLine(ExpenseLinesRecord r) {
        return new AccountingViews.ExpenseLine(
                r.getId(),
                r.getLineNo(),
                r.getAccountId(),
                r.getDescription(),
                r.getNetAmount(),
                r.getTaxCodeId(),
                r.getTaxAmount(),
                r.getTotalAmount(),
                r.getBranchId(),
                r.getDepartmentId());
    }
}
