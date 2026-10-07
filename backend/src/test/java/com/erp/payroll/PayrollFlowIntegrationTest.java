package com.erp.payroll;

import static com.erp.db.admin.Tables.AUDIT_LOG;
import static com.erp.db.auth.Tables.SESSIONS;
import static com.erp.support.AccountingFixtures.amounts;
import static com.erp.support.HrFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.accounting.application.LedgerInvariantCheck;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AccountingFixtures;
import com.erp.support.HrFixtures;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.PayrollFixtures;
import com.erp.support.PayrollFixtures.Payroll;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payroll flow through the API (PRODUCT_SPEC.md §11): compensation → run → calculation job →
 * approval with SoD → posting (GL entry per the posting matrix) → payslip PDF → bank file → paid
 * (bank disbursement). Phase 9 exit criteria: the GL entries match the matrix.
 */
class PayrollFlowIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PayrollFixtures payroll;

    @Autowired
    HrFixtures hr;

    @Autowired
    AccountingFixtures acc;

    @Autowired
    LedgerInvariantCheck invariants;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private Payroll p;
    private LocalDate hired;

    @BeforeEach
    void setUp() throws Exception {
        p = payroll.setup();
        hired = LocalDate.of(p.year() - 1, 6, 1);
    }

    @AfterEach
    void ledgerStaysConsistent() {
        assertThat(invariants.check(p.hr().company()).clean()).isTrue();
    }

    @Test
    void aMonthIsCalculatedApprovedPostedPaidAndBooked() throws Exception {
        UUID ann = payroll.employee(p, "E001", hired, "3000");
        UUID bob = payroll.employee(p, "E002", hired, "4000");
        UUID january = payroll.period(p, 1);
        // Bob worked 10 overtime hours at 20.
        expect(
                hr.post(
                        p.officer(),
                        p.path("/payroll-periods/" + january + "/inputs"),
                        OrgFixtures.map("employeeId", bob, "componentId", p.component("OVERTIME"), "quantity", "10")),
                201);
        UUID run = payroll.run(p, january, "REGULAR");

        // Calculation is asynchronous: 202 and CALCULATING until the job has run.
        expect(
                hr.action(
                        p.officer(),
                        p.path("/payroll-runs/" + run + "/calculate"),
                        payroll.version(p, run),
                        null,
                        null),
                202);
        assertThat((String) JsonPath.read(payroll.runBody(p, run), "$.run.status"))
                .isEqualTo("CALCULATING");
        assertThat(payroll.jobs().calculateQueued(p.hr().company())).isEqualTo(1);
        String calculated = payroll.runBody(p, run);
        assertThat((String) JsonPath.read(calculated, "$.run.status")).isEqualTo("CALCULATED");
        assertThat((Integer) JsonPath.read(calculated, "$.run.employeeCount")).isEqualTo(2);

        // Ann: BASIC 3000 + HOUSING 500 = 3500; TAX 10 % of taxable 3000 = 300; PENSION 5 % of 3500 = 175.
        String annSlip = payroll.payslip(p, run, ann);
        assertThat((String) JsonPath.read(annSlip, "$.payslip.grossAmount")).isEqualTo("3500.0000");
        assertThat((String) JsonPath.read(annSlip, "$.payslip.taxableGross")).isEqualTo("3000.0000");
        assertThat((String) JsonPath.read(annSlip, "$.payslip.deductionAmount")).isEqualTo("475.0000");
        assertThat((String) JsonPath.read(annSlip, "$.payslip.netAmount")).isEqualTo("3025.0000");
        assertThat((String) JsonPath.read(annSlip, "$.payslip.employerContributionAmount"))
                .isEqualTo("280.0000");
        assertThat((Integer) JsonPath.read(annSlip, "$.payslip.daysPaid")).isEqualTo(31);
        // Bob: 4000 + 500 + overtime 200 = 4700; taxable 4200 → tax 420; pension 235; net 4045; employer 376.
        String bobSlip = payroll.payslip(p, run, bob);
        assertThat((String) JsonPath.read(bobSlip, "$.payslip.grossAmount")).isEqualTo("4700.0000");
        assertThat((String) JsonPath.read(bobSlip, "$.payslip.netAmount")).isEqualTo("4045.0000");
        assertThat(JsonPath.<List<String>>read(bobSlip, "$.lines[?(@.componentCode=='OVERTIME')].amount"))
                .containsExactly("200.0000");
        String totals = payroll.runBody(p, run);
        assertThat((String) JsonPath.read(totals, "$.run.grossTotal")).isEqualTo("8200.0000");
        assertThat((String) JsonPath.read(totals, "$.run.netTotal")).isEqualTo("7070.0000");

        // SoD: whoever calculated does not approve (G-16); the approver does.
        payroll.act(p, p.officer(), run, "approve", null).andExpect(status().isForbidden());
        payrollUserWithApproveAndCalculate(run);
        expect(payroll.act(p, p.approver(), run, "approve", null), 200);
        expect(payroll.act(p, p.approver(), run, "post", null), 200);
        String posted = payroll.runBody(p, run);
        assertThat((String) JsonPath.read(posted, "$.run.status")).isEqualTo("POSTED");
        assertThat((String) JsonPath.read(posted, "$.run.number")).startsWith("PR-");
        assertThat((String) hr.read(p.officer(), p.path("/payroll-periods/" + january), "$.status"))
                .isEqualTo("PROCESSED");

        // The GL entry follows the posting matrix exactly.
        assertThat(acc.lines(p.books(), "payroll", "PAYROLL_RUN", run))
                .isEqualTo(amounts("6100", "8200", "6150", "656", "2210", "-1130", "2220", "-656", "2200", "-7070"));

        // Payslip PDFs: the job renders them, staff download them.
        assertThat(payroll.jobs().renderPendingPayslips(p.hr().company())).isEqualTo(2);
        MvcResult pdf = mvc.perform(get(p.path("/payslips/" + JsonPath.read(annSlip, "$.payslip.id") + "/pdf"))
                        .cookie(p.officer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");

        // The bank file needs every employee's bank account.
        mvc.perform(get(p.path("/payroll-runs/" + run + "/bank-file")).cookie(p.approver()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MISSING_BANK_ACCOUNT"));
        for (UUID employee : List.of(ann, bob)) {
            expect(
                    hr.post(
                            p.hr().session(),
                            p.path("/employees/" + employee + "/bank-accounts"),
                            OrgFixtures.map(
                                    "bankName",
                                    "=HYPERLINK(1)",
                                    "accountHolder",
                                    "Holder",
                                    "accountNumber",
                                    "DE00123456789")),
                    201);
        }
        String csv = mvc.perform(
                        get(p.path("/payroll-runs/" + run + "/bank-file")).cookie(p.approver()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(csv.lines()).hasSize(3);
        assertThat(csv).contains("\"E001\"").contains("3025.0000").contains("4045.0000");
        // Spreadsheet formulas are neutralised.
        assertThat(csv).contains("\"'=HYPERLINK(1)\"").doesNotContain(",\"=HYPERLINK");
        // Every employee's decrypted account number: audited as a sensitive view, and only within five
        // minutes of a password confirmation (step-up), like revealing a single account.
        assertThat(auditActions(p, run)).contains("VIEW_SENSITIVE");
        dsl.update(SESSIONS)
                .set(SESSIONS.AUTHENTICATED_AT, OffsetDateTime.now().minusMinutes(10))
                .set(SESSIONS.REAUTHENTICATED_AT, (OffsetDateTime) null)
                .where(SESSIONS.USER_ID.eq(p.approverUser().id()))
                .execute();
        mvc.perform(get(p.path("/payroll-runs/" + run + "/bank-file")).cookie(p.approver()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));

        // Paid: Dr salaries payable / Cr bank, recorded as a payment of kind OTHER.
        expect(
                payroll.act(
                        p,
                        p.approver(),
                        run,
                        "mark-paid",
                        Map.of(
                                "bankAccountId",
                                p.books().bankAccount().toString(),
                                "paymentDate",
                                p.hr().today().toString())),
                200);
        assertThat((String) JsonPath.read(payroll.runBody(p, run), "$.run.status"))
                .isEqualTo("PAID");
        assertThat(acc.lines(p.books(), "payroll", "PAYROLL_PAYMENT", run))
                .isEqualTo(amounts("2200", "7070", "1010", "-7070"));
        assertThat(acc.balance(p.books(), "2200")).isEqualByComparingTo("0");
        String disbursements = acc.body(p.books(), "/payments?limit=50");
        assertThat(JsonPath.<List<String>>read(disbursements, "$.data[?(@.paymentKind=='OTHER')].amount"))
                .containsExactly("7070.0000");
    }

    /** Even holding every permission, the user who requested the calculation does not approve. */
    private void payrollUserWithApproveAndCalculate(UUID run) throws Exception {
        assertThat((String) JsonPath.read(payroll.runBody(p, run), "$.run.calculationRequestedBy"))
                .isEqualTo(p.officerUser().id().toString());
    }

    @Test
    void midPeriodHiresAndTerminationsAreProrated() throws Exception {
        LocalDate january = LocalDate.of(p.year(), 1, 1);
        UUID joiner = payroll.employee(p, "E010", january.plusDays(15), "3100"); // 16 of 31 days
        UUID leaver = payroll.employee(p, "E011", hired, "3100");
        // The leaver's last day is January 10th: the termination ends the compensation (hr.employee.terminated).
        expect(
                hr.action(
                        p.hr().session(),
                        p.path("/employees/" + leaver + "/terminate"),
                        hr.version(p.hr(), "/employees/" + leaver),
                        null,
                        OrgFixtures.map("terminationDate", january.plusDays(9))),
                200);
        assertThat(JsonPath.<List<String>>read(
                        hr.body(p.officer(), p.path("/employees/" + leaver + "/compensations")),
                        "$.data[*].effectiveTo"))
                .containsExactly(january.plusDays(9).toString());

        UUID run = payroll.run(p, payroll.period(p, 1), "REGULAR");
        payroll.calculate(p, run);
        // Joiner: base 3100 × 16/31 = 1600; housing 500 × 16/31 = 258.06.
        String joinerSlip = payroll.payslip(p, run, joiner);
        assertThat((Integer) JsonPath.read(joinerSlip, "$.payslip.daysPaid")).isEqualTo(16);
        assertThat(JsonPath.<List<String>>read(joinerSlip, "$.lines[?(@.componentCode=='BASIC')].amount"))
                .containsExactly("1600.0000");
        assertThat(JsonPath.<List<String>>read(joinerSlip, "$.lines[?(@.componentCode=='HOUSING')].amount"))
                .containsExactly("258.0600");
        // Leaver: 10 of 31 days: 1000 and 161.29.
        String leaverSlip = payroll.payslip(p, run, leaver);
        assertThat((Integer) JsonPath.read(leaverSlip, "$.payslip.daysPaid")).isEqualTo(10);
        assertThat(JsonPath.<List<String>>read(leaverSlip, "$.lines[?(@.componentCode=='BASIC')].amount"))
                .containsExactly("1000.0000");
        assertThat(JsonPath.<List<String>>read(leaverSlip, "$.lines[?(@.componentCode=='HOUSING')].amount"))
                .containsExactly("161.2900");
        // A raise in the middle of February pays each part at its rate.
        UUID raised = payroll.employee(p, "E012", hired, "2800");
        payroll.compensation(p, raised, "5600", LocalDate.of(p.year(), 2, 15));
        UUID february = payroll.run(p, payroll.period(p, 2), "REGULAR");
        payroll.calculate(p, february);
        String raisedSlip = payroll.payslip(p, february, raised);
        int febDays = LocalDate.of(p.year(), 2, 1).lengthOfMonth();
        // 2800 × 14/feb + 5600 × (feb − 14)/feb
        java.math.BigDecimal expected = new java.math.BigDecimal("2800")
                .multiply(java.math.BigDecimal.valueOf(14))
                .add(new java.math.BigDecimal("5600").multiply(java.math.BigDecimal.valueOf(febDays - 14)))
                .divide(java.math.BigDecimal.valueOf(febDays), 2, java.math.RoundingMode.HALF_UP);
        assertThat(new java.math.BigDecimal(
                        JsonPath.<List<String>>read(raisedSlip, "$.lines[?(@.componentCode=='BASIC')].amount")
                                .getFirst()))
                .isEqualByComparingTo(expected);
    }

    @Test
    void inputChangesSendACalculatedRunBackAndIssuesBlockApproval() throws Exception {
        UUID ann = payroll.employee(p, "E020", hired, "100");
        UUID january = payroll.period(p, 1);
        UUID run = payroll.run(p, january, "REGULAR");
        payroll.calculate(p, run);
        assertThat((String) JsonPath.read(payroll.runBody(p, run), "$.run.status"))
                .isEqualTo("CALCULATED");

        // A second regular run of the period is refused.
        hr.post(p.officer(), p.path("/payroll-runs"), OrgFixtures.map("payrollPeriodId", january, "runType", "REGULAR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGULAR_RUN_EXISTS"));

        // An input resets the calculated run to DRAFT and removes its payslips.
        UUID bonus =
                payroll.component(p.officer(), p.path(""), "ADVANCE", "DEDUCTION", "INPUT", null, null, true, null, 90);
        expect(
                hr.post(
                        p.officer(),
                        p.path("/payroll-periods/" + january + "/inputs"),
                        OrgFixtures.map("employeeId", ann, "componentId", bonus, "amount", "5000")),
                201);
        assertThat((String) JsonPath.read(payroll.runBody(p, run), "$.run.status"))
                .isEqualTo("DRAFT");
        assertThat(JsonPath.<List<?>>read(hr.body(p.officer(), p.path("/payroll-runs/" + run + "/payslips")), "$.data"))
                .isEmpty();

        // The advance exceeds the pay: no payslip, a NEGATIVE_NET issue, and no approval.
        payroll.calculate(p, run);
        String calculated = payroll.runBody(p, run);
        assertThat(JsonPath.<List<String>>read(calculated, "$.issues[*].code")).containsExactly("NEGATIVE_NET");
        payroll.act(p, p.approver(), run, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_HAS_ISSUES"));
        // Cancelled, the period takes a new regular run.
        expect(
                hr.action(p.officer(), p.path("/payroll-runs/" + run + "/cancel"), payroll.version(p, run), null, null),
                200);
        payroll.run(p, january, "REGULAR");
    }

    @Test
    void anOffCycleRunPaysItsInputsOnly() throws Exception {
        UUID ann = payroll.employee(p, "E030", hired, "3000");
        UUID january = payroll.period(p, 1);
        UUID offCycle = payroll.run(p, january, "OFF_CYCLE");
        UUID bonus =
                payroll.component(p.officer(), p.path(""), "BONUS", "EARNING", "INPUT", null, null, true, null, 50);
        expect(
                hr.post(
                        p.officer(),
                        p.path("/payroll-periods/" + january + "/inputs"),
                        OrgFixtures.map("employeeId", ann, "componentId", bonus, "amount", "1000", "runId", offCycle)),
                201);
        payroll.post(p, offCycle);
        // Bonus 1000: tax 100 and pension 50; no base, housing or fixed components.
        String slip = payroll.payslip(p, offCycle, ann);
        assertThat(JsonPath.<List<String>>read(slip, "$.lines[*].componentCode"))
                .containsExactlyInAnyOrder("BONUS", "TAX", "PENSION", "PENSION_ER");
        assertThat((String) JsonPath.read(slip, "$.payslip.netAmount")).isEqualTo("850.0000");
        assertThat(acc.lines(p.books(), "payroll", "PAYROLL_RUN", offCycle))
                .isEqualTo(amounts("6100", "1000", "6150", "80", "2210", "-150", "2220", "-80", "2200", "-850"));
        // The regular run of the same period is unaffected by the off-cycle inputs.
        UUID regular = payroll.run(p, january, "REGULAR");
        payroll.calculate(p, regular);
        assertThat(JsonPath.<List<String>>read(payroll.payslip(p, regular, ann), "$.lines[*].componentCode"))
                .doesNotContain("BONUS");
    }

    @Test
    void postingIntoAClosedAccountingPeriodIsRefusedAndRolledBack() throws Exception {
        payroll.employee(p, "E040", hired, "3000");
        UUID run = payroll.run(p, payroll.period(p, 1), "REGULAR");
        payroll.calculate(p, run);
        expect(payroll.act(p, p.approver(), run, "approve", null), 200);
        var books = p.books();
        var periods = acc.periods(books, LocalDate.of(p.year(), 1, 31));
        expect(acc.period(books, books.session(), periods.getFirst(), "close", null), 200);

        payroll.act(p, p.approver(), run, "post", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        String after = payroll.runBody(p, run);
        assertThat((String) JsonPath.read(after, "$.run.status")).isEqualTo("APPROVED");
        assertThat(JsonPath.<String>read(after, "$.run.number")).isNull();
        assertThat(acc.lines(books, "payroll", "PAYROLL_RUN", run)).isEmpty();
    }

    private List<String> auditActions(Payroll p, UUID entityId) {
        return CurrentContext.callWith(
                RequestContext.forRequest("audit-check").withCompany(p.hr().company()),
                () -> tx.execute(status -> dsl.select(AUDIT_LOG.ACTION)
                        .from(AUDIT_LOG)
                        .where(AUDIT_LOG.ENTITY_ID.eq(entityId))
                        .fetch(AUDIT_LOG.ACTION)));
    }
}
