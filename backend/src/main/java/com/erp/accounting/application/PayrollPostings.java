package com.erp.accounting.application;

import com.erp.accounting.api.AccountingReports;
import com.erp.accounting.domain.MappingKey;
import com.erp.accounting.domain.MappingKey.ScopeType;
import com.erp.accounting.persistence.AccountRepository;
import com.erp.accounting.persistence.CompanyBankAccountRepository;
import com.erp.accounting.persistence.EntryRepository;
import com.erp.accounting.persistence.PaymentRepository;
import com.erp.payroll.events.PayrollRunPaid;
import com.erp.payroll.events.PayrollRunPosted;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Payroll's GL entries (PRODUCT_SPEC.md §8.6), booked synchronously in Payroll's transaction (ADR-005):
 *
 * <ul>
 *   <li>{@code payroll.run.posted}: Dr SALARY_EXPENSE [pay component, department] for earnings, Dr
 *       EMPLOYER_CONTRIBUTION_EXPENSE [component, department]; Cr PAYROLL_DEDUCTION_LIABILITY [component]
 *       for deductions, Cr EMPLOYER_CONTRIBUTION_LIABILITY [component], Cr SALARIES_PAYABLE for the net.
 *   <li>{@code payroll.run.paid}: Dr SALARIES_PAYABLE / Cr the bank account's GL account, and a payment
 *       of kind OTHER records the disbursement.
 * </ul>
 *
 * Each event is booked once; a refused posting (closed period, missing mapping, unusable bank account)
 * rolls the payroll step back.
 */
@Component
class PayrollPostings {

    static final String PAYROLL = "payroll";

    private final PostingService engine;
    private final AccountDetermination determination;
    private final EntryRepository entries;
    private final CompanyBankAccountRepository bankAccounts;
    private final AccountRepository accounts;
    private final PaymentRepository payments;
    private final DocumentNumberService numbering;
    private final AccountingContext context;
    private final AuditPort audit;

    PayrollPostings(
            PostingService engine,
            AccountDetermination determination,
            EntryRepository entries,
            CompanyBankAccountRepository bankAccounts,
            AccountRepository accounts,
            PaymentRepository payments,
            DocumentNumberService numbering,
            AccountingContext context,
            AuditPort audit) {
        this.engine = engine;
        this.determination = determination;
        this.entries = entries;
        this.bankAccounts = bankAccounts;
        this.accounts = accounts;
        this.payments = payments;
        this.numbering = numbering;
        this.context = context;
        this.audit = audit;
    }

    @EventListener
    void on(PayrollRunPosted event) {
        UUID companyId = CurrentContext.requireCompany();
        if (entries.findByEvent(companyId, event.metadata().eventId()).isPresent()) {
            return;
        }
        String base = context.profile().baseCurrency();
        requireBase(event.currencyCode(), base);
        List<PostingService.Line> lines = new ArrayList<>();
        for (PayrollRunPosted.Line line : event.lines()) {
            List<AccountDetermination.Scope> componentThenDepartment = new ArrayList<>();
            componentThenDepartment.add(new AccountDetermination.Scope(ScopeType.PAY_COMPONENT, line.componentId()));
            componentThenDepartment.add(new AccountDetermination.Scope(ScopeType.DEPARTMENT, line.departmentId()));
            List<AccountDetermination.Scope> component =
                    AccountDetermination.of(ScopeType.PAY_COMPONENT, line.componentId());
            switch (line.kind()) {
                case "EARNING" ->
                    lines.add(PostingService.Line.base(
                                    determination.resolve(MappingKey.SALARY_EXPENSE, componentThenDepartment),
                                    line.amount(),
                                    base)
                            .withDimensions(line.branchId(), line.departmentId(), null));
                case "DEDUCTION" ->
                    lines.add(PostingService.Line.base(
                                    determination.resolve(MappingKey.PAYROLL_DEDUCTION_LIABILITY, component),
                                    line.amount().negate(),
                                    base)
                            .withDimensions(line.branchId(), line.departmentId(), null));
                case "EMPLOYER_CONTRIBUTION" -> {
                    lines.add(PostingService.Line.base(
                                    determination.resolve(
                                            MappingKey.EMPLOYER_CONTRIBUTION_EXPENSE, componentThenDepartment),
                                    line.amount(),
                                    base)
                            .withDimensions(line.branchId(), line.departmentId(), null));
                    lines.add(PostingService.Line.base(
                                    determination.resolve(MappingKey.EMPLOYER_CONTRIBUTION_LIABILITY, component),
                                    line.amount().negate(),
                                    base)
                            .withDimensions(line.branchId(), line.departmentId(), null));
                }
                default -> throw new IllegalArgumentException("Unknown payroll line kind " + line.kind());
            }
        }
        if (event.netPayTotal().signum() != 0) {
            lines.add(PostingService.Line.base(
                    determination.resolve(MappingKey.SALARIES_PAYABLE),
                    event.netPayTotal().negate(),
                    base));
        }
        if (lines.isEmpty()) {
            return;
        }
        engine.post(new PostingService.Request(
                ChartTemplate.Journals.PAYROLL,
                event.accountingDate(),
                "SYSTEM",
                "Payroll " + event.number() + " (" + event.runType() + ")",
                base,
                BigDecimal.ONE,
                new PostingService.Source(
                        PAYROLL,
                        "PAYROLL_RUN",
                        event.runId(),
                        event.number(),
                        event.metadata().eventId()),
                null,
                lines,
                null,
                false,
                false));
    }

