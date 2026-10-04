package com.erp.accounting.persistence;

import static com.erp.db.accounting.Tables.ACCOUNTS;
import static com.erp.db.accounting.Tables.BANK_RECONCILIATION_MARKS;
import static com.erp.db.accounting.Tables.JOURNAL_ENTRIES;
import static com.erp.db.accounting.Tables.JOURNAL_LINES;
import static com.erp.db.accounting.Tables.PERIOD_BALANCES;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Read queries over the posted general ledger (posted lines only) for the reports, the period close
 * and the invariant checks, and the period balance snapshots.
 */
@Repository
public class LedgerRepository {

    /** Per account: the balance before the range and the movements in it (base currency). */
    public record AccountMovement(UUID accountId, BigDecimal opening, BigDecimal debit, BigDecimal credit) {

        public BigDecimal closing() {
            return opening.add(debit).subtract(credit);
        }
    }

    /** A posted line with its entry, for ledgers, statements and the cash book. */
    public record LedgerLine(
            UUID lineId,
            UUID entryId,
            @Nullable String entryNumber,
            LocalDate entryDate,
            String entryType,
            String description,
            @Nullable String lineDescription,
            @Nullable String sourceModule,
            @Nullable String sourceType,
            @Nullable UUID sourceId,
            @Nullable String sourceNumber,
            UUID accountId,
            @Nullable UUID partnerId,
            @Nullable UUID taxCodeId,
            BigDecimal debit,
            BigDecimal credit,
            String currencyCode,
            BigDecimal amountCurrency,
            @Nullable String statementReference,
            @Nullable LocalDate statementDate) {}

    /** Σ tax and taxable amounts per tax code and account subtype in a range. */
    public record TaxRow(UUID taxCodeId, String accountSubtype, BigDecimal debit, BigDecimal credit) {}

    private static final Field<BigDecimal> SIGNED = JOURNAL_LINES.DEBIT.sub(JOURNAL_LINES.CREDIT);

    private final DSLContext dsl;

