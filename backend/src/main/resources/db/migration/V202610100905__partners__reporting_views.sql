-- =====================================================================================
-- Phase 10: Partners reporting view (DATABASE.md §11, ADR-040). Names and roles only: no
-- contact details, tax numbers or bank accounts.
-- =====================================================================================

CREATE VIEW partners.v_rpt_partners WITH (security_invoker = true) AS
SELECT p.company_id,
       p.id               AS partner_id,
       p.code             AS partner_code,
       p.name             AS partner_name,
       p.partner_type,
       p.status,
       c.partner_id IS NOT NULL AS is_customer,
       s.partner_id IS NOT NULL AS is_supplier,
       c.customer_group_id,
       s.supplier_group_id
  FROM partners.partners p
  LEFT JOIN partners.customers c ON c.company_id = p.company_id AND c.partner_id = p.id
  LEFT JOIN partners.suppliers s ON s.company_id = p.company_id AND s.partner_id = p.id;

REVOKE ALL ON partners.v_rpt_partners FROM erp_app;
GRANT SELECT ON partners.v_rpt_partners TO erp_reporting;
GRANT SELECT (company_id, id, code, name, partner_type, status) ON partners.partners TO erp_reporting;
GRANT SELECT (company_id, partner_id, customer_group_id) ON partners.customers TO erp_reporting;
GRANT SELECT (company_id, partner_id, supplier_group_id) ON partners.suppliers TO erp_reporting;
