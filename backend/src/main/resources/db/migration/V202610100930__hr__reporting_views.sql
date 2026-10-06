-- =====================================================================================
-- Phase 10: HR reporting views (DATABASE.md §11, ADR-039, ADR-040). No personal data beyond
-- the employee number and name: no contact details, date of birth, national ID or bank accounts.
-- =====================================================================================

-- Employment assignments with the employee's dates and status. An employee counts on a date when
-- an assignment is in effect, the employee was hired on or before it and not terminated before it.
CREATE VIEW hr.v_rpt_headcount WITH (security_invoker = true) AS
SELECT a.company_id,
       a.id               AS assignment_id,
       a.employee_id,
       e.employee_number,
       e.first_name || ' ' || e.last_name AS employee_name,
       e.status           AS employee_status,
       e.hire_date,
       e.termination_date,
       a.branch_id,
       a.department_id,
       a.position_id,
       p.code             AS position_code,
       p.title            AS position_title,
       a.employment_type,
       a.fte,
       a.effective_from,
       a.effective_to
  FROM hr.employment_assignments a
  JOIN hr.employees e ON e.company_id = a.company_id AND e.id = a.employee_id
  LEFT JOIN hr.positions p ON p.company_id = a.company_id AND p.id = a.position_id;

CREATE VIEW hr.v_rpt_employees WITH (security_invoker = true) AS
SELECT e.company_id,
       e.id               AS employee_id,
       e.employee_number,
       e.first_name || ' ' || e.last_name AS employee_name,
       e.status           AS employee_status,
       e.hire_date,
       e.termination_date
  FROM hr.employees e;

CREATE VIEW hr.v_rpt_attendance WITH (security_invoker = true) AS
SELECT r.company_id,
       r.id               AS attendance_record_id,
       r.employee_id,
       r.work_date,
       r.status,
       r.worked_minutes
  FROM hr.attendance_records r;

CREATE VIEW hr.v_rpt_leave WITH (security_invoker = true) AS
SELECT r.company_id,
       r.id               AS leave_request_id,
       r.employee_id,
       r.leave_type_id,
       t.code             AS leave_type_code,
       t.name             AS leave_type_name,
       t.is_paid,
       r.start_date,
       r.end_date,
       r.days,
       r.status
  FROM hr.leave_requests r
  JOIN hr.leave_types t ON t.company_id = r.company_id AND t.id = r.leave_type_id;

CREATE INDEX ix_leave_requests__company_id_start_date ON hr.leave_requests (company_id, start_date);

REVOKE ALL ON hr.v_rpt_headcount, hr.v_rpt_employees, hr.v_rpt_attendance, hr.v_rpt_leave FROM erp_app;
GRANT SELECT ON hr.v_rpt_headcount, hr.v_rpt_employees, hr.v_rpt_attendance, hr.v_rpt_leave TO erp_reporting;
GRANT SELECT (company_id, id, employee_number, first_name, last_name, status, hire_date, termination_date)
  ON hr.employees TO erp_reporting;
GRANT SELECT ON hr.employment_assignments, hr.positions, hr.leave_types TO erp_reporting;
GRANT SELECT (company_id, id, employee_id, work_date, status, worked_minutes) ON hr.attendance_records TO erp_reporting;
GRANT SELECT (company_id, id, employee_id, leave_type_id, start_date, end_date, days, status)
  ON hr.leave_requests TO erp_reporting;
