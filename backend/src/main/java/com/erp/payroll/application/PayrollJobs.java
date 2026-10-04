package com.erp.payroll.application;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The asynchronous payroll work of one company (PRODUCT_SPEC.md §11.1): queued run calculations and
 * the PDFs of released payslips. The worker's recurring tasks call these for every company; tests
 * call them directly.
 */
@Component
public class PayrollJobs {

    private static final Logger log = LoggerFactory.getLogger(PayrollJobs.class);
    private static final int MAX_RUNS = 20;

    private final PayrollEngine engine;
    private final PayslipPdfService pdfs;

    PayrollJobs(PayrollEngine engine, PayslipPdfService pdfs) {
        this.engine = engine;
        this.pdfs = pdfs;
    }

    /** Calculates the company's queued runs; a failing run returns to DRAFT with the error as an issue. */
    public int calculateQueued(UUID companyId) {
        return inCompany(companyId, "job-payroll-calculation", () -> {
            int done = 0;
            for (int i = 0; i < MAX_RUNS; i++) {
                UUID runId = engine.nextQueued();
                if (runId == null) {
                    break;
                }
                try {
                    if (engine.calculate(runId)) {
                        done++;
                    } else {
                        break;
                    }
                } catch (RuntimeException e) {
                    log.error("Payroll calculation of run {} failed", runId, e);
                    engine.fail(runId, "The calculation failed: " + e.getMessage());
                }
            }
            return done;
        });
    }

    /** Renders the PDFs of released payslips that have none yet. */
    public int renderPendingPayslips(UUID companyId) {
        return inCompany(companyId, "job-payroll-payslip-pdfs", pdfs::renderPending);
    }

    private static int inCompany(UUID companyId, String name, java.util.function.Supplier<Integer> work) {
        return CurrentContext.callWith(
                RequestContext.forRequest(name + "-" + UUID.randomUUID()).withCompany(companyId), work::get);
    }
}