    @EventListener
    void on(PayrollRunPaid event) {
        UUID companyId = CurrentContext.requireCompany();
        if (entries.findByEvent(companyId, event.metadata().eventId()).isPresent()) {
            return;
        }
        String base = context.profile().baseCurrency();
        requireBase(event.currencyCode(), base);
        AccountingReports.BankAccount bank = bankAccounts
                .forUse(companyId, event.bankAccountId())
                .filter(AccountingReports.BankAccount::active)
                .orElseThrow(() -> ApiException.validationFailed(
                        "The bank account is invalid.",
                        List.of(FieldViolation.atPointer(
                                "/bankAccountId",
                                "UNKNOWN_BANK_ACCOUNT",
                                "must be an active bank account of the company"))));
        if (!bank.currencyCode().equals(base)) {
            throw ApiException.validationFailed(
                    "The bank account is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/bankAccountId", "CURRENCY_MISMATCH", "must be in the base currency " + base)));
        }
        String journal = "CASH"
                        .equals(accounts.find(companyId, bank.accountId())
                                .orElseThrow()
                                .accountSubtype())
                ? ChartTemplate.Journals.CASH
                : ChartTemplate.Journals.BANK;
        PostingService.Posted posted = engine.post(new PostingService.Request(
                journal,
                event.paymentDate(),
                "SYSTEM",
                "Salaries paid: payroll " + event.number(),
                base,
                BigDecimal.ONE,
                new PostingService.Source(
                        PAYROLL,
                        "PAYROLL_PAYMENT",
                        event.runId(),
                        event.number(),
                        event.metadata().eventId()),
                null,
                List.of(
                        PostingService.Line.base(
                                determination.resolve(MappingKey.SALARIES_PAYABLE), event.amount(), base),
                        PostingService.Line.base(
                                bank.accountId(), event.amount().negate(), base)),
                null,
                false,
                false));
        String number = numbering.next(
                companyId,
                AccountingConfiguration.SUPPLIER_PAYMENT,
                FiscalYears.label(event.paymentDate(), context.profile().fiscalYearStartMonth()));
        UUID payment = payments.insertDisbursement(
                companyId,
                number,
                bank.id(),
                event.paymentDate(),
                base,
                event.amount(),
                "Payroll " + event.number(),
                posted.entryId(),
                PAYROLL,
                "PAYROLL_RUN",
                event.runId(),
                event.metadata().actorUserId());
        audit.record(AuditEvent.builder("POST", "accounting")
                .entity("payment", payment, number)
                .detail("source", "payroll " + event.number())
                .detail("amount", event.amount().toPlainString())
                .detail("journalEntryId", posted.entryId())
                .build());
    }

    private static void requireBase(String currency, String base) {
        if (!currency.equals(base)) {
            throw new IllegalStateException("Payroll is booked in the base currency, not " + currency);
        }
    }
}
