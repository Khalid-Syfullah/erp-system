-- =====================================================================================
-- Phase 10: Payroll reporting view (DATABASE.md §11, ADR-039, ADR-040).
-- Posted and paid runs, aggregated per run, branch, department and component: amounts and
-- employee counts, never per-employee figures.
-- =====================================================================================

CREATE VIEW payroll.v_rpt_payroll_summary WITH (security_invoker = true) AS
SELECT r.company_id,
       r.id                AS payroll_run_id,
       r.number            AS run_number,
       r.run_type,
       r.status            AS run_status,
       pp.start_date       AS period_start,
       pp.end_date         AS period_end,
       pp.pay_date,
       r.accounting_date,
       r.currency_code,
       s.branch_id,
       s.department_id,
       l.component_id,
       l.component_code,
       l.component_name,
       l.kind,
       count(DISTINCT s.id) AS employee_count,
       sum(l.amount)        AS amount
  FROM payroll.payroll_runs r
  JOIN payroll.payroll_periods pp ON pp.company_id = r.company_id AND pp.id = r.payroll_period_id
  JOIN payroll.payslips s ON s.company_id = r.company_id AND s.payroll_run_id = r.id
  JOIN payroll.payslip_lines l ON l.company_id = s.company_id AND l.payslip_id = s.id
 WHERE r.status IN ('POSTED', 'PAID')
 GROUP BY r.company_id, r.id, r.number, r.run_type, r.status, pp.start_date, pp.end_date, pp.pay_date,
          r.accounting_date, r.currency_code, s.branch_id, s.department_id, l.component_id, l.component_code,
          l.component_name, l.kind;

REVOKE ALL ON payroll.v_rpt_payroll_summary FROM erp_app;
GRANT SELECT ON payroll.v_rpt_payroll_summary TO erp_reporting;
GRANT SELECT (company_id, id, number, run_type, status, payroll_period_id, accounting_date, currency_code)
  ON payroll.payroll_runs TO erp_reporting;
GRANT SELECT (company_id, id, start_date, end_date, pay_date) ON payroll.payroll_periods TO erp_reporting;
GRANT SELECT (company_id, id, payroll_run_id, branch_id, department_id) ON payroll.payslips TO erp_reporting;
GRANT SELECT (company_id, payslip_id, component_id, component_code, component_name, kind, amount)
  ON payroll.payslip_lines TO erp_reporting;
