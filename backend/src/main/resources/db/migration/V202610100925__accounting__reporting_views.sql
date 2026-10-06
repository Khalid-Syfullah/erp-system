-- =====================================================================================
-- Phase 10: Accounting reporting views (DATABASE.md §11, ADR-038, ADR-040).
-- The posted general ledger is the source of truth of every financial figure. The financial
-- statements themselves are computed by Accounting's ReportService (FinancialReports API); these
-- views serve the reports and KPIs that slice the posted ledger and the open items.
-- =====================================================================================

CREATE VIEW accounting.v_rpt_accounts WITH (security_invoker = true) AS
SELECT a.company_id,
       a.id              AS account_id,
       a.code            AS account_code,
       a.name            AS account_name,
       a.account_type,
       a.account_subtype,
       a.is_postable,
       a.status
  FROM accounting.accounts a;

-- Posted journal lines with their account and entry. amount_base = debit − credit.
CREATE VIEW accounting.v_rpt_gl_lines WITH (security_invoker = true) AS
SELECT l.company_id,
       l.id               AS journal_line_id,
       l.journal_entry_id,
       e.number           AS entry_number,
       l.entry_date,
       l.period_id,
       e.journal_id,
       e.entry_type,
       e.source_module,
       e.source_type,
       e.source_id,
       e.source_number,
       l.account_id,
       a.code             AS account_code,
       a.name             AS account_name,
       a.account_type,
       a.account_subtype,
       l.debit,
       l.credit,
       l.debit - l.credit AS amount_base,
       l.currency_code,
       l.amount_currency,
       l.partner_id,
       l.branch_id,
       l.department_id,
       l.tax_code_id,
       l.description
  FROM accounting.journal_lines l
  JOIN accounting.journal_entries e ON e.company_id = l.company_id AND e.id = l.journal_entry_id
  JOIN accounting.accounts a ON a.company_id = l.company_id AND a.id = l.account_id
 WHERE l.is_posted;

-- Receivable and payable open items (current open amounts; ageing as of a past date is
-- Accounting's ReportService).
CREATE VIEW accounting.v_rpt_open_items WITH (security_invoker = true) AS
SELECT o.company_id,
       o.id               AS open_item_id,
       o.kind,
       o.partner_id,
       o.account_id,
       o.source_module,
       o.source_type,
       o.source_id,
       o.document_number,
       o.document_date,
       o.due_date,
       o.currency_code,
       o.original_amount,
       o.open_amount,
       o.original_amount_base,
       o.open_amount_base,
       o.status
  FROM accounting.open_items o;

-- Company bank accounts (no account numbers).
CREATE VIEW accounting.v_rpt_bank_accounts WITH (security_invoker = true) AS
SELECT b.company_id,
       b.id               AS bank_account_id,
       b.name             AS bank_account_name,
       b.account_id,
       b.currency_code,
       b.bank_name,
       b.is_active
  FROM accounting.bank_accounts b;

CREATE INDEX ix_journal_lines__company_id_entry_date_posted
  ON accounting.journal_lines (company_id, entry_date) WHERE is_posted;

REVOKE ALL ON accounting.v_rpt_accounts, accounting.v_rpt_gl_lines, accounting.v_rpt_open_items,
  accounting.v_rpt_bank_accounts FROM erp_app;
GRANT SELECT ON accounting.v_rpt_accounts, accounting.v_rpt_gl_lines, accounting.v_rpt_open_items,
  accounting.v_rpt_bank_accounts TO erp_reporting;
GRANT SELECT ON accounting.accounts, accounting.journal_lines, accounting.journal_entries,
  accounting.open_items TO erp_reporting;
GRANT SELECT (company_id, id, name, account_id, currency_code, bank_name, is_active)
  ON accounting.bank_accounts TO erp_reporting;
