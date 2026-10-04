package com.erp.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.accounting.domain.EntryBalance.Amounts;
import com.erp.accounting.domain.Settlement.Open;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountingDomainTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    // ------------------------------------------------------------------------------ entries

    @Test
    void aBalancedEntryOfOneSidedLinesHasNoProblems() {
        assertThat(EntryBalance.problems(List.of(Amounts.debit(d("100.25")), Amounts.credit(d("100.2500")))))
                .isEmpty();
        assertThat(EntryBalance.problems(List.of(
                        Amounts.signed(d("0.0001")), Amounts.signed(d("0.0002")), Amounts.signed(d("-0.0003")))))
                .isEmpty();
    }

    @Test
    void unbalancedOneLineAndTwoSidedEntriesAreDescribed() {
        assertThat(EntryBalance.problems(List.of(Amounts.debit(d("100")), Amounts.credit(d("99.99")))))
                .singleElement()
                .asString()
                .contains("100", "99.99");
        assertThat(EntryBalance.problems(List.of(Amounts.debit(d("1")))))
                .anySatisfy(p -> assertThat(p).contains("two lines"));
        assertThat(EntryBalance.problems(List.of(new Amounts(d("5"), d("5")), Amounts.credit(d("0")))))
                .filteredOn(p -> p.contains("exactly one positive side"))
                .hasSize(2);
        assertThat(EntryBalance.problems(List.of(Amounts.debit(d("-5")), Amounts.credit(d("-5")))))
                .filteredOn(p -> p.contains("exactly one positive side"))
                .hasSize(2);
    }

    @Test
    void signedAmountsPickTheirSide() {
        assertThat(Amounts.signed(d("-12.5"))).isEqualTo(new Amounts(BigDecimal.ZERO, d("12.5")));
        assertThat(Amounts.signed(d("12.5")).signed()).isEqualByComparingTo("12.5");
        EntryBalance.Totals totals = EntryBalance.totals(List.of(Amounts.debit(d("3")), Amounts.credit(d("1"))));
        assertThat(totals.difference()).isEqualByComparingTo("2");
        assertThat(totals.balanced()).isFalse();
    }

    @Test
    void aRoundingLineBalancesWithinTheToleranceOnly() {
        assertThat(EntryBalance.roundingLine(new EntryBalance.Totals(d("10.01"), d("10.00")), d("0.01")))
                .contains(Amounts.credit(d("0.01")));
        assertThat(EntryBalance.roundingLine(new EntryBalance.Totals(d("10.00"), d("10.01")), d("0.01")))
                .contains(Amounts.debit(d("0.01")));
        assertThat(EntryBalance.roundingLine(new EntryBalance.Totals(d("10"), d("10.0000")), d("0.01")))
                .isEmpty();
        assertThatThrownBy(() -> EntryBalance.roundingLine(new EntryBalance.Totals(d("10.02"), d("10.00")), d("0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entriesAreEditedAsDraftsAndReversedWhenPosted() {
        assertThat(EntryStatus.DRAFT.apply(EntryStatus.Action.POST)).isEqualTo(EntryStatus.POSTED);
        assertThat(EntryStatus.DRAFT.allows(EntryStatus.Action.EDIT)).isTrue();
        assertThat(EntryStatus.DRAFT.allows(EntryStatus.Action.REVERSE)).isFalse();
        assertThat(EntryStatus.POSTED.allows(EntryStatus.Action.EDIT)).isFalse();
        assertThat(EntryStatus.POSTED.allows(EntryStatus.Action.DELETE)).isFalse();
        assertThat(EntryStatus.POSTED.apply(EntryStatus.Action.REVERSE)).isEqualTo(EntryStatus.POSTED);
        assertThatThrownBy(() -> EntryStatus.POSTED.apply(EntryStatus.Action.POST))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------------------ periods

    @Test
    void periodsCloseSoftlyOrHardAndReopen() {
        assertThat(PeriodStatus.OPEN.apply(PeriodStatus.Action.SOFT_CLOSE)).isEqualTo(PeriodStatus.SOFT_CLOSED);
        assertThat(PeriodStatus.OPEN.apply(PeriodStatus.Action.CLOSE)).isEqualTo(PeriodStatus.CLOSED);
        assertThat(PeriodStatus.SOFT_CLOSED.apply(PeriodStatus.Action.CLOSE)).isEqualTo(PeriodStatus.CLOSED);
        assertThat(PeriodStatus.CLOSED.apply(PeriodStatus.Action.REOPEN)).isEqualTo(PeriodStatus.OPEN);
        assertThat(PeriodStatus.OPEN.allows(PeriodStatus.Action.REOPEN)).isFalse();
        assertThat(PeriodStatus.CLOSED.allows(PeriodStatus.Action.SOFT_CLOSE)).isFalse();
        assertThatThrownBy(() -> PeriodStatus.CLOSED.apply(PeriodStatus.Action.CLOSE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyOpenPeriodsTakePostingsUnlessPrivileged() {
        assertThat(PeriodStatus.OPEN.takesPostings(false)).isTrue();
        assertThat(PeriodStatus.SOFT_CLOSED.takesPostings(false)).isFalse();
        assertThat(PeriodStatus.SOFT_CLOSED.takesPostings(true)).isTrue();
        assertThat(PeriodStatus.CLOSED.takesPostings(true)).isFalse();
    }

    @Test
    void theFiscalYearHasTwelveContiguousMonths() {
        LocalDate start = FiscalCalendar.yearStart(LocalDate.of(2026, 3, 15), 4);
        assertThat(start).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(FiscalCalendar.yearEnd(start)).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(FiscalCalendar.code(start)).isEqualTo("2025");
        List<FiscalCalendar.Period> periods = FiscalCalendar.periods(start);
        assertThat(periods).hasSize(12);
        assertThat(periods.getFirst().start()).isEqualTo(start);
        assertThat(periods.getLast().end()).isEqualTo(FiscalCalendar.yearEnd(start));
        for (int i = 1; i < periods.size(); i++) {
            assertThat(periods.get(i).start())
                    .isEqualTo(periods.get(i - 1).end().plusDays(1));
        }
        // February of a leap year.
        assertThat(FiscalCalendar.periods(LocalDate.of(2028, 1, 1)).get(1).end())
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(FiscalCalendar.yearStart(LocalDate.of(2026, 1, 1), 1)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThatThrownBy(() -> FiscalCalendar.yearStart(LocalDate.now(), 13))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------ subledger

    @Test
    void anAllocationReducesBothItemsAtTheirOwnRates() {
        // A EUR invoice of 100 at 1.10 (110.00) paid at 1.20 (120.00): AR gain of 10.00.
        Open invoice = new Open(d("100"), d("110.00"), d("1.10"));
        Open payment = new Open(d("100"), d("120.00"), d("1.20"));
        Settlement.Result full = Settlement.allocate(invoice, payment, d("100"), true, 2, RoundingMode.HALF_UP);
        assertThat(full.targetBase()).isEqualByComparingTo("110.00");
        assertThat(full.counterBase()).isEqualByComparingTo("120.00");
        assertThat(full.fxDifferenceBase()).isEqualByComparingTo("-10.00");
        // The same rates on the payable side are a loss.
        assertThat(Settlement.allocate(invoice, payment, d("100"), false, 2, RoundingMode.HALF_UP)
                        .fxDifferenceBase())
                .isEqualByComparingTo("10.00");

        Settlement.Result part = Settlement.allocate(invoice, payment, d("33.33"), true, 2, RoundingMode.HALF_UP);
        assertThat(part.targetBase()).isEqualByComparingTo("36.66");
        assertThat(part.counterBase()).isEqualByComparingTo("40.00");
        // Same rate: no difference.
        Open same = new Open(d("50"), d("50"), BigDecimal.ONE);
        assertThat(Settlement.allocate(same, same, d("20"), true, 2, RoundingMode.HALF_UP)
                        .fxDifferenceBase())
                .isEqualByComparingTo("0");
    }

    @Test
    void theLastAllocationTakesWhatIsLeftInBase() {
        // Three thirds of 100 at 1.1111: the final third takes the remaining base exactly.
        Open item = new Open(d("33.34"), d("37.05"), d("1.1111"));
        assertThat(Settlement.baseReduction(item, d("33.34"), 2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("37.05");
        assertThat(Settlement.baseReduction(item, d("10"), 2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("11.11");
        // Never more than what is left.
        Open tiny = new Open(d("1"), d("0.01"), d("1.5"));
        assertThat(Settlement.baseReduction(tiny, d("0.99"), 2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("0.01");
    }

    @Test
    void allocationsMustBePositiveAndCovered() {
        Open item = new Open(d("10"), d("10"), BigDecimal.ONE);
        assertThatThrownBy(() -> Settlement.allocate(item, item, d("0"), true, 2, RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.allocate(item, item, d("10.01"), true, 2, RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.allocate(
                        item, new Open(d("5"), d("5"), BigDecimal.ONE), d("6"), true, 2, RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openItemsAgeByDaysPastDue() {
        LocalDate due = LocalDate.of(2026, 5, 31);
        assertThat(Ageing.of(due, due)).isEqualTo(Ageing.CURRENT);
        assertThat(Ageing.of(due, due.minusDays(10))).isEqualTo(Ageing.CURRENT);
        assertThat(Ageing.of(due, due.plusDays(1))).isEqualTo(Ageing.DAYS_1_30);
        assertThat(Ageing.of(due, due.plusDays(30))).isEqualTo(Ageing.DAYS_1_30);
        assertThat(Ageing.of(due, due.plusDays(31))).isEqualTo(Ageing.DAYS_31_60);
        assertThat(Ageing.of(due, due.plusDays(60))).isEqualTo(Ageing.DAYS_31_60);
        assertThat(Ageing.of(due, due.plusDays(61))).isEqualTo(Ageing.DAYS_61_90);
        assertThat(Ageing.of(due, due.plusDays(90))).isEqualTo(Ageing.DAYS_61_90);
        assertThat(Ageing.of(due, due.plusDays(91))).isEqualTo(Ageing.OVER_90);
    }

    @Test
    void paymentsAndExpensesFollowTheirLifecycle() {
        assertThat(DocumentStatus.DRAFT.apply(DocumentStatus.Action.POST)).isEqualTo(DocumentStatus.POSTED);
        assertThat(DocumentStatus.POSTED.apply(DocumentStatus.Action.VOID)).isEqualTo(DocumentStatus.VOIDED);
        assertThat(DocumentStatus.POSTED.apply(DocumentStatus.Action.REVERSE)).isEqualTo(DocumentStatus.REVERSED);
        assertThat(DocumentStatus.POSTED.apply(DocumentStatus.Action.ALLOCATE)).isEqualTo(DocumentStatus.POSTED);
        assertThat(DocumentStatus.DRAFT.allows(DocumentStatus.Action.ALLOCATE)).isFalse();
        assertThat(DocumentStatus.VOIDED.allows(DocumentStatus.Action.VOID)).isFalse();
        assertThat(DocumentStatus.REVERSED.allows(DocumentStatus.Action.EDIT)).isFalse();
        assertThatThrownBy(() -> DocumentStatus.VOIDED.apply(DocumentStatus.Action.ALLOCATE))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------------------ chart

    @Test
    void accountTypesKnowTheirSubtypesNormalSideAndStatement() {
        assertThat(AccountType.ASSET.allows("RECEIVABLE")).isTrue();
        assertThat(AccountType.ASSET.allows("PAYABLE")).isFalse();
        assertThat(AccountType.EXPENSE.allows("OPERATING_EXPENSE")).isTrue();
        assertThat(AccountType.ASSET.debitNormal()).isTrue();
        assertThat(AccountType.EXPENSE.debitNormal()).isTrue();
        assertThat(AccountType.LIABILITY.debitNormal()).isFalse();
        assertThat(AccountType.REVENUE.debitNormal()).isFalse();
        assertThat(AccountType.EQUITY.balanceSheet()).isTrue();
        assertThat(AccountType.REVENUE.balanceSheet()).isFalse();
        assertThat(AccountType.CONTROL_SUBTYPES).contains("RECEIVABLE", "PAYABLE", "INVENTORY", "GRNI");
        for (AccountType type : AccountType.values()) {
            assertThat(type.subtypes()).isNotEmpty();
        }
    }

    @Test
    void mappingKeysTakeMatchingAccountsAndScopes() {
        assertThat(MappingKey.AR_CONTROL.accepts(AccountType.ASSET, "RECEIVABLE"))
                .isTrue();
        assertThat(MappingKey.AR_CONTROL.accepts(AccountType.ASSET, "BANK")).isFalse();
        assertThat(MappingKey.SALES_REVENUE.accepts(AccountType.REVENUE, "OTHER_INCOME"))
                .isTrue();
        assertThat(MappingKey.COGS.accepts(AccountType.REVENUE, "OPERATING_REVENUE"))
                .isFalse();
        assertThat(MappingKey.FX_REALIZED_GAIN.accepts(AccountType.REVENUE, "OTHER_INCOME"))
                .isTrue();
        assertThat(MappingKey.INVENTORY_OPENING.accepts(AccountType.EQUITY, "OPENING_BALANCE_EQUITY"))
                .isTrue();
        assertThat(MappingKey.SUPPLIER_ADVANCE.accepts(AccountType.ASSET, "PREPAYMENT"))
                .isTrue();
        assertThat(MappingKey.CUSTOMER_ADVANCE.accepts(AccountType.LIABILITY, "CUSTOMER_ADVANCE"))
                .isTrue();
        assertThat(MappingKey.COGS.allows(MappingKey.ScopeType.PRODUCT_CATEGORY))
                .isTrue();
        assertThat(MappingKey.COGS.allows(MappingKey.ScopeType.PARTNER_GROUP)).isFalse();
        assertThat(MappingKey.GRNI.allows(MappingKey.ScopeType.DEFAULT)).isTrue();
        assertThat(MappingKey.AP_CONTROL.groupKind()).isEqualTo("SUPPLIER");
        assertThat(MappingKey.AR_CONTROL.groupKind()).isEqualTo("CUSTOMER");
        for (MappingKey key : MappingKey.values()) {
            assertThat(key.allows(MappingKey.ScopeType.DEFAULT)).isTrue();
        }
    }
}
