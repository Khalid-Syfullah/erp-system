package com.erp.payroll.application;

import com.erp.hr.api.HrFacade;
import com.erp.payroll.domain.RunStatus;
import com.erp.payroll.persistence.PayslipRepository;
import com.erp.payroll.persistence.RunRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payslips (PAY-6): {@code payroll.payslip.read} sees every payslip; an employee sees only their own,
 * and only once the run is posted (self-service). Nothing here is reachable with HR permissions.
 */
@Service
public class PayslipService {

    /** A payslip with its lines. */
    public record Detail(PayrollViews.Payslip payslip, List<PayrollViews.PayslipLine> lines, RunStatus runStatus) {}

    private final PayslipRepository payslips;
    private final RunRepository runs;
    private final PayslipPdfService pdfs;
    private final HrFacade hr;
    private final PayrollContext context;
    private final AuditPort audit;
    private final TransactionTemplate tx;

    PayslipService(
            PayslipRepository payslips,
            RunRepository runs,
            PayslipPdfService pdfs,
            HrFacade hr,
            PayrollContext context,
            AuditPort audit,
            TransactionTemplate tx) {
        this.payslips = payslips;
        this.runs = runs;
        this.pdfs = pdfs;
        this.hr = hr;
        this.context = context;
        this.audit = audit;
        this.tx = tx;
    }

    @Transactional(readOnly = true)
    public PageResponse<PayrollViews.Payslip> forRun(UUID runId, ListQuery query) {
        UUID companyId = context.companyId();
        runs.find(companyId, runId).orElseThrow(ApiException::notFound);
        return payslips.list(companyId, runId, query);
    }

    @Transactional(readOnly = true)
    public Detail get(UUID payslipId) {
        UUID companyId = context.companyId();
        PayrollViews.Payslip payslip = payslips.find(companyId, payslipId).orElseThrow(ApiException::notFound);
        RunStatus status = runs.find(companyId, payslip.runId()).orElseThrow().status();
        return new Detail(
                payslip, payslips.lines(companyId, List.of(payslipId)).getOrDefault(payslipId, List.of()), status);
    }

    /** The PDF of a released payslip (payroll staff; audited). */
    public byte[] pdf(UUID payslipId) {
        byte[] bytes = pdfs.pdf(payslipId);
        audited(payslipId, "pdf");
        return bytes;
    }

    // ------------------------------------------------------------------------- self-service

    @Transactional(readOnly = true)
    public List<PayrollViews.Payslip> own() {
        return payslips.releasedForEmployee(context.companyId(), me());
    }

    @Transactional(readOnly = true)
    public Detail getOwn(UUID payslipId) {
        Detail detail = get(payslipId);
        if (!detail.payslip().employeeId().equals(me()) || !detail.runStatus().released()) {
            throw ApiException.notFound();
        }
        return detail;
    }

    /** The caller's own released payslip as PDF (call outside a transaction). */
    public byte[] ownPdf(UUID payslipId) {
        tx.execute(status -> getOwn(payslipId));
        return pdfs.pdf(payslipId);
    }

    private UUID me() {
        return hr.employeeOfUser(context.actor())
                .orElseThrow(() -> new ApiException(
                        PayrollErrorCode.NOT_AN_EMPLOYEE, "You are not linked to an employee record in this company."));
    }

    private void audited(UUID payslipId, String what) {
        tx.executeWithoutResult(status -> audit.record(AuditEvent.builder("VIEW_SENSITIVE", "payroll")
                .entity("payslip", payslipId, null)
                .detail("what", what)
                .build()));
    }
}
