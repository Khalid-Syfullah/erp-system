-- =====================================================================================
-- Phase 10: Inventory reporting views (DATABASE.md §11, ADR-035, ADR-040).
-- Quantities are in the base unit. Values are base currency at the company-wide moving average
-- (ADR-016): the ledger (inventory_transactions) is the source of truth for as-of figures, the
-- item valuations for the current value (the nightly invariant check keeps both equal).
-- =====================================================================================

CREATE VIEW inventory.v_rpt_products WITH (security_invoker = true) AS
SELECT v.company_id,
       v.id              AS variant_id,
       v.sku,
       v.name            AS variant_name,
       v.status          AS variant_status,
       p.id              AS product_id,
       p.code            AS product_code,
       p.name            AS product_name,
       p.product_type,
       c.id              AS category_id,
       c.code            AS category_code,
       c.name            AS category_name,
       c.path::text      AS category_path,
       u.code            AS uom_code
  FROM inventory.product_variants v
  JOIN inventory.products p ON p.company_id = v.company_id AND p.id = v.product_id
  JOIN inventory.product_categories c ON c.company_id = p.company_id AND c.id = p.category_id
  JOIN inventory.uoms u ON u.id = p.base_uom_id;

CREATE VIEW inventory.v_rpt_warehouses WITH (security_invoker = true) AS
SELECT w.company_id,
       w.id        AS warehouse_id,
       w.code      AS warehouse_code,
       w.name      AS warehouse_name,
       w.branch_id,
       w.is_active
  FROM inventory.warehouses w;

-- Location grain: on hand per variant and location (reservations are kept per warehouse).
CREATE VIEW inventory.v_rpt_stock_on_hand WITH (security_invoker = true) AS
SELECT b.company_id,
       b.warehouse_id,
       w.branch_id,
       b.location_id,
       l.code            AS location_code,
       l.location_type,
       b.variant_id,
       pr.sku,
       pr.product_id,
       pr.product_name,
       pr.category_id,
       pr.category_code,
       pr.uom_code,
       b.on_hand
  FROM inventory.stock_balances b
  JOIN inventory.warehouses w ON w.company_id = b.company_id AND w.id = b.warehouse_id
  JOIN inventory.locations l ON l.company_id = b.company_id AND l.id = b.location_id
  JOIN inventory.v_rpt_products pr ON pr.company_id = b.company_id AND pr.variant_id = b.variant_id;

-- Warehouse grain: on hand, reserved and available.
CREATE VIEW inventory.v_rpt_warehouse_stock WITH (security_invoker = true) AS
SELECT s.company_id,
       s.warehouse_id,
       w.branch_id,
       s.variant_id,
       pr.sku,
       pr.product_id,
       pr.product_name,
       pr.category_id,
       pr.category_code,
       pr.uom_code,
       s.on_hand,
       s.reserved,
       s.on_hand - s.reserved AS available
  FROM inventory.warehouse_stock s
  JOIN inventory.warehouses w ON w.company_id = s.company_id AND w.id = s.warehouse_id
  JOIN inventory.v_rpt_products pr ON pr.company_id = s.company_id AND pr.variant_id = s.variant_id;

-- Current company-wide valuation per variant (moving average).
CREATE VIEW inventory.v_rpt_stock_valuation WITH (security_invoker = true) AS
SELECT iv.company_id,
       iv.variant_id,
       pr.sku,
       pr.product_id,
       pr.product_name,
       pr.category_id,
       pr.category_code,
       pr.category_name,
       pr.uom_code,
       iv.quantity_base   AS qty,
       iv.total_value_base,
       CASE WHEN iv.quantity_base <> 0 THEN round(iv.total_value_base / iv.quantity_base, 6) END AS avg_cost
  FROM inventory.item_valuations iv
  JOIN inventory.v_rpt_products pr ON pr.company_id = iv.company_id AND pr.variant_id = iv.variant_id;

