-- =====================================================================================
-- Phase 10: Procurement reporting views (DATABASE.md §11, ADR-036, ADR-040).
-- =====================================================================================

-- Purchase orders (submitted or later) with their progress counters per line.
CREATE VIEW procurement.v_rpt_purchase_lines WITH (security_invoker = true) AS
SELECT o.company_id,
       o.id                 AS purchase_order_id,
       o.number             AS po_number,
       o.order_date,
       o.expected_date,
       o.status,
       o.billing_status,
       o.supplier_id,
       o.branch_id,
       o.warehouse_id,
       o.department_id,
       o.currency_code,
       l.id                 AS line_id,
       l.line_no,
       l.variant_id,
       l.description,
       l.is_stockable,
       l.quantity_base,
       l.received_quantity_base,
       l.returned_quantity_base,
       l.billed_quantity_base,
       l.net_amount,
       l.tax_amount,
       l.total_amount
  FROM procurement.purchase_orders o
  JOIN procurement.purchase_order_lines l ON l.company_id = o.company_id AND l.purchase_order_id = o.id
 WHERE o.status <> 'DRAFT';

CREATE VIEW procurement.v_rpt_purchase_orders WITH (security_invoker = true) AS
SELECT o.company_id,
       o.id              AS purchase_order_id,
       o.number          AS po_number,
       o.order_date,
       o.expected_date,
       o.status,
       o.billing_status,
       o.supplier_id,
       o.branch_id,
       o.warehouse_id,
       o.department_id,
       o.currency_code,
       o.subtotal,
       o.tax_total,
       o.total,
       o.approved_at
  FROM procurement.purchase_orders o
 WHERE o.status <> 'DRAFT';

-- Posted goods receipt lines. grni_value_base is the receipt value not yet cleared by bills or
-- returns: value − returned − (billed − credited), as ProcurementViews.GoodsReceiptLine.openValueBase.
CREATE VIEW procurement.v_rpt_receipt_lines WITH (security_invoker = true) AS
SELECT r.company_id,
       r.id                 AS goods_receipt_id,
       r.number             AS receipt_number,
       r.receipt_date,
       r.purchase_order_id,
       o.number             AS po_number,
       o.order_date,
       o.expected_date,
       r.supplier_id,
       r.branch_id,
       r.warehouse_id,
       l.id                 AS line_id,
       l.line_no,
       l.variant_id,
       l.quantity_base,
       l.value_base,
       l.billed_quantity_base,
       l.billed_value_base,
       l.returned_quantity_base,
       l.returned_value_base,
       l.credited_quantity_base,
       l.credited_value_base,
       l.quantity_base - l.returned_quantity_base - (l.billed_quantity_base - l.credited_quantity_base)
                            AS unbilled_quantity_base,
       l.value_base - l.returned_value_base - (l.billed_value_base - l.credited_value_base)
                            AS grni_value_base
  FROM procurement.goods_receipts r
  JOIN procurement.goods_receipt_lines l ON l.company_id = r.company_id AND l.goods_receipt_id = r.id
  JOIN procurement.purchase_orders o ON o.company_id = r.company_id AND o.id = r.purchase_order_id
 WHERE r.status = 'POSTED';

-- Posted supplier bills and debit notes (headers); amounts signed: debit notes negative.
CREATE VIEW procurement.v_rpt_supplier_bills WITH (security_invoker = true) AS
SELECT b.company_id,
       b.id                AS supplier_bill_id,
       b.document_type,
       b.number,
       b.supplier_invoice_number,
       b.supplier_id,
       b.purchase_order_id,
       b.bill_date,
       b.accounting_date,
       b.due_date,
       b.currency_code,
       b.exchange_rate,
       b.match_status,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -1 ELSE 1 END AS sign,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -b.total ELSE b.total END AS total,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -b.subtotal_base ELSE b.subtotal_base END AS subtotal_base,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -b.tax_total_base ELSE b.tax_total_base END AS tax_total_base,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -b.total_base ELSE b.total_base END AS total_base,
       b.posted_at
  FROM procurement.supplier_bills b
 WHERE b.status = 'POSTED';

-- Posted bill and debit note lines, signed (debit notes negative), base currency.
CREATE VIEW procurement.v_rpt_supplier_bill_lines WITH (security_invoker = true) AS
SELECT b.company_id,
       b.id                AS supplier_bill_id,
       b.document_type,
       b.number,
       b.supplier_id,
       b.bill_date,
       b.accounting_date,
       l.id                AS line_id,
       l.line_kind,
       l.variant_id,
       l.description,
       l.branch_id,
       l.department_id,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -l.quantity_base ELSE l.quantity_base END AS quantity_base,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -l.net_amount_base ELSE l.net_amount_base END AS net_amount_base,
       CASE WHEN b.document_type = 'DEBIT_NOTE' THEN -l.tax_amount_base ELSE l.tax_amount_base END AS tax_amount_base
  FROM procurement.supplier_bills b
  JOIN procurement.supplier_bill_lines l ON l.company_id = b.company_id AND l.supplier_bill_id = b.id
 WHERE b.status = 'POSTED';

CREATE INDEX ix_goods_receipts__company_id_receipt_date_posted
  ON procurement.goods_receipts (company_id, receipt_date) WHERE status = 'POSTED';
CREATE INDEX ix_supplier_bills__company_id_bill_date_posted
  ON procurement.supplier_bills (company_id, bill_date) WHERE status = 'POSTED';
CREATE INDEX ix_purchase_orders__company_id_order_date ON procurement.purchase_orders (company_id, order_date);

REVOKE ALL ON procurement.v_rpt_purchase_lines, procurement.v_rpt_purchase_orders, procurement.v_rpt_receipt_lines,
  procurement.v_rpt_supplier_bills, procurement.v_rpt_supplier_bill_lines FROM erp_app;
GRANT SELECT ON procurement.v_rpt_purchase_lines, procurement.v_rpt_purchase_orders, procurement.v_rpt_receipt_lines,
  procurement.v_rpt_supplier_bills, procurement.v_rpt_supplier_bill_lines TO erp_reporting;
GRANT SELECT ON procurement.purchase_orders, procurement.purchase_order_lines, procurement.goods_receipts,
  procurement.goods_receipt_lines, procurement.supplier_bills, procurement.supplier_bill_lines TO erp_reporting;
