package com.erp.accounting.application;

import com.erp.accounting.domain.AccountType;
import com.erp.accounting.domain.Ageing;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.CompanyBankAccountRepository;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.LedgerRepository;
import com.erp.accounting.persistence.OpenItemRepository;
import com.erp.org.api.OrgFacade;
import com.erp.org.api.TaxCodeSummary;
import com.erp.partners.api.PartnersFacade;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The financial reports (PRODUCT_SPEC.md §8.10) as JSON, computed from the posted general ledger
 * (closed periods are immutable, so a live computation equals the period snapshots). File exports
 * (CSV, XLSX, PDF) come with the document rendering pipeline (ADR-038).
 */
@Service
public class ReportService {

    static final int MAX_DAYS = 3660;

    private final LedgerRepository ledger;
    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final OpenItemRepository openItems;
    private final CompanyBankAccountRepository bankAccounts;
    private final PartnersFacade partners;
    private final OrgFacade org;
    private final AccountingContext context;

    ReportService(
            LedgerRepository ledger,
            AccountRepository accounts,
            EntryRepository entries,
            OpenItemRepository openItems,
            CompanyBankAccountRepository bankAccounts,
            PartnersFacade partners,
            OrgFacade org,
            AccountingContext context) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.entries = entries;
        this.openItems = openItems;
        this.bankAccounts = bankAccounts;
        this.partners = partners;
        this.org = org;
        this.context = context;
    }

    /** Opening balance, debits, credits and closing balance per account; zero rows only if asked. */
    @Transactional(readOnly = true)
    public AccountingReports.TrialBalance trialBalance(
            LocalDate from, LocalDate to, @Nullable UUID branchId, boolean includeZero) {
        range(from, to);
        UUID companyId = context.companyId();
        var movements = ledger.movements(companyId, from, to, branchId);
        List<AccountingReports.TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal closingDebit = BigDecimal.ZERO;
        BigDecimal closingCredit = BigDecimal.ZERO;
        for (AccountingViews.Account account : accounts.all(companyId)) {
            var m = movements.get(account.id());
            if (m == null && !includeZero) {
                continue;
            }
            BigDecimal opening = m == null ? BigDecimal.ZERO : m.opening();
            BigDecimal d = m == null ? BigDecimal.ZERO : m.debit();
            BigDecimal c = m == null ? BigDecimal.ZERO : m.credit();
            BigDecimal closing = opening.add(d).subtract(c);
            if (!includeZero && opening.signum() == 0 && d.signum() == 0 && c.signum() == 0) {
                continue;
            }
            rows.add(new AccountingReports.TrialBalanceRow(ref(account), opening, d, c, closing));
            debit = debit.add(d);
            credit = credit.add(c);
            if (closing.signum() > 0) {
                closingDebit = closingDebit.add(closing);
            } else {
                closingCredit = closingCredit.add(closing.negate());
            }
        }
        return new AccountingReports.TrialBalance(from, to, branchId, rows, debit, credit, closingDebit, closingCredit);
    }

    /** The posted lines of an account with a running balance. */
    @Transactional(readOnly = true)
    public AccountingReports.GeneralLedger generalLedger(UUID accountId, LocalDate from, LocalDate to) {
        range(from, to);
        UUID companyId = context.companyId();
        AccountingViews.Account account = accounts.find(companyId, accountId).orElseThrow(ApiException::notFound);
        BigDecimal opening = ledger.balanceBefore(companyId, List.of(accountId), from, null);
        List<AccountingReports.LedgerRow> rows =
                rows(ledger.lines(companyId, List.of(accountId), from, to, null), opening);
        return new AccountingReports.GeneralLedger(
                ref(account),
                from,
                to,
                opening,
                rows,
                rows.isEmpty() ? opening : rows.getLast().balance());
    }

    /** Posted entries of a range with their lines (at most 5 000 entries). */
    @Transactional(readOnly = true)
    public AccountingReports.JournalReport journal(LocalDate from, LocalDate to, @Nullable UUID journalId) {
        range(from, to);
        UUID companyId = context.companyId();
        List<AccountingViews.JournalEntryDetail> details = new ArrayList<>();
        for (UUID id : ledger.entries(companyId, from, to, journalId)) {
            details.add(new AccountingViews.JournalEntryDetail(
                    entries.find(companyId, id).orElseThrow(), entries.lines(companyId, id)));
        }
        return new AccountingReports.JournalReport(from, to, details);
    }

    /** Revenue and expenses of a range (without year-end closing entries), optionally against another. */
    @Transactional(readOnly = true)
    public AccountingReports.ProfitAndLoss profitAndLoss(
            LocalDate from,
            LocalDate to,
            @Nullable LocalDate compareFrom,
            @Nullable LocalDate compareTo,
            @Nullable UUID branchId) {
        range(from, to);
        boolean compare = compareFrom != null && compareTo != null;
        if (compare) {
            range(compareFrom, compareTo);
        }
        UUID companyId = context.companyId();
        Map<UUID, BigDecimal> current = ledger.resultMovements(companyId, from, to, branchId);
        Map<UUID, BigDecimal> previous =
                compare ? ledger.resultMovements(companyId, compareFrom, compareTo, branchId) : Map.of();
        List<AccountingReports.StatementLine> revenue = new ArrayList<>();
        List<AccountingReports.StatementLine> expenses = new ArrayList<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal comparisonRevenue = BigDecimal.ZERO;
        BigDecimal comparisonExpenses = BigDecimal.ZERO;
        for (AccountingViews.Account account : accounts.all(companyId)) {
            AccountType type = AccountType.valueOf(account.accountType());
            if (type.balanceSheet()) {
                continue;
            }
            BigDecimal signed = current.getOrDefault(account.id(), BigDecimal.ZERO);
            BigDecimal signedBefore = previous.getOrDefault(account.id(), BigDecimal.ZERO);
            if (signed.signum() == 0 && signedBefore.signum() == 0) {
                continue;
            }
            if (type == AccountType.REVENUE) {
                revenue.add(new AccountingReports.StatementLine(
                        ref(account), signed.negate(), compare ? signedBefore.negate() : null));
                totalRevenue = totalRevenue.add(signed.negate());
                comparisonRevenue = comparisonRevenue.add(signedBefore.negate());
            } else {
                expenses.add(new AccountingReports.StatementLine(ref(account), signed, compare ? signedBefore : null));
                totalExpenses = totalExpenses.add(signed);
                comparisonExpenses = comparisonExpenses.add(signedBefore);
            }
        }
        return new AccountingReports.ProfitAndLoss(
                from,
                to,
                compareFrom,
                compareTo,
                revenue,
                expenses,
                totalRevenue,
                totalExpenses,
                totalRevenue.subtract(totalExpenses),
                compare ? comparisonRevenue.subtract(comparisonExpenses) : null);
    }

    /**
     * Assets, liabilities and equity as of a date, with the result not yet closed into retained
     * earnings shown as current earnings (ACC-7: assets = liabilities + equity + current earnings).
     */
    @Transactional(readOnly = true)
    public AccountingReports.BalanceSheet balanceSheet(LocalDate asOf) {
        UUID companyId = context.companyId();
        Map<UUID, BigDecimal> balances = ledger.balancesAsOf(companyId, asOf);
        List<AccountingReports.StatementLine> assets = new ArrayList<>();
        List<AccountingReports.StatementLine> liabilities = new ArrayList<>();
        List<AccountingReports.StatementLine> equity = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;
        BigDecimal earnings = BigDecimal.ZERO;
        for (AccountingViews.Account account : accounts.all(companyId)) {
            BigDecimal signed = balances.getOrDefault(account.id(), BigDecimal.ZERO);
            if (signed.signum() == 0) {
                continue;
            }
            switch (AccountType.valueOf(account.accountType())) {
                case ASSET -> {
                    assets.add(new AccountingReports.StatementLine(ref(account), signed, null));
                    totalAssets = totalAssets.add(signed);
                }
                case LIABILITY -> {
                    liabilities.add(new AccountingReports.StatementLine(ref(account), signed.negate(), null));
                    totalLiabilities = totalLiabilities.add(signed.negate());
                }
                case EQUITY -> {
                    equity.add(new AccountingReports.StatementLine(ref(account), signed.negate(), null));
                    totalEquity = totalEquity.add(signed.negate());
                }
                case REVENUE, EXPENSE -> earnings = earnings.add(signed.negate());
            }
        }
        boolean balanced =
                totalAssets.compareTo(totalLiabilities.add(totalEquity).add(earnings)) == 0;
        return new AccountingReports.BalanceSheet(
                asOf, assets, liabilities, equity, totalAssets, totalLiabilities, totalEquity, earnings, balanced);
    }

    /** Open items by partner and days past due as of a date (document currency converted at the item rate). */
    @Transactional(readOnly = true)
    public AccountingReports.Ageing ageing(String kind, LocalDate asOf) {
        UUID companyId = context.companyId();
        Map<UUID, EnumMap<Ageing, BigDecimal>> byPartner = new LinkedHashMap<>();
        EnumMap<Ageing, BigDecimal> total = buckets();
        var rounding = context.baseRounding();
        for (OpenItemRepository.AgedItem aged : openItems.asOf(companyId, kind, asOf, null)) {
            if (aged.openAsOf().signum() == 0) {
                continue;
            }
            BigDecimal base = aged.openAsOf().compareTo(aged.item().originalAmount()) == 0
                    ? aged.item().originalAmountBase()
                    : rounding.round(aged.openAsOf().multiply(aged.item().exchangeRate()));
            Ageing bucket = Ageing.of(aged.item().dueDate(), asOf);
            byPartner.computeIfAbsent(aged.item().partnerId(), k -> buckets()).merge(bucket, base, BigDecimal::add);
            total.merge(bucket, base, BigDecimal::add);
        }
        Map<UUID, PartnersFacade.PartnerSummary> names = partners.partners(byPartner.keySet());
        List<AccountingReports.AgeingRow> rows = new ArrayList<>();
        byPartner.forEach((partnerId, buckets) -> {
            var partner = names.get(partnerId);
            rows.add(new AccountingReports.AgeingRow(
                    partnerId,
                    partner == null ? null : partner.code(),
                    partner == null ? null : partner.name(),
                    toBuckets(buckets)));
        });
        rows.sort((a, b) -> String.valueOf(a.partnerCode()).compareTo(String.valueOf(b.partnerCode())));
        return new AccountingReports.Ageing(kind, asOf, rows, toBuckets(total));
    }

    /** A partner's movements on the AR or AP control accounts with a running balance. */
    @Transactional(readOnly = true)
    public AccountingReports.PartnerStatement partnerStatement(
            UUID partnerId, String kind, LocalDate from, LocalDate to) {
        range(from, to);
        UUID companyId = context.companyId();
        String subtype = "PAYABLE".equals(kind) ? "PAYABLE" : "RECEIVABLE";
        Set<UUID> control = new LinkedHashSet<>();
        accounts.all(companyId).stream()
                .filter(a -> a.accountSubtype().equals(subtype))
                .forEach(a -> control.add(a.id()));
        var partner = partners.partners(List.of(partnerId)).get(partnerId);
        if (partner == null) {
            throw ApiException.notFound();
        }
        BigDecimal opening = ledger.balanceBefore(companyId, control, from, partnerId);
        List<AccountingReports.LedgerRow> rows = rows(ledger.lines(companyId, control, from, to, partnerId), opening);
        return new AccountingReports.PartnerStatement(
                partnerId,
                partner.code(),
                partner.name(),
                subtype,
                from,
                to,
                opening,
                rows,
                rows.isEmpty() ? opening : rows.getLast().balance());
    }

    /** Output and input tax per tax code, with the taxable amounts, in a range. */
    @Transactional(readOnly = true)
    public AccountingReports.TaxSummary taxSummary(LocalDate from, LocalDate to) {
        range(from, to);
        UUID companyId = context.companyId();
        Map<UUID, BigDecimal[]> byCode = new LinkedHashMap<>();
        for (LedgerRepository.TaxRow row : ledger.taxRows(companyId, from, to)) {
            BigDecimal[] sums = byCode.computeIfAbsent(row.taxCodeId(), k ->
                    new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal net = row.debit().subtract(row.credit());
            switch (row.accountSubtype()) {
                case "TAX_PAYABLE" -> sums[1] = sums[1].add(net.negate());
                case "TAX_RECEIVABLE" -> sums[3] = sums[3].add(net);
                case "OPERATING_REVENUE", "OTHER_INCOME" -> sums[0] = sums[0].add(net.negate());
                default -> sums[2] = sums[2].add(net);
            }
        }
        List<AccountingReports.TaxRow> rows = new ArrayList<>();
        BigDecimal output = BigDecimal.ZERO;
        BigDecimal input = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal[]> e : byCode.entrySet()) {
            TaxCodeSummary code = org.taxCode(companyId, e.getKey()).orElse(null);
            BigDecimal[] s = e.getValue();
            rows.add(new AccountingReports.TaxRow(
                    e.getKey(),
                    code == null ? null : code.code(),
                    code == null ? null : code.ratePercent(),
                    s[0],
                    s[1],
                    s[2],
                    s[3],
                    s[1].subtract(s[3])));
            output = output.add(s[1]);
            input = input.add(s[3]);
        }
        return new AccountingReports.TaxSummary(from, to, rows, output, input, output.subtract(input));
    }

    /**
     * The cash and bank book: the bank account's posted lines with a running balance in the
     * account's currency and their reconciliation marks ("bank transactions").
     */
    @Transactional(readOnly = true)
    public AccountingReports.CashBook cashBook(UUID bankAccountId, LocalDate from, LocalDate to) {
        range(from, to);
        UUID companyId = context.companyId();
        AccountingViews.BankAccount bank =
                bankAccounts.find(companyId, bankAccountId).orElseThrow(ApiException::notFound);
        BigDecimal opening = ledger.currencyBalanceBefore(companyId, bank.accountId(), from);
        BigDecimal balance = opening;
        List<AccountingViews.BankTransaction> transactions = new ArrayList<>();
        for (LedgerRepository.LedgerLine line : ledger.lines(companyId, List.of(bank.accountId()), from, to, null)) {
            balance = balance.add(line.amountCurrency());
            transactions.add(new AccountingViews.BankTransaction(
                    line.lineId(),
                    line.entryId(),
                    line.entryNumber(),
                    line.entryDate(),
                    line.lineDescription() != null ? line.lineDescription() : line.description(),
                    line.sourceModule(),
                    line.sourceType(),
                    line.sourceId(),
                    line.sourceNumber(),
                    line.debit(),
                    line.credit(),
                    line.amountCurrency(),
                    balance,
                    line.statementReference(),
                    line.statementDate()));
        }
        return new AccountingReports.CashBook(bank, from, to, opening, transactions, balance);
    }

    // ------------------------------------------------------------------------------ helpers

    private static List<AccountingReports.LedgerRow> rows(List<LedgerRepository.LedgerLine> lines, BigDecimal opening) {
        List<AccountingReports.LedgerRow> rows = new ArrayList<>();
        BigDecimal balance = opening;
        for (LedgerRepository.LedgerLine line : lines) {
            balance = balance.add(line.debit()).subtract(line.credit());
            rows.add(new AccountingReports.LedgerRow(
                    line.lineId(),
                    line.entryId(),
                    line.entryNumber(),
                    line.entryDate(),
                    line.lineDescription() != null ? line.lineDescription() : line.description(),
                    line.sourceModule(),
                    line.sourceType(),
                    line.sourceId(),
                    line.sourceNumber(),
                    line.partnerId(),
                    line.debit(),
                    line.credit(),
                    line.currencyCode(),
                    line.amountCurrency(),
                    balance));
        }
        return rows;
    }

    private static AccountingReports.AccountRef ref(AccountingViews.Account a) {
        return new AccountingReports.AccountRef(a.id(), a.code(), a.name(), a.accountType(), a.accountSubtype());
    }

    private static EnumMap<Ageing, BigDecimal> buckets() {
        EnumMap<Ageing, BigDecimal> map = new EnumMap<>(Ageing.class);
        for (Ageing a : Ageing.values()) {
            map.put(a, BigDecimal.ZERO);
        }
        return map;
    }

    private static AccountingReports.AgeingBuckets toBuckets(EnumMap<Ageing, BigDecimal> map) {
        BigDecimal total = map.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AccountingReports.AgeingBuckets(
                map.get(Ageing.CURRENT),
                map.get(Ageing.DAYS_1_30),
                map.get(Ageing.DAYS_31_60),
                map.get(Ageing.DAYS_61_90),
                map.get(Ageing.OVER_90),
                total);
    }

    private static void range(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw ApiException.validationFailed(
                    "The date range is invalid.",
                    List.of(FieldViolation.atParameter(
                            "to", "INVALID_VALUE", "must not be before 'from' nor more than 10 years after it")));
        }
    }
}