-- The stock ledger. effective_movement_type is the original type for reversals, so a reversed
-- sales issue still counts as sales (cost of goods sold) and a reversed adjustment as adjustment.
-- Every join is a LEFT JOIN on a unique key (the composite FKs guarantee the row exists), so the
-- planner drops the joins a query does not use: aggregations over quantities and dates scan the
-- ledger alone.
CREATE VIEW inventory.v_rpt_stock_movements WITH (security_invoker = true) AS
SELECT t.company_id,
       t.id                AS transaction_id,
       t.transaction_date,
       t.movement_id,
       m.number            AS movement_number,
       t.movement_type,
       coalesce(o.movement_type, t.movement_type) AS effective_movement_type,
       m.reversal_of_id IS NOT NULL AS is_reversal,
       coalesce(m.reason_code_id, o.reason_code_id) AS reason_code_id,
       rc.code             AS reason_code,
       coalesce(m.source_module, o.source_module) AS source_module,
       coalesce(m.source_type, o.source_type) AS source_type,
       coalesce(m.source_id, o.source_id) AS source_id,
       coalesce(m.source_number, o.source_number) AS source_number,
       coalesce(m.partner_id, o.partner_id) AS partner_id,
       t.warehouse_id,
       w.branch_id,
       t.location_id,
       l.code              AS location_code,
       t.variant_id,
       v.sku,
       p.id                AS product_id,
       p.name              AS product_name,
       c.id                AS category_id,
       c.code              AS category_code,
       u.code              AS uom_code,
       t.quantity_base,
       t.unit_cost_base,
       t.value_base,
       t.created_at
  FROM inventory.inventory_transactions t
  LEFT JOIN inventory.stock_movements m ON m.company_id = t.company_id AND m.id = t.movement_id
  LEFT JOIN inventory.stock_movements o ON o.company_id = m.company_id AND o.id = m.reversal_of_id
  LEFT JOIN inventory.reason_codes rc
         ON rc.company_id = t.company_id AND rc.id = coalesce(m.reason_code_id, o.reason_code_id)
  LEFT JOIN inventory.warehouses w ON w.company_id = t.company_id AND w.id = t.warehouse_id
  LEFT JOIN inventory.locations l ON l.company_id = t.company_id AND l.id = t.location_id
  LEFT JOIN inventory.product_variants v ON v.company_id = t.company_id AND v.id = t.variant_id
  LEFT JOIN inventory.products p ON p.company_id = v.company_id AND p.id = v.product_id
  LEFT JOIN inventory.product_categories c ON c.company_id = p.company_id AND c.id = p.category_id
  LEFT JOIN inventory.uoms u ON u.id = p.base_uom_id;

-- Slow-moving analysis reads the last movement per variant and warehouse.
CREATE INDEX ix_inventory_transactions__company_id_warehouse_id_variant_id_date
  ON inventory.inventory_transactions (company_id, warehouse_id, variant_id, transaction_date)
  INCLUDE (quantity_base, movement_type);

REVOKE ALL ON inventory.v_rpt_products, inventory.v_rpt_warehouses, inventory.v_rpt_stock_on_hand,
  inventory.v_rpt_warehouse_stock, inventory.v_rpt_stock_valuation, inventory.v_rpt_stock_movements FROM erp_app;
GRANT SELECT ON inventory.v_rpt_products, inventory.v_rpt_warehouses, inventory.v_rpt_stock_on_hand,
  inventory.v_rpt_warehouse_stock, inventory.v_rpt_stock_valuation, inventory.v_rpt_stock_movements TO erp_reporting;
GRANT SELECT ON inventory.product_variants, inventory.products, inventory.product_categories, inventory.uoms,
  inventory.warehouses, inventory.locations, inventory.stock_balances, inventory.warehouse_stock,
  inventory.item_valuations, inventory.inventory_transactions, inventory.stock_movements,
  inventory.reason_codes TO erp_reporting;
