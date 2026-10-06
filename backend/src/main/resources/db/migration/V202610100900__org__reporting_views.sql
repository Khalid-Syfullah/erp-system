-- =====================================================================================
-- Phase 10: Org reporting views (DATABASE.md §11, ADR-040).
-- Reporting views are published contracts: security_invoker (RLS of the querying role applies),
-- readable only by erp_reporting. erp_app gets no privileges on them (the schema's default
-- privileges would otherwise grant DML on the new relations).
-- =====================================================================================

CREATE VIEW org.v_rpt_branches WITH (security_invoker = true) AS
SELECT b.company_id,
       b.id        AS branch_id,
       b.code      AS branch_code,
       b.name      AS branch_name,
       b.is_active
  FROM org.branches b;

CREATE VIEW org.v_rpt_departments WITH (security_invoker = true) AS
SELECT d.company_id,
       d.id        AS department_id,
       d.code      AS department_code,
       d.name      AS department_name,
       d.parent_id,
       d.branch_id,
       d.is_active
  FROM org.departments d;

REVOKE ALL ON org.v_rpt_branches, org.v_rpt_departments FROM erp_app;
GRANT SELECT ON org.v_rpt_branches, org.v_rpt_departments TO erp_reporting;
-- security_invoker views check the invoker's privileges on the underlying columns.
GRANT SELECT (company_id, id, code, name, is_active) ON org.branches TO erp_reporting;
GRANT SELECT (company_id, id, code, name, parent_id, branch_id, is_active) ON org.departments TO erp_reporting;
