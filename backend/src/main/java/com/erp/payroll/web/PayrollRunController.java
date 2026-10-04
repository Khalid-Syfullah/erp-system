package com.erp.payroll.web;

import com.erp.payroll.PayrollPermissions;
import com.erp.payroll.application.BankFileService;
import com.erp.payroll.application.PayrollListings;
import com.erp.payroll.application.PayrollReportService;
import com.erp.payroll.application.PayrollViews;
import com.erp.payroll.application.PayslipService;
import com.erp.payroll.application.RunService;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payroll runs, payslips, the bank file and payroll reports (API.md §17.10). */
@RestController
class PayrollRunController {

    private static final String C = PayrollSetupController.C;

    private final RunService runs;
    private final PayslipService payslips;
    private final BankFileService bankFiles;
    private final PayrollReportService reports;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    PayrollRunController(
            RunService runs,
            PayslipService payslips,
            BankFileService bankFiles,
            PayrollReportService reports,
            ListQueryParser parser,
            IdempotencyExecutor idempotency) {
        this.runs = runs;
        this.payslips = payslips;
        this.bankFiles = bankFiles;
        this.reports = reports;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record RunRequest(
            @NotNull UUID payrollPeriodId,

            @NotNull @Pattern(regexp = "^(REGULAR|OFF_CYCLE|FINAL_SETTLEMENT)$")
            String runType,

            @Size(max = 200) @Nullable String description,
            @Nullable LocalDate accountingDate) {}

    record PaymentRequest(
            @NotNull UUID bankAccountId, @NotNull LocalDate paymentDate) {}

    record RunResponse(PayrollViews.Run run, List<PayrollViews.Issue> issues) {
        static ResponseEntity<RunResponse> entity(RunService.Detail d) {
            return ResponseEntity.ok()
                    .eTag(EntityTags.forVersion(d.run().version()))
                    .body(new RunResponse(d.run(), d.issues()));
        }
    }

    @RequiresPermission(PayrollPermissions.RUN_READ)
    @GetMapping(C + "/payroll-runs")
    PageResponse<PayrollViews.Run> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return runs.list(parser.parse(parameters, PayrollListings.RUNS));
    }

    @RequiresPermission(PayrollPermissions.RUN_READ)
    @GetMapping(C + "/payroll-runs/{runId}")
    ResponseEntity<RunResponse> get(@PathVariable UUID companyId, @PathVariable UUID runId) {
        return RunResponse.entity(runs.get(runId));
    }

    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @PostMapping(C + "/payroll-runs")
    ResponseEntity<RunResponse> create(@PathVariable UUID companyId, @Valid @RequestBody RunRequest request) {
        RunService.Detail created = runs.create(
                request.payrollPeriodId(), request.runType(), request.description(), request.accountingDate());
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/payroll-runs/"
                        + created.run().id()))
                .eTag(EntityTags.forVersion(created.run().version()))
                .body(new RunResponse(created.run(), created.issues()));
    }

    /** Queues the calculation; the run is CALCULATING until the job has paid it. */
    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @PostMapping(C + "/payroll-runs/{runId}/calculate")
    ResponseEntity<RunResponse> calculate(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        RunService.Detail d = runs.calculate(runId, ifMatch);
        return ResponseEntity.accepted()
                .eTag(EntityTags.forVersion(d.run().version()))
                .body(new RunResponse(d.run(), d.issues()));
    }

    @RequiresPermission(PayrollPermissions.RUN_APPROVE)
    @PostMapping(C + "/payroll-runs/{runId}/approve")
    ResponseEntity<?> approve(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(http, null, true, () -> RunResponse.entity(runs.approve(runId, ifMatch)));
    }

    @RequiresPermission(PayrollPermissions.RUN_APPROVE)
    @PostMapping(C + "/payroll-runs/{runId}/unapprove")
    ResponseEntity<?> unapprove(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(http, null, true, () -> RunResponse.entity(runs.unapprove(runId, ifMatch)));
    }

    @RequiresPermission(PayrollPermissions.RUN_POST)
    @PostMapping(C + "/payroll-runs/{runId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(http, null, true, () -> RunResponse.entity(runs.post(runId, ifMatch)));
    }

    @RequiresPermission(PayrollPermissions.RUN_PAY)
    @PostMapping(C + "/payroll-runs/{runId}/mark-paid")
    ResponseEntity<?> markPaid(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody PaymentRequest request,
            HttpServletRequest http) {
        return idempotency.execute(
                http,
                request,
                true,
                () -> RunResponse.entity(
                        runs.markPaid(runId, ifMatch, request.bankAccountId(), request.paymentDate())));
    }

    @RequiresPermission(PayrollPermissions.RUN_PREPARE)
    @PostMapping(C + "/payroll-runs/{runId}/cancel")
    ResponseEntity<RunResponse> cancel(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        return RunResponse.entity(runs.cancel(runId, ifMatch));
    }

    // ------------------------------------------------------------------------------ payslips

    @RequiresPermission(PayrollPermissions.PAYSLIP_READ)
    @GetMapping(C + "/payroll-runs/{runId}/payslips")
    PageResponse<PayrollViews.Payslip> payslips(
            @PathVariable UUID companyId,
            @PathVariable UUID runId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return payslips.forRun(runId, parser.parse(parameters, PayrollListings.PAYSLIPS));
    }

    @RequiresPermission(PayrollPermissions.PAYSLIP_READ)
    @GetMapping(C + "/payslips/{payslipId}")
    PayslipService.Detail payslip(@PathVariable UUID companyId, @PathVariable UUID payslipId) {
        return payslips.get(payslipId);
    }

    @RequiresPermission(PayrollPermissions.PAYSLIP_READ)
    @GetMapping(C + "/payslips/{payslipId}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID companyId, @PathVariable UUID payslipId) {
        return pdfResponse(payslips.pdf(payslipId), payslipId);
    }

    /** The generic CSV bank file (audit-logged: it carries decrypted account numbers). */
    @RequiresPermission(PayrollPermissions.RUN_PAY)
    @GetMapping(C + "/payroll-runs/{runId}/bank-file")
    ResponseEntity<byte[]> bankFile(@PathVariable UUID companyId, @PathVariable UUID runId) {
        BankFileService.BankFile file = bankFiles.export(runId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName())
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.content());
    }

    // ------------------------------------------------------------------------------- reports

    @RequiresPermission(PayrollPermissions.REPORT_READ)
    @GetMapping(C + "/payroll-runs/{runId}/summary")
    PayrollReportService.RunSummary summary(@PathVariable UUID companyId, @PathVariable UUID runId) {
        return reports.summary(runId);
    }

    /** Pay per employee: needs the report and the payslip permission (PAY-6). */
    @RequiresPermission({PayrollPermissions.REPORT_READ, PayrollPermissions.PAYSLIP_READ})
    @GetMapping(C + "/payroll-runs/{runId}/register")
    PayrollReportService.Register register(@PathVariable UUID companyId, @PathVariable UUID runId) {
        return reports.register(runId);
    }

    @RequiresPermission(PayrollPermissions.REPORT_READ)
    @GetMapping(C + "/payroll-reports/component-totals")
    PayrollReportService.ComponentTotals componentTotals(
            @PathVariable UUID companyId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.componentTotals(from, to);
    }

    static ResponseEntity<byte[]> pdfResponse(byte[] bytes, UUID payslipId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("payslip-" + payslipId + ".pdf")
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }
}
