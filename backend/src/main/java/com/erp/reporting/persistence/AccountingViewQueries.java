package com.erp.reporting.persistence;

import static com.erp.db.accounting.tables.VRptAccounts.V_RPT_ACCOUNTS;
import static com.erp.db.accounting.tables.VRptBankAccounts.V_RPT_BANK_ACCOUNTS;
import static com.erp.db.accounting.tables.VRptGlLines.V_RPT_GL_LINES;
import static com.erp.db.org.tables.VRptBranches.V_RPT_BRANCHES;
import static com.erp.db.org.tables.VRptDepartments.V_RPT_DEPARTMENTS;
import static com.erp.reporting.persistence.ViewQueries.eq;
import static com.erp.reporting.persistence.ViewQueries.inRange;
import static com.erp.reporting.persistence.ViewQueries.month;
import static com.erp.reporting.persistence.ViewQueries.sum;

import com.erp.reporting.application.ReportCatalog;
import com.erp.reporting.application.ReportScope;
import com.erp.reporting.domain.ReportParameters;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * The accounting reports that slice the posted general ledger ({@code accounting.v_rpt_gl_lines});
 * the financial statements come from Accounting itself (FinancialReports). The ledger is company-wide
 * (SECURITY.md §4.4: not branch-scoped).
 */
@Component
class AccountingViewQueries implements ViewQueries {

    @Override
    public Map<String, BiFunction<ReportParameters, ReportScope, Select<? extends Record>>> queries() {
        return Map.of("cash-position", this::cashPosition, "expenses", this::expenses);
    }

    private Select<? extends Record> cashPosition(ReportParameters p, ReportScope s) {
        var gl = V_RPT_GL_LINES;
        Table<?> balances = DSL.select(
                        gl.ACCOUNT_ID.as("account"),
                        sum(gl.AMOUNT_BASE).as("base"),
                        sum(gl.AMOUNT_CURRENCY).as("currency"))
                .from(gl)
                .where(gl.COMPANY_ID.eq(s.companyId()))
                .and(gl.ENTRY_DATE.le(p.requireDate(ReportCatalog.AS_OF)))
                .and(gl.ACCOUNT_ID.in(DSL.select(V_RPT_BANK_ACCOUNTS.ACCOUNT_ID)
                        .from(V_RPT_BANK_ACCOUNTS)
                        .where(V_RPT_BANK_ACCOUNTS.COMPANY_ID.eq(s.companyId()))))
                .groupBy(gl.ACCOUNT_ID)
                .asTable("g");
        var ba = V_RPT_BANK_ACCOUNTS;
        var a = V_RPT_ACCOUNTS;
        return DSL.select(
                        ba.BANK_ACCOUNT_ID.as("bankAccountId"),
                        ba.BANK_ACCOUNT_NAME.as("bankAccountName"),
                        a.ACCOUNT_CODE.as("accountCode"),
                        ba.CURRENCY_CODE.as("currencyCode"),
                        DSL.coalesce(balances.field("currency", BigDecimal.class), BigDecimal.ZERO)
                                .as("balanceCurrency"),
                        DSL.coalesce(balances.field("base", BigDecimal.class), BigDecimal.ZERO)
                                .as("balanceBase"))
                .from(ba)
                .join(a)
                .on(a.COMPANY_ID.eq(ba.COMPANY_ID))
                .and(a.ACCOUNT_ID.eq(ba.ACCOUNT_ID))
                .leftJoin(balances)
                .on(balances.field("account", UUID.class).eq(ba.ACCOUNT_ID))
                .where(ba.COMPANY_ID.eq(s.companyId()));
    }

    private Select<? extends Record> expenses(ReportParameters p, ReportScope s) {
        var gl = V_RPT_GL_LINES;
        var b = V_RPT_BRANCHES;
        var d = V_RPT_DEPARTMENTS;
        Field<String> code;
        Field<String> name;
        switch (p.choice(ReportCatalog.GROUP_BY)) {
            case "MONTH" -> {
                code = month(gl.ENTRY_DATE);
                name = month(gl.ENTRY_DATE);
            }
            case "BRANCH" -> {
                code = DSL.coalesce(b.BRANCH_CODE, DSL.inline(""));
                name = DSL.coalesce(b.BRANCH_NAME, DSL.inline("No branch"));
            }
            case "DEPARTMENT" -> {
                code = DSL.coalesce(d.DEPARTMENT_CODE, DSL.inline(""));
                name = DSL.coalesce(d.DEPARTMENT_NAME, DSL.inline("No department"));
            }
            default -> {
                code = gl.ACCOUNT_CODE;
                name = gl.ACCOUNT_NAME;
            }
        }
        return DSL.select(
                        code.as("groupCode"),
                        name.as("groupName"),
                        sum(gl.DEBIT).as("debitBase"),
                        sum(gl.CREDIT).as("creditBase"),
                        sum(gl.AMOUNT_BASE).as("netBase"))
                .from(gl)
                .leftJoin(b)
                .on(b.COMPANY_ID.eq(gl.COMPANY_ID))
                .and(b.BRANCH_ID.eq(gl.BRANCH_ID))
                .leftJoin(d)
                .on(d.COMPANY_ID.eq(gl.COMPANY_ID))
                .and(d.DEPARTMENT_ID.eq(gl.DEPARTMENT_ID))
                .where(gl.COMPANY_ID.eq(s.companyId()))
                .and(gl.ACCOUNT_TYPE.eq("EXPENSE"))
                // Year-end closing entries are not expenses (as in the income statement).
                .and(gl.ENTRY_TYPE.ne("CLOSING"))
                .and(inRange(gl.ENTRY_DATE, p))
                .and(eq(gl.ACCOUNT_ID, p.id("accountId")))
                .and(eq(gl.BRANCH_ID, p.id("branchId")))
                .and(eq(gl.DEPARTMENT_ID, p.id("departmentId")))
                .groupBy(code, name);
    }
}
