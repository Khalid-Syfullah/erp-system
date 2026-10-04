package com.erp.payroll;

import static com.erp.support.HrFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AuthTestSupport;
import com.erp.support.HrFixtures;
import com.erp.support.HrFixtures.Person;
import com.erp.support.IntegrationTest;
import com.erp.support.PayrollFixtures;
import com.erp.support.PayrollFixtures.Payroll;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who sees pay (PAY-6, HR-3, HR-4): salary data never appears in HR responses or to HR roles, payroll
 * staff need the payroll permissions, employees see only their own payslips once the run is posted,
 * and per-employee pay in reports needs the payslip permission too. Posted runs and their payslips
 * are frozen in the database (PAY-4).
 */
class PayrollSecurityIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PayrollFixtures payroll;

    @Autowired
    HrFixtures hr;

    @Autowired
    AuthTestSupport auth;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private Payroll p;
    private UUID ann;
    private UUID bob;
    private UUID run;

    @BeforeEach
    void setUp() throws Exception {
        p = payroll.setup();
        LocalDate hired = LocalDate.of(p.year() - 1, 3, 1);
        ann = payroll.employee(p, "E001", hired, "3000");
        bob = payroll.employee(p, "E002", hired, "9000");
        run = payroll.run(p, payroll.period(p, 1), "REGULAR");
        payroll.calculate(p, run);
    }

    @Test
    void hrRolesAndResponsesCarryNoPay() throws Exception {
        // The HR manager holds every HR permission and none of payroll's.
        String employee = hr.body(p.hr().session(), p.path("/employees/" + ann));
        assertThat(employee).doesNotContain("3000").doesNotContain("baseAmount").doesNotContain("salary");
        for (String path : List.of(
                "/employees/" + ann + "/compensations",
                "/payroll-runs/" + run,
                "/payroll-runs/" + run + "/payslips",
                "/payroll-runs/" + run + "/summary",
                "/pay-components")) {
            mvc.perform(get(p.path(path)).cookie(p.hr().session())).andExpect(status().isForbidden());
        }
        // Run approvers see the run but not compensations.
        mvc.perform(get(p.path("/employees/" + ann + "/compensations")).cookie(p.approver()))
                .andExpect(status().isForbidden());
        // Aggregates need payroll.report.read; per-employee pay also payroll.payslip.read.
        Cookie reporter = login("payroll.report.read");
        mvc.perform(get(p.path("/payroll-runs/" + run + "/summary")).cookie(reporter))
                .andExpect(status().isOk());
        mvc.perform(get(p.path("/payroll-runs/" + run + "/register")).cookie(reporter))
                .andExpect(status().isForbidden());
        mvc.perform(get(p.path("/payroll-runs/" + run + "/register")).cookie(p.approver()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslips.length()").value(2));
    }

    @Test
    void employeesSeeOnlyTheirOwnReleasedPayslips() throws Exception {
        Person annSelf = hr.person(p.hr(), ann);
        Person bobSelf = hr.person(p.hr(), bob);
        // Calculated, not posted: nothing to see yet.
        mvc.perform(get(p.path("/me/payslips")).cookie(annSelf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        String annSlipId = JsonPath.read(payroll.payslip(p, run, ann), "$.payslip.id");
        mvc.perform(get(p.path("/me/payslips/" + annSlipId)).cookie(annSelf.session()))
                .andExpect(status().isNotFound());

        expect(payroll.act(p, p.approver(), run, "approve", null), 200);
        expect(payroll.act(p, p.approver(), run, "post", null), 200);
        String mine = hr.body(annSelf.session(), p.path("/me/payslips"));
        assertThat(JsonPath.<List<String>>read(mine, "$.data[*].id")).containsExactly(annSlipId);
        mvc.perform(get(p.path("/me/payslips/" + annSlipId)).cookie(annSelf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslip.netAmount").value("3025.0000"));
        byte[] pdf = mvc.perform(
                        get(p.path("/me/payslips/" + annSlipId + "/pdf")).cookie(annSelf.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // Bob cannot read Ann's payslip, nor can employees use the payroll endpoints.
        mvc.perform(get(p.path("/me/payslips/" + annSlipId)).cookie(bobSelf.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(p.path("/me/payslips/" + annSlipId + "/pdf")).cookie(bobSelf.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get(p.path("/payslips/" + annSlipId)).cookie(bobSelf.session()))
                .andExpect(status().isForbidden());
        // A user without an employee record has no payslips.
        mvc.perform(get(p.path("/me/payslips")).cookie(p.officer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_AN_EMPLOYEE"));
    }

    @Test
    void approversDoNotApproveTheirOwnPay() throws Exception {
        // The approver is linked to Ann's employee record: approving a run that pays them is refused.
        linkUser(ann, p.approverUser().id());
        payroll.act(p, p.approver(), run, "approve", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOD_VIOLATION"));
    }

    @Test
    void postedRunsAndPayslipsAreFrozenInTheDatabase() throws Exception {
        expect(payroll.act(p, p.approver(), run, "approve", null), 200);
        expect(payroll.act(p, p.approver(), run, "post", null), 200);
        assertRefused(
                d -> d.execute(
                        "UPDATE payroll.payroll_runs SET net_total = 0, gross_total = 0, deduction_total = 0"
                                + " WHERE id = ?",
                        run),
                "ck_payroll_runs__frozen");
        assertRefused(d -> d.execute("DELETE FROM payroll.payroll_runs WHERE id = ?", run), "ck_payroll_runs__frozen");
        assertRefused(
                d -> d.execute(
                        "UPDATE payroll.payslips SET net_amount = net_amount + 1, gross_amount = gross_amount + 1"
                                + " WHERE payroll_run_id = ?",
                        run),
                "ck_payslips__frozen");
        assertRefused(
                d -> d.execute(
                        "UPDATE payroll.payslip_lines SET amount = amount + 1 WHERE payslip_id IN"
                                + " (SELECT id FROM payroll.payslips WHERE payroll_run_id = ?)",
                        run),
                "ck_payslip_lines__frozen");
        assertRefused(
                d -> d.execute("DELETE FROM payroll.payslips WHERE payroll_run_id = ?", run), "ck_payslips__frozen");
        // Unapproving or cancelling a posted run is not a transition.
        payroll.act(p, p.approver(), run, "unapprove", null).andExpect(status().isConflict());
    }

    private Cookie login(String... permissions) throws Exception {
        var user = auth.enrollMfa(auth.user());
        auth.assign(user, auth.customRole(permissions), p.hr().company());
        auth.invalidatePermissionCache();
        return auth.login(user);
    }

    private void linkUser(UUID employee, UUID userId) throws Exception {
        expect(
                mvc.perform(
                        AuthTestSupport.unsafe(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        p.path("/employees/" + employee + "/user")))
                                .cookie(p.hr().session())
                                .header(
                                        "If-Match",
                                        com.erp.support.OrgFixtures.etag(hr.version(p.hr(), "/employees/" + employee)))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(com.erp.support.OrgFixtures.json("userId", userId))),
                200);
    }

    private void assertRefused(Consumer<DSLContext> work, String constraint) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> CurrentContext.callWith(
                RequestContext.forRequest("frozen-" + UUID.randomUUID()).withCompany(p.hr().company()),
                () -> tx.execute(s -> {
                    work.accept(dsl);
                    return null;
                })));
        assertThat(thrown).as(constraint).isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PSQLException e && e.getServerErrorMessage() != null) {
                assertThat(e.getServerErrorMessage().getConstraint())
                        .as(e.getMessage())
                        .isEqualTo(constraint);
                return;
            }
        }
        throw new AssertionError("No database error for " + constraint, thrown);
    }
}
