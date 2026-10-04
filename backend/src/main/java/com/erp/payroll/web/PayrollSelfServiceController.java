package com.erp.payroll.web;

import com.erp.payroll.application.PayrollViews;
import com.erp.payroll.application.PayslipService;
import com.erp.platform.security.AuthenticatedEndpoint;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own payslips of posted runs (PAY-6, HR-4). */
@RestController
class PayrollSelfServiceController {

    private static final String ME = PayrollSetupController.C + "/me/payslips";

    private final PayslipService payslips;

    PayrollSelfServiceController(PayslipService payslips) {
        this.payslips = payslips;
    }

    record ListResponse<T>(List<T> data) {}

    @AuthenticatedEndpoint
    @GetMapping(ME)
    ListResponse<PayrollViews.Payslip> list(@PathVariable UUID companyId) {
        return new ListResponse<>(payslips.own());
    }

    @AuthenticatedEndpoint
    @GetMapping(ME + "/{payslipId}")
    PayslipService.Detail get(@PathVariable UUID companyId, @PathVariable UUID payslipId) {
        return payslips.getOwn(payslipId);
    }

    @AuthenticatedEndpoint
    @GetMapping(ME + "/{payslipId}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID companyId, @PathVariable UUID payslipId) {
        return PayrollRunController.pdfResponse(payslips.ownPdf(payslipId), payslipId);
    }
}