    public LedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Opening balance before {@code from} and movements from {@code from} to {@code to}, per account. */
    public Map<UUID, AccountMovement> movements(UUID companyId, LocalDate from, LocalDate to, @Nullable UUID branchId) {
        Condition base = JOURNAL_LINES
                .COMPANY_ID
                .eq(companyId)
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.ENTRY_DATE.le(to))
                .and(branchId == null ? DSL.noCondition() : JOURNAL_LINES.BRANCH_ID.eq(branchId));
        Field<BigDecimal> opening = DSL.sum(
                        DSL.when(JOURNAL_LINES.ENTRY_DATE.lt(from), SIGNED).otherwise(BigDecimal.ZERO))
                .as("opening");
        Field<BigDecimal> debit = DSL.sum(DSL.when(JOURNAL_LINES.ENTRY_DATE.ge(from), JOURNAL_LINES.DEBIT)
                        .otherwise(BigDecimal.ZERO))
                .as("debit");
        Field<BigDecimal> credit = DSL.sum(DSL.when(JOURNAL_LINES.ENTRY_DATE.ge(from), JOURNAL_LINES.CREDIT)
                        .otherwise(BigDecimal.ZERO))
                .as("credit");
        Map<UUID, AccountMovement> result = new LinkedHashMap<>();
        dsl.select(JOURNAL_LINES.ACCOUNT_ID, opening, debit, credit)
                .from(JOURNAL_LINES)
                .where(base)
                .groupBy(JOURNAL_LINES.ACCOUNT_ID)
                .fetch()
                .forEach(r -> result.put(
                        r.value1(), new AccountMovement(r.value1(), nz(r.value2()), nz(r.value3()), nz(r.value4()))));
        return result;
    }

    /**
     * Signed (debit − credit) movement per account in a range, without the year-end CLOSING entries:
     * the profit and loss of the range.
     */
    public Map<UUID, BigDecimal> resultMovements(
            UUID companyId, LocalDate from, LocalDate to, @Nullable UUID branchId) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        dsl.select(JOURNAL_LINES.ACCOUNT_ID, DSL.sum(SIGNED))
                .from(JOURNAL_LINES)
                .join(JOURNAL_ENTRIES)
                .on(JOURNAL_ENTRIES.ID.eq(JOURNAL_LINES.JOURNAL_ENTRY_ID))
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.ENTRY_DATE.between(from, to))
                .and(JOURNAL_ENTRIES.ENTRY_TYPE.ne("CLOSING"))
                .and(branchId == null ? DSL.noCondition() : JOURNAL_LINES.BRANCH_ID.eq(branchId))
                .groupBy(JOURNAL_LINES.ACCOUNT_ID)
                .fetch()
                .forEach(r -> result.put(r.value1(), nz(r.value2())));
        return result;
    }

    /** Signed (debit − credit) balance per account up to and including {@code asOf}. */
    public Map<UUID, BigDecimal> balancesAsOf(UUID companyId, LocalDate asOf) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        dsl.select(JOURNAL_LINES.ACCOUNT_ID, DSL.sum(SIGNED))
                .from(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.ENTRY_DATE.le(asOf))
                .groupBy(JOURNAL_LINES.ACCOUNT_ID)
                .fetch()
                .forEach(r -> result.put(r.value1(), nz(r.value2())));
        return result;
    }

    /** Signed balance per account over all posted lines. */
    public Map<UUID, BigDecimal> balances(UUID companyId) {
        return balancesAsOf(companyId, LocalDate.of(9999, 12, 31));
    }

    /** Σ debits and Σ credits of all posted lines (the trial balance must be level). */
    public BigDecimal[] totals(UUID companyId) {
        var r = dsl.select(DSL.sum(JOURNAL_LINES.DEBIT), DSL.sum(JOURNAL_LINES.CREDIT))
                .from(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .fetchOne();
        return new BigDecimal[] {nz(r == null ? null : r.value1()), nz(r == null ? null : r.value2())};
    }

    /** Posted lines of accounts in a date range, in posting order, optionally of one partner. */
    public List<LedgerLine> lines(
            UUID companyId, Collection<UUID> accountIds, LocalDate from, LocalDate to, @Nullable UUID partnerId) {
        return dsl.select(
                        JOURNAL_LINES.ID,
                        JOURNAL_ENTRIES.ID,
                        JOURNAL_ENTRIES.NUMBER,
                        JOURNAL_LINES.ENTRY_DATE,
                        JOURNAL_ENTRIES.ENTRY_TYPE,
                        JOURNAL_ENTRIES.DESCRIPTION,
                        JOURNAL_LINES.DESCRIPTION,
                        JOURNAL_ENTRIES.SOURCE_MODULE,
                        JOURNAL_ENTRIES.SOURCE_TYPE,
                        JOURNAL_ENTRIES.SOURCE_ID,
                        JOURNAL_ENTRIES.SOURCE_NUMBER,
                        JOURNAL_LINES.ACCOUNT_ID,
                        JOURNAL_LINES.PARTNER_ID,
                        JOURNAL_LINES.TAX_CODE_ID,
                        JOURNAL_LINES.DEBIT,
                        JOURNAL_LINES.CREDIT,
                        JOURNAL_LINES.CURRENCY_CODE,
                        JOURNAL_LINES.AMOUNT_CURRENCY,
                        BANK_RECONCILIATION_MARKS.STATEMENT_REFERENCE,
                        BANK_RECONCILIATION_MARKS.STATEMENT_DATE)
                .from(JOURNAL_LINES)
                .join(JOURNAL_ENTRIES)
                .on(JOURNAL_ENTRIES.ID.eq(JOURNAL_LINES.JOURNAL_ENTRY_ID))
                .leftJoin(BANK_RECONCILIATION_MARKS)
                .on(BANK_RECONCILIATION_MARKS.JOURNAL_LINE_ID.eq(JOURNAL_LINES.ID))
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.ACCOUNT_ID.in(accountIds))
                .and(JOURNAL_LINES.ENTRY_DATE.between(from, to))
                .and(partnerId == null ? DSL.noCondition() : JOURNAL_LINES.PARTNER_ID.eq(partnerId))
                .orderBy(JOURNAL_LINES.ENTRY_DATE, JOURNAL_ENTRIES.POSTED_AT, JOURNAL_LINES.LINE_NO)
                .fetch(r -> new LedgerLine(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6(),
                        r.value7(),
                        r.value8(),
                        r.value9(),
                        r.value10(),
                        r.value11(),
                        r.value12(),
                        r.value13(),
                        r.value14(),
                        r.value15(),
                        r.value16(),
                        r.value17(),
                        r.value18(),
                        r.value19(),
                        r.value20()));
    }

    /** Signed balance of accounts before a date, optionally of one partner. */
    public BigDecimal balanceBefore(
            UUID companyId, Collection<UUID> accountIds, LocalDate before, @Nullable UUID partnerId) {
        BigDecimal sum = dsl.select(DSL.sum(SIGNED))
                .from(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.ACCOUNT_ID.in(accountIds))
                .and(JOURNAL_LINES.ENTRY_DATE.lt(before))
                .and(partnerId == null ? DSL.noCondition() : JOURNAL_LINES.PARTNER_ID.eq(partnerId))
                .fetchOne(0, BigDecimal.class);
        return nz(sum);
    }

    /** Σ signed document-currency amounts of an account before a date (a bank account's own balance). */
    public BigDecimal currencyBalanceBefore(UUID companyId, UUID accountId, LocalDate before) {
        BigDecimal sum = dsl.select(DSL.sum(JOURNAL_LINES.AMOUNT_CURRENCY))
                .from(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.ACCOUNT_ID.eq(accountId))
                .and(JOURNAL_LINES.ENTRY_DATE.lt(before))
                .fetchOne(0, BigDecimal.class);
        return nz(sum);
    }

    /** Lines with a tax code in a range, summed per tax code and account subtype. */
    public List<TaxRow> taxRows(UUID companyId, LocalDate from, LocalDate to) {
        return dsl.select(
                        JOURNAL_LINES.TAX_CODE_ID,
                        ACCOUNTS.ACCOUNT_SUBTYPE,
                        DSL.sum(JOURNAL_LINES.DEBIT),
                        DSL.sum(JOURNAL_LINES.CREDIT))
                .from(JOURNAL_LINES)
                .join(ACCOUNTS)
                .on(ACCOUNTS.ID.eq(JOURNAL_LINES.ACCOUNT_ID))
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .and(JOURNAL_LINES.TAX_CODE_ID.isNotNull())
                .and(JOURNAL_LINES.ENTRY_DATE.between(from, to))
                .groupBy(JOURNAL_LINES.TAX_CODE_ID, ACCOUNTS.ACCOUNT_SUBTYPE)
                .orderBy(JOURNAL_LINES.TAX_CODE_ID, ACCOUNTS.ACCOUNT_SUBTYPE)
                .fetch(r -> new TaxRow(r.value1(), r.value2(), nz(r.value3()), nz(r.value4())));
    }

    /** Posted entries of a range (optionally of one journal), for the journal report. */
    public List<UUID> entries(UUID companyId, LocalDate from, LocalDate to, @Nullable UUID journalId) {
        return dsl.select(JOURNAL_ENTRIES.ID)
                .from(JOURNAL_ENTRIES)
                .where(JOURNAL_ENTRIES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_ENTRIES.STATUS.eq("POSTED"))
                .and(JOURNAL_ENTRIES.ENTRY_DATE.between(from, to))
                .and(journalId == null ? DSL.noCondition() : JOURNAL_ENTRIES.JOURNAL_ID.eq(journalId))
                .orderBy(JOURNAL_ENTRIES.ENTRY_DATE, JOURNAL_ENTRIES.POSTED_AT, JOURNAL_ENTRIES.ID)
                .limit(5000)
                .fetch(JOURNAL_ENTRIES.ID);
    }

    // ---------------------------------------------------------------------- period snapshots

    public void writeSnapshot(UUID companyId, UUID periodId, Collection<AccountMovement> rows) {
        deleteSnapshot(companyId, periodId);
        for (AccountMovement m : rows) {
            dsl.insertInto(PERIOD_BALANCES)
                    .set(PERIOD_BALANCES.COMPANY_ID, companyId)
                    .set(PERIOD_BALANCES.PERIOD_ID, periodId)
                    .set(PERIOD_BALANCES.ACCOUNT_ID, m.accountId())
                    .set(PERIOD_BALANCES.OPENING_BALANCE, m.opening())
                    .set(PERIOD_BALANCES.DEBIT_TOTAL, m.debit())
                    .set(PERIOD_BALANCES.CREDIT_TOTAL, m.credit())
                    .set(PERIOD_BALANCES.CLOSING_BALANCE, m.closing())
                    .execute();
        }
    }

    public void deleteSnapshot(UUID companyId, UUID periodId) {
        dsl.deleteFrom(PERIOD_BALANCES)
                .where(PERIOD_BALANCES.COMPANY_ID.eq(companyId))
                .and(PERIOD_BALANCES.PERIOD_ID.eq(periodId))
                .execute();
    }

    public Map<UUID, AccountMovement> snapshot(UUID companyId, UUID periodId) {
        Map<UUID, AccountMovement> result = new LinkedHashMap<>();
        dsl.selectFrom(PERIOD_BALANCES)
                .where(PERIOD_BALANCES.COMPANY_ID.eq(companyId))
                .and(PERIOD_BALANCES.PERIOD_ID.eq(periodId))
                .fetch()
                .forEach(r -> result.put(
                        r.getAccountId(),
                        new AccountMovement(
                                r.getAccountId(), r.getOpeningBalance(), r.getDebitTotal(), r.getCreditTotal())));
        return result;
    }

    // --------------------------------------------------------------- bank reconciliation marks

    public boolean mark(
            UUID companyId, UUID lineId, UUID bankAccountId, String reference, LocalDate statementDate, UUID actor) {
        return dsl.insertInto(BANK_RECONCILIATION_MARKS)
                        .set(BANK_RECONCILIATION_MARKS.JOURNAL_LINE_ID, lineId)
                        .set(BANK_RECONCILIATION_MARKS.COMPANY_ID, companyId)
                        .set(BANK_RECONCILIATION_MARKS.BANK_ACCOUNT_ID, bankAccountId)
                        .set(BANK_RECONCILIATION_MARKS.STATEMENT_REFERENCE, reference)
                        .set(BANK_RECONCILIATION_MARKS.STATEMENT_DATE, statementDate)
                        .set(BANK_RECONCILIATION_MARKS.RECONCILED_BY, actor)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    public boolean unmark(UUID companyId, UUID lineId) {
        return dsl.deleteFrom(BANK_RECONCILIATION_MARKS)
                        .where(BANK_RECONCILIATION_MARKS.COMPANY_ID.eq(companyId))
                        .and(BANK_RECONCILIATION_MARKS.JOURNAL_LINE_ID.eq(lineId))
                        .execute()
                == 1;
    }

    /** Posted lines by ID with their account, for reconciliation. */
    public Map<UUID, UUID> postedLineAccounts(UUID companyId, Collection<UUID> lineIds) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        if (lineIds.isEmpty()) {
            return result;
        }
        dsl.select(JOURNAL_LINES.ID, JOURNAL_LINES.ACCOUNT_ID)
                .from(JOURNAL_LINES)
                .where(JOURNAL_LINES.COMPANY_ID.eq(companyId))
                .and(JOURNAL_LINES.ID.in(lineIds))
                .and(JOURNAL_LINES.IS_POSTED.isTrue())
                .fetch()
                .forEach(r -> result.put(r.value1(), r.value2()));
        return result;
    }

    private static BigDecimal nz(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
