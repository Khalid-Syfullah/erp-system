package com.erp.payroll;

import static com.erp.support.HrFixtures.expect;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.HrFixtures;
import com.erp.support.IntegrationTest;
import com.erp.support.PayrollFixtures;
import com.erp.support.PayrollFixtures.Payroll;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 9 exit criterion (DEVELOPMENT_PLAN.md): a payroll for 1,000 employees calculates in under
 * two minutes. The employees, assignments and compensations are inserted directly; the run is
 * calculated by the job as in production.
 */
class PayrollVolumeIntegrationTest extends IntegrationTest {

    private static final int EMPLOYEES = 1000;

    @Autowired
    PayrollFixtures payroll;

    @Autowired
    HrFixtures hr;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aThousandEmployeesCalculateWithinTwoMinutes() throws Exception {
        Payroll p = payroll.setup();
        UUID company = p.hr().company();
        LocalDate hired = LocalDate.of(p.year() - 2, 1, 1);
        CurrentContext.callWith(
                RequestContext.forRequest("seed-" + UUID.randomUUID()).withCompany(company),
                () -> tx.execute(s -> {
                    dsl.execute(
                            "INSERT INTO hr.employees (company_id, employee_number, first_name, last_name, hire_date, status)"
                                    + " SELECT ?, 'V' || lpad(g::text, 5, '0'), 'First', 'Last' || g, ?, 'ACTIVE'"
                                    + " FROM generate_series(1, ?) g",
                            company,
                            hired,
                            EMPLOYEES);
                    dsl.execute(
                            "INSERT INTO hr.employment_assignments (company_id, employee_id, branch_id, department_id,"
                                    + " effective_from) SELECT company_id, id, ?, ?, hire_date FROM hr.employees"
                                    + " WHERE company_id = ? AND employee_number LIKE 'V%'",
                            p.hr().branch(), p.hr().department(), company);
                    dsl.execute(
                            "INSERT INTO payroll.employee_compensations (company_id, employee_id, pay_schedule_id,"
                                    + " salary_structure_id, base_amount, currency_code, effective_from)"
                                    + " SELECT company_id, id, ?, ?, 2000 + right(employee_number, 3)::int, 'USD', hire_date"
                                    + " FROM hr.employees WHERE company_id = ? AND employee_number LIKE 'V%'",
                            p.schedule(), p.structure(), company);
                    return null;
                }));

        UUID run = payroll.run(p, payroll.period(p, 3), "REGULAR");
        expect(
                hr.action(
                        p.officer(),
                        p.path("/payroll-runs/" + run + "/calculate"),
                        payroll.version(p, run),
                        null,
                        null),
                202);
        long started = System.nanoTime();
        payroll.jobs().calculateQueued(company);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        String body = payroll.runBody(p, run);
        assertThat((String) JsonPath.read(body, "$.run.status")).isEqualTo("CALCULATED");
        assertThat((Integer) JsonPath.read(body, "$.run.employeeCount")).isEqualTo(EMPLOYEES);
        assertThat(JsonPath.<List<?>>read(body, "$.issues")).isEmpty();
        assertThat(took).isLessThan(Duration.ofMinutes(2));
        System.out.println("Payroll of " + EMPLOYEES + " employees calculated in " + took.toMillis() + " ms");
    }
}
