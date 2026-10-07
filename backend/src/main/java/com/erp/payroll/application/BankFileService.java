package com.erp.payroll.application;

import com.erp.hr.api.HrFacade;
import com.erp.payroll.domain.RunStatus;
import com.erp.payroll.persistence.PayslipRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.security.ReauthenticationGuard;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The generic payroll bank file (PRODUCT_SPEC.md §11.1): one CSV row per payslip with the employee's
 * primary bank account and net pay, for approved, posted and paid runs. The decrypted account numbers
 * leave the system here — every employee's at once — so, like revealing one account (SECURITY.md §7),
 * it needs a recent password confirmation (step-up; API tokens cannot) and is audited as
 * {@code VIEW_SENSITIVE}; cells are CSV-injection safe.
 */
@Service
public class BankFileService {

    private static final Set<RunStatus> EXPORTABLE = Set.of(RunStatus.APPROVED, RunStatus.POSTED, RunStatus.PAID);

    /** The file name and content. */
    public record BankFile(String fileName, byte[] content) {}

    private final RunRepository runs;
    private final PayslipRepository payslips;
    private final HrFacade hr;
    private final PayrollContext context;
    private final AuditPort audit;

    BankFileService(
            RunRepository runs, PayslipRepository payslips, HrFacade hr, PayrollContext context, AuditPort audit) {
        this.runs = runs;
        this.payslips = payslips;
        this.hr = hr;
        this.context = context;
        this.audit = audit;
    }

    @Transactional
    public BankFile export(UUID runId) {
        ReauthenticationGuard.require(
                CurrentContext.requireActor(), context.clock().instant());
        UUID companyId = context.companyId();
        PayrollViews.Run run = runs.find(companyId, runId).orElseThrow(ApiException::notFound);
        if (!EXPORTABLE.contains(run.status())) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE, "The bank file is available for approved, posted and paid runs.");
        }
        List<PayrollViews.Payslip> slips = payslips.forRun(companyId, runId);
        Map<UUID, HrFacade.BankDetails> accounts = hr.primaryBankAccounts(
                slips.stream().map(PayrollViews.Payslip::employeeId).toList());
        List<FieldViolation> missing = new ArrayList<>();
        for (PayrollViews.Payslip p : slips) {
            if (p.netAmount().signum() > 0 && !accounts.containsKey(p.employeeId())) {
                missing.add(FieldViolation.atPointer(
                        "/employees/" + p.employeeNumber(), "MISSING_BANK_ACCOUNT", "has no primary bank account"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(
                    PayrollErrorCode.MISSING_BANK_ACCOUNT,
                    missing.size() + " employee(s) have no primary bank account.",
                    missing);
        }
        StringBuilder csv = new StringBuilder(
                "employee_number,employee_name,account_holder,bank_name,account_number,iban,swift_bic,amount,currency,reference\r\n");
        String reference = run.number() == null ? run.id().toString() : run.number();
        int rows = 0;
        for (PayrollViews.Payslip p : slips) {
            if (p.netAmount().signum() <= 0) {
                continue;
            }
            HrFacade.BankDetails a = accounts.get(p.employeeId());
            csv.append(String.join(
                            ",",
                            cell(p.employeeNumber()),
                            cell(p.employeeName()),
                            cell(a.accountHolder()),
                            cell(a.bankName()),
                            cell(a.accountNumber()),
                            cell(a.iban()),
                            cell(a.swiftBic()),
                            p.netAmount().toPlainString(),
                            p.currencyCode(),
                            cell(reference)))
                    .append("\r\n");
            rows++;
        }
        audit.record(AuditEvent.builder("VIEW_SENSITIVE", "payroll")
                .entity("payroll_run", runId, run.number())
                .detail("file", "bank-file")
                .detail("fields", "accountNumber,iban")
                .detail("rows", rows)
                .detail("netTotal", run.netTotal().toPlainString())
                .build());
        return new BankFile("bank-file-" + reference + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Quotes the value and neutralises spreadsheet formulas (SECURITY.md §6). */
    static String cell(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
