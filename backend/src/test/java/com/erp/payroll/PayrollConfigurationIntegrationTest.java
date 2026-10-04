package com.erp.payroll;

import static com.erp.support.AuthTestSupport.unsafe;
import static com.erp.support.HrFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.support.AccountingFixtures;
import com.erp.support.HrFixtures;
import com.erp.support.IntegrationTest;
import com.erp.support.OrgFixtures;
import com.erp.support.PayrollFixtures;
import com.erp.support.PayrollFixtures.Payroll;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Payroll configuration and preparation (PRODUCT_SPEC.md §11.1): components and their validation,
 * structures, schedules and periods, compensations, inputs, working-day proration, run issues and the
 * pay-component scope of account mappings.
 */
class PayrollConfigurationIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PayrollFixtures payroll;

    @Autowired
    HrFixtures hr;

    @Autowired
    AccountingFixtures acc;

    private Payroll p;
    private LocalDate hired;

    @BeforeEach
    void setUp() throws Exception {
        p = payroll.setup();
        hired = LocalDate.of(p.year() - 1, 1, 1);
    }

    @Test
    void componentsAreValidatedAndMaintained() throws Exception {
        assertThat(JsonPath.<List<String>>read(hr.body(p.officer(), p.path("/statutory-rules")), "$.data[*].code"))
                .containsExactly("FLAT_PERCENT", "NONE");
        component("BAD1", "EARNING", "PERCENT_OF_GROSS", null).andExpect(status().isUnprocessableContent());
        component("BAD2", "DEDUCTION", "STATUTORY", "MISSING")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_STATUTORY_RULE"));
        component("BAD3", "DEDUCTION", "FIXED", "NONE").andExpect(status().isUnprocessableContent());
        component("BASIC", "EARNING", "FIXED", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));

        UUID tax = p.component("TAX");
        int version = JsonPath.read(hr.body(p.officer(), p.path("/pay-components/" + tax)), "$.version");
        mvc.perform(unsafe(patch(p.path("/pay-components/" + tax)))
                        .cookie(p.officer())
                        .header("If-Match", OrgFixtures.etag(version))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("name", "Income tax", "defaultRate", "12.5", "sequence", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Income tax"))
                .andExpect(jsonPath("$.defaultRate").value("12.500000"));
        mvc.perform(unsafe(patch(p.path("/pay-components/" + tax)))
                        .cookie(p.officer())
                        .header("If-Match", OrgFixtures.etag(version + 1))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("statutoryRuleCode", "MISSING")))
                .andExpect(status().isUnprocessableContent());
        assertThat(JsonPath.<List<?>>read(
                        hr.body(p.officer(), p.path("/pay-components?filter[kind]=DEDUCTION")), "$.data"))
                .hasSize(2);

        // Structures: listed with their components, replaced as a whole, validated.
        assertThat(JsonPath.<List<String>>read(
                        hr.body(p.officer(), p.path("/salary-structures")), "$.data[0].components[*].componentCode"))
                .hasSize(6);
        int structureVersion =
                JsonPath.read(hr.body(p.officer(), p.path("/salary-structures/" + p.structure())), "$.version");
        structure(structureVersion, List.of(p.component("BASIC"), p.component("BASIC")))
                .andExpect(status().isUnprocessableContent());
        structure(structureVersion, List.of(UUID.randomUUID())).andExpect(status().isUnprocessableContent());
        structure(structureVersion, List.of(p.component("BASIC"), p.component("TAX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.length()").value(2));
    }

    @Test
    void schedulesAndPeriodsFollowTheCompanyCurrencyAndCalendar() throws Exception {
        hr.post(
                        p.officer(),
                        p.path("/pay-schedules"),
                        OrgFixtures.map("code", "EUR", "name", "Euro", "frequency", "MONTHLY", "currencyCode", "EUR"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CURRENCY_NOT_SUPPORTED"));
        hr.post(
                        p.officer(),
                        p.path("/pay-schedules"),
                        OrgFixtures.map("code", "WEEK", "name", "Weekly", "frequency", "WEEKLY", "currencyCode", "USD"))
                .andExpect(status().isUnprocessableContent());
        UUID weekly = hr.create(
                p.officer(),
                p.path("/pay-schedules"),
                OrgFixtures.map(
                        "code",
                        "WEEK",
                        "name",
                        "Weekly",
                        "frequency",
                        "WEEKLY",
                        "currencyCode",
                        "USD",
                        "anchorDate",
                        LocalDate.of(p.year(), 1, 4),
                        "payDayOffset",
                        2));
        String first = HrFixtures.expect(
                        hr.post(
                                p.officer(),
                                p.path("/pay-schedules/" + weekly + "/periods"),
                                OrgFixtures.map("year", p.year())),
                        200)
                .getResponse()
                .getContentAsString();
        String again = HrFixtures.expect(
                        hr.post(
                                p.officer(),
                                p.path("/pay-schedules/" + weekly + "/periods"),
                                OrgFixtures.map("year", p.year())),
                        200)
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<?>>read(again, "$.data")).hasSameSizeAs(JsonPath.<List<?>>read(first, "$.data"));
        mvc.perform(unsafe(patch(p.path("/pay-schedules/" + weekly)))
                        .cookie(p.officer())
                        .header("If-Match", OrgFixtures.etag(0))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("name", "Weekly staff", "isActive", false)))
                .andExpect(status().isOk());
        hr.post(p.officer(), p.path("/pay-schedules/" + weekly + "/periods"), OrgFixtures.map("year", p.year()))
                .andExpect(status().isConflict());
        assertThat(JsonPath.<List<?>>read(hr.body(p.officer(), p.path("/pay-schedules")), "$.data"))
                .hasSize(2);

        // Working-day proration: a hire on the period's second Monday is paid its working days only.
        int version = JsonPath.read(hr.body(p.officer(), p.path("/settings/payroll")), "$.version");
        mvc.perform(unsafe(put(p.path("/settings/payroll")))
                        .cookie(p.officer())
                        .header("If-Match", OrgFixtures.etag(version))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json("prorationBasis", "WORKING_DAYS")))
                .andExpect(status().isOk());
        LocalDate secondMonday = HrFixtures.firstMonday(p.year(), 3).plusDays(7);
        UUID joiner = payroll.employee(p, "E100", secondMonday, "2000");
        UUID run = payroll.run(p, payroll.period(p, 3), "REGULAR");
        payroll.calculate(p, run);
        String slip = payroll.payslip(p, run, joiner);
        int paid = JsonPath.read(slip, "$.payslip.daysPaid");
        int periodDays = JsonPath.read(slip, "$.payslip.daysInPeriod");
        assertThat(periodDays).isBetween(21, 23);
        assertThat(paid).isLessThan(periodDays).isGreaterThan(10);
    }

    @Test
    void compensationsAreEffectiveDatedAndValidated() throws Exception {
        UUID ann = hr.employee(p.hr(), "E200", hired, null);
        String path = p.path("/employees/" + ann + "/compensations");
        compensation(
                        path,
                        Map.of(
                                "payScheduleId",
                                p.schedule(),
                                "salaryStructureId",
                                p.structure(),
                                "baseAmount",
                                "100",
                                "effectiveFrom",
                                hired.minusDays(1).toString()))
                .andExpect(status().isUnprocessableContent());
        compensation(
                        path,
                        Map.of(
                                "payScheduleId",
                                UUID.randomUUID(),
                                "salaryStructureId",
                                p.structure(),
                                "baseAmount",
                                "100",
                                "effectiveFrom",
                                hired.toString()))
                .andExpect(status().isUnprocessableContent());
        UUID outside =
                payroll.component(p.officer(), p.path(""), "SPECIAL", "EARNING", "FIXED", null, "1", true, null, 99);
        compensation(
                        path,
                        Map.of(
                                "payScheduleId",
                                p.schedule(),
                                "salaryStructureId",
                                p.structure(),
                                "baseAmount",
                                "100",
                                "effectiveFrom",
                                hired.toString(),
                                "overrides",
                                List.of(Map.of("componentId", outside, "amount", "5"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_IN_STRUCTURE"));
        compensation(
                        path,
                        Map.of(
                                "payScheduleId",
                                p.schedule(),
                                "salaryStructureId",
                                p.structure(),
                                "baseAmount",
                                "100.001",
                                "effectiveFrom",
                                hired.toString()))
                .andExpect(status().isUnprocessableContent());

        String created = HrFixtures.expect(
                        compensation(
                                path,
                                Map.of(
                                        "payScheduleId",
                                        p.schedule(),
                                        "salaryStructureId",
                                        p.structure(),
                                        "baseAmount",
                                        "3000",
                                        "effectiveFrom",
                                        hired.toString(),
                                        "overrides",
                                        List.of(Map.of("componentId", p.component("HOUSING"), "amount", "900")))),
                        201)
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(created, "$.id");
        // A future raise ends the current compensation the day before; the future one can be deleted.
        LocalDate raise = p.hr().today().plusMonths(2);
        String future = HrFixtures.expect(
                        compensation(
                                path,
                                Map.of(
                                        "payScheduleId",
                                        p.schedule(),
                                        "salaryStructureId",
                                        p.structure(),
                                        "baseAmount",
                                        "3300",
                                        "effectiveFrom",
                                        raise.toString())),
                        201)
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(hr.body(p.officer(), path), "$.data[*].effectiveTo"))
                .containsExactly(raise.minusDays(1).toString(), null);
        String futureId = JsonPath.read(future, "$.id");
        mvc.perform(unsafe(delete(path + "/" + futureId)).cookie(p.officer()).header("If-Match", OrgFixtures.etag(0)))
                .andExpect(status().isNoContent());
        mvc.perform(unsafe(delete(path + "/" + id)).cookie(p.officer()).header("If-Match", OrgFixtures.etag(1)))
                .andExpect(status().isConflict());
        mvc.perform(unsafe(patch(path + "/" + id))
                        .cookie(p.officer())
                        .header("If-Match", OrgFixtures.etag(1))
                        .contentType(OrgFixtures.MERGE_PATCH)
                        .content(OrgFixtures.json("effectiveTo", OrgFixtures.NULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());
        // The override pays 900 housing.
        UUID run = payroll.run(p, payroll.period(p, 1), "REGULAR");
        payroll.calculate(p, run);
        assertThat(JsonPath.<List<String>>read(
                        payroll.payslip(p, run, ann), "$.lines[?(@.componentCode=='HOUSING')].amount"))
                .containsExactly("900.0000");
    }

    @Test
    void inputsAreValidatedEditedAndFrozenWithTheirRun() throws Exception {
        UUID ann = payroll.employee(p, "E300", hired, "3000");
        UUID january = payroll.period(p, 1);
        String inputs = p.path("/payroll-periods/" + january + "/inputs");
        hr.post(
                        p.officer(),
                        inputs,
                        OrgFixtures.map("employeeId", ann, "componentId", p.component("BASIC"), "amount", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_AN_INPUT"));
        hr.post(
                        p.officer(),
                        inputs,
                        OrgFixtures.map(
                                "employeeId",
                                ann,
                                "componentId",
                                p.component("OVERTIME"),
                                "amount",
                                "1",
                                "quantity",
                                "2"))
                .andExpect(status().isUnprocessableContent());
        UUID input = hr.create(
                p.officer(),
                inputs,
                OrgFixtures.map("employeeId", ann, "componentId", p.component("OVERTIME"), "quantity", "5"));
        mvc.perform(unsafe(put(inputs + "/" + input))
                        .cookie(p.officer())
                        .header("If-Match", OrgFixtures.etag(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "employeeId", ann, "componentId", p.component("OVERTIME"), "quantity", "8")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value("8.000000"));
        assertThat(JsonPath.<List<?>>read(hr.body(p.officer(), inputs), "$.data"))
                .hasSize(1);

        UUID run = payroll.run(p, january, "REGULAR");
        payroll.calculate(p, run);
        expect(payroll.act(p, p.approver(), run, "approve", null), 200);
        // Approved: inputs are frozen; unapproved, they change again and the run goes back to DRAFT.
        mvc.perform(unsafe(delete(inputs + "/" + input)).cookie(p.officer())).andExpect(status().isConflict());
        expect(payroll.act(p, p.approver(), run, "unapprove", null), 200);
        mvc.perform(unsafe(delete(inputs + "/" + input)).cookie(p.officer())).andExpect(status().isNoContent());
        assertThat((String) JsonPath.read(payroll.runBody(p, run), "$.run.status"))
                .isEqualTo("DRAFT");
        hr.post(
                        p.officer(),
                        p.path("/payroll-runs"),
                        OrgFixtures.map("payrollPeriodId", january, "runType", "FINAL_SETTLEMENT"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void runIssuesReportMissingAssignmentsAndCompensations() throws Exception {
        // An employee without an assignment: the compensation alone does not make a payslip.
        UUID loose = hr.create(
                p.hr().session(),
                p.path("/employees"),
                OrgFixtures.map("employeeNumber", "E400", "firstName", "Lo", "lastName", "Ose", "hireDate", hired));
        payroll.compensation(p, loose, "1000", hired);
        UUID january = payroll.period(p, 1);
        UUID run = payroll.run(p, january, "REGULAR");
        payroll.calculate(p, run);
        assertThat(JsonPath.<List<String>>read(payroll.runBody(p, run), "$.issues[*].code"))
                .containsExactly("NO_ASSIGNMENT");
        // Off-cycle inputs for someone without a compensation.
        UUID other = hr.employee(p.hr(), "E401", hired, null);
        UUID bonus =
                payroll.component(p.officer(), p.path(""), "BONUS", "EARNING", "INPUT", null, null, true, null, 50);
        UUID offCycle = payroll.run(p, january, "OFF_CYCLE");
        expect(
                hr.post(
                        p.officer(),
                        p.path("/payroll-periods/" + january + "/inputs"),
                        OrgFixtures.map("employeeId", other, "componentId", bonus, "amount", "100", "runId", offCycle)),
                201);
        payroll.calculate(p, offCycle);
        assertThat(JsonPath.<List<String>>read(payroll.runBody(p, offCycle), "$.issues[*].code"))
                .containsExactly("NO_COMPENSATION");
        assertThat(JsonPath.<List<?>>read(
                        hr.body(p.officer(), p.path("/payroll-runs?filter[runType]=OFF_CYCLE")), "$.data"))
                .hasSize(1);
    }

    @Test
    void payComponentsScopeAccountMappingsAndTotalsAreReported() throws Exception {
        UUID ann = payroll.employee(p, "E500", hired, "3000");
        // Housing is booked on its own expense account.
        var books = p.books();
        UUID housing = acc.account(books, "6110", "EXPENSE", "PAYROLL_EXPENSE");
        String etag = mvc.perform(get(books.path("/account-mappings")).cookie(books.session()))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        mvc.perform(unsafe(put(books.path("/account-mappings")))
                        .cookie(books.session())
                        .header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "mappings",
                                List.of(OrgFixtures.map(
                                        "mappingKey",
                                        "SALARY_EXPENSE",
                                        "scopeType",
                                        "PAY_COMPONENT",
                                        "scopeId",
                                        p.component("HOUSING"),
                                        "accountId",
                                        housing)))))
                .andExpect(status().isOk());
        String etag2 = mvc.perform(get(books.path("/account-mappings")).cookie(books.session()))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        mvc.perform(unsafe(put(books.path("/account-mappings")))
                        .cookie(books.session())
                        .header("If-Match", etag2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OrgFixtures.json(
                                "mappings",
                                List.of(OrgFixtures.map(
                                        "mappingKey",
                                        "SALARY_EXPENSE",
                                        "scopeType",
                                        "PAY_COMPONENT",
                                        "scopeId",
                                        UUID.randomUUID(),
                                        "accountId",
                                        housing)))))
                .andExpect(status().isUnprocessableContent());

        UUID run = payroll.run(p, payroll.period(p, 1), "REGULAR");
        payroll.post(p, run);
        assertThat(acc.lines(books, "payroll", "PAYROLL_RUN", run))
                .isEqualTo(AccountingFixtures.amounts(
                        "6100", "3000", "6110", "500", "6150", "280", "2210", "-475", "2220", "-280", "2200", "-3025"));
        String totals = hr.body(
                p.approver(),
                p.path("/payroll-reports/component-totals?from=" + LocalDate.of(p.year(), 1, 1) + "&to="
                        + LocalDate.of(p.year(), 12, 31)));
        assertThat(JsonPath.<List<String>>read(totals, "$.components[?(@.componentCode=='BASIC')].amount"))
                .containsExactly("3000.0000");
        String summary = hr.body(p.approver(), p.path("/payroll-runs/" + run + "/summary"));
        assertThat((Integer) JsonPath.read(summary, "$.departments[0].employees"))
                .isEqualTo(1);
        assertThat(ann).isNotNull();
    }

    // ------------------------------------------------------------------------------ helpers

    private ResultActions component(String code, String kind, String calculation, String rule) throws Exception {
        return hr.post(
                p.officer(),
                p.path("/pay-components"),
                OrgFixtures.map(
                        "code",
                        code,
                        "name",
                        code,
                        "kind",
                        kind,
                        "calculation",
                        calculation,
                        "statutoryRuleCode",
                        rule,
                        "defaultRate",
                        "1",
                        "sequence",
                        1));
    }

    private ResultActions structure(int version, List<UUID> components) throws Exception {
        return mvc.perform(unsafe(put(p.path("/salary-structures/" + p.structure())))
                .cookie(p.officer())
                .header("If-Match", OrgFixtures.etag(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(OrgFixtures.JSON_MAPPER.writeValueAsString(Map.of(
                        "code",
                        "STD",
                        "name",
                        "Standard",
                        "components",
                        components.stream()
                                .map(c -> Map.of("componentId", c.toString()))
                                .toList()))));
    }

    private ResultActions compensation(String path, Map<String, Object> body) throws Exception {
        return hr.post(p.officer(), path, body);
    }
}
