-- =====================================================================================
-- Phase 10: Sales reporting views (DATABASE.md §11, ADR-037, ADR-040).
-- Sales are measured by posted invoices net of credit notes (PRODUCT_SPEC.md §13): amounts and
-- quantities of credit notes are negative. Base amounts are the ones stored at posting, i.e. the
-- amounts Accounting booked.
-- =====================================================================================

CREATE VIEW sales.v_rpt_sales_lines WITH (security_invoker = true) AS
SELECT i.company_id,
       i.id                AS invoice_id,
       i.document_type,
       i.number,
       i.invoice_date,
       i.accounting_date,
       i.customer_id,
       i.sales_order_id,
       i.currency_code,
       l.id                AS invoice_line_id,
       l.variant_id,
       l.description,
       l.branch_id,
       l.department_id,
       CASE WHEN i.document_type = 'CREDIT_NOTE' THEN -1 ELSE 1 END AS sign,
       CASE WHEN i.document_type = 'CREDIT_NOTE' THEN -l.quantity_base ELSE l.quantity_base END AS quantity_base,
       CASE WHEN i.document_type = 'CREDIT_NOTE' THEN -l.net_amount ELSE l.net_amount END AS net_amount,
       CASE WHEN i.document_type = 'CREDIT_NOTE' THEN -l.net_amount_base ELSE l.net_amount_base END AS net_amount_base,
       CASE WHEN i.document_type = 'CREDIT_NOTE' THEN -l.tax_amount_base ELSE l.tax_amount_base END AS tax_amount_base
  FROM sales.invoices i
  JOIN sales.invoice_lines l ON l.company_id = i.company_id AND l.invoice_id = i.id
 WHERE i.status = 'POSTED';

-- Invoice and credit note headers in every status (the invoice status report).
CREATE VIEW sales.v_rpt_invoices WITH (security_invoker = true) AS
SELECT i.company_id,
       i.id                AS invoice_id,
       i.document_type,
       i.number,
       i.invoice_date,
       i.accounting_date,
       i.due_date,
       i.customer_id,
       i.sales_order_id,
       i.currency_code,
       i.status,
       i.total,
       i.total_base,
       i.posted_at,
       i.created_at
  FROM sales.invoices i;

-- Open order lines: confirmed orders not closed or cancelled, with what is left to deliver and
-- what was delivered but not invoiced.
CREATE VIEW sales.v_rpt_order_backlog WITH (security_invoker = true) AS
SELECT o.company_id,
       o.id                AS sales_order_id,
       o.number            AS order_number,
       o.order_date,
       o.requested_date,
       o.status,
       o.invoice_status,
       o.customer_id,
       o.branch_id,
       o.warehouse_id,
       o.currency_code,
       l.id                AS line_id,
       l.line_no,
       l.variant_id,
       l.description,
       l.is_stockable,
       l.quantity_base,
       l.delivered_quantity_base,
       l.returned_quantity_base,
       l.invoiced_quantity_base,
       CASE WHEN l.is_stockable THEN greatest(l.quantity_base - l.delivered_quantity_base, 0) ELSE 0 END
                           AS undelivered_quantity_base,
       CASE WHEN l.is_stockable
            THEN greatest(l.delivered_quantity_base - l.returned_quantity_base - l.invoiced_quantity_base, 0)
            ELSE 0 END     AS uninvoiced_delivered_quantity_base,
       CASE WHEN l.is_stockable AND l.quantity_base > 0
            THEN round(l.net_amount * greatest(l.quantity_base - l.delivered_quantity_base, 0) / l.quantity_base, 4)
            ELSE 0 END     AS undelivered_net_amount,
       l.net_amount
  FROM sales.sales_orders o
  JOIN sales.sales_order_lines l ON l.company_id = o.company_id AND l.sales_order_id = o.id
 WHERE o.status IN ('CONFIRMED', 'PARTIALLY_DELIVERED', 'DELIVERED');

CREATE INDEX ix_invoices__company_id_invoice_date_posted
  ON sales.invoices (company_id, invoice_date) WHERE status = 'POSTED';
CREATE INDEX ix_invoice_lines__company_id_invoice_id ON sales.invoice_lines (company_id, invoice_id);

REVOKE ALL ON sales.v_rpt_sales_lines, sales.v_rpt_invoices, sales.v_rpt_order_backlog FROM erp_app;
GRANT SELECT ON sales.v_rpt_sales_lines, sales.v_rpt_invoices, sales.v_rpt_order_backlog TO erp_reporting;
GRANT SELECT ON sales.invoices, sales.invoice_lines, sales.sales_orders, sales.sales_order_lines TO erp_reporting;
