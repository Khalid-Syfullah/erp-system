-- =====================================================================================
-- The report catalogue (PRODUCT_SPEC.md §13, API.md §17.11, ADR-040). Mirrors
-- com.erp.reporting.application.ReportCatalog; ReportCatalogIntegrationTest verifies that both
-- agree and that every permission code is in the SECURITY.md §4.2 catalogue. Idempotent.
-- Definitions are never deleted (saved reports and export jobs reference them).
-- =====================================================================================

INSERT INTO reporting.report_definitions (code, name, owner_module, permission_codes) VALUES
  ('sales-summary', 'Sales summary', 'sales', '{reporting.sales.read}'),
  ('sales-by-customer', 'Sales by customer', 'sales', '{reporting.sales.read}'),
  ('sales-by-product', 'Sales by product', 'sales', '{reporting.sales.read}'),
  ('sales-by-branch', 'Sales by branch', 'sales', '{reporting.sales.read}'),
  ('sales-by-period', 'Sales by period', 'sales', '{reporting.sales.read}'),
  ('invoice-status', 'Invoice status', 'sales', '{reporting.sales.read}'),
  ('payment-status', 'Invoice payment status', 'sales', '{reporting.sales.read}'),
  ('gross-margin', 'Gross margin', 'sales', '{reporting.sales.read}'),
  ('order-backlog', 'Order backlog and uninvoiced deliveries', 'sales', '{reporting.sales.read}'),
  ('purchases', 'Purchases', 'procurement', '{reporting.procurement.read}'),
  ('supplier-analysis', 'Supplier analysis', 'procurement', '{reporting.procurement.read}'),
  ('purchase-orders', 'Purchase orders', 'procurement', '{reporting.procurement.read}'),
  ('receiving', 'Receiving', 'procurement', '{reporting.procurement.read}'),
  ('grni', 'Goods received not invoiced', 'procurement', '{reporting.procurement.read}'),
  ('outstanding-supplier-bills', 'Outstanding supplier bills', 'procurement', '{reporting.procurement.read}'),
  ('stock-on-hand', 'Stock on hand', 'inventory', '{reporting.inventory.read}'),
  ('stock-valuation', 'Stock valuation', 'inventory', '{inventory.valuation.read}'),
  ('stock-movements', 'Stock movement summary', 'inventory', '{reporting.inventory.read}'),
  ('slow-moving', 'Slow-moving and non-moving stock', 'inventory', '{reporting.inventory.read}'),
  ('warehouse-summary', 'Warehouse summary', 'inventory', '{reporting.inventory.read}'),
  ('inventory-adjustments', 'Inventory adjustments', 'inventory', '{reporting.inventory.read}'),
  ('inventory-transactions', 'Inventory transaction history', 'inventory', '{reporting.inventory.read}'),
  ('trial-balance', 'Trial balance', 'accounting', '{accounting.report.read}'),
  ('general-ledger', 'General ledger', 'accounting', '{accounting.report.read}'),
  ('profit-and-loss', 'Income statement', 'accounting', '{accounting.report.read}'),
  ('balance-sheet', 'Balance sheet', 'accounting', '{accounting.report.read}'),
  ('ar-ageing', 'Accounts receivable ageing', 'accounting', '{accounting.report.read,accounting.ar.read}'),
  ('ap-ageing', 'Accounts payable ageing', 'accounting', '{accounting.report.read,accounting.ap.read}'),
  ('cash-book', 'Cash book', 'accounting', '{accounting.report.read}'),
  ('cash-position', 'Cash and bank position', 'accounting', '{accounting.report.read}'),
  ('expenses', 'Expenses', 'accounting', '{accounting.report.read}'),
  ('headcount', 'Headcount', 'hr', '{reporting.hr.read}'),
  ('turnover', 'Hires, terminations and turnover', 'hr', '{reporting.hr.read}'),
  ('attendance', 'Attendance', 'hr', '{reporting.hr.read}'),
  ('leave', 'Leave taken', 'hr', '{reporting.hr.read}'),
  ('payroll-summary', 'Payroll summary', 'payroll', '{payroll.report.read}')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, owner_module = EXCLUDED.owner_module,
  permission_codes = EXCLUDED.permission_codes;
