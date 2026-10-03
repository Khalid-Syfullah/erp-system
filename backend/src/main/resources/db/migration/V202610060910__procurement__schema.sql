-- =====================================================================================
-- Procurement (Phase 6, ADR-036). Logical model: DATABASE.md §5.6; triggers §8.4; locks §9.
-- Procure-to-pay documents: requisitions, purchase orders, goods receipts, purchase returns and
-- supplier bills / debit notes. Stock changes only through Inventory's stock movements (referenced
-- by stock_movement_id). Documents leaving DRAFT are frozen by triggers: afterwards only the
-- fulfilment counters and the state columns listed per table may change.
-- =====================================================================================

SELECT platform.setup_module_schema('procurement');

-- ------------------------------------------------------------------------- generic guards
-- procurement.guard_frozen_document(<column>...): BEFORE UPDATE OR DELETE on a document header.
-- A DRAFT header changes freely and may be deleted; any other header may change only the listed
-- columns (plus updated_at, updated_by, version) and is never deleted.
CREATE FUNCTION procurement.guard_frozen_document()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, procurement
AS $$
DECLARE
  v_mutable text[] := TG_ARGV || ARRAY['updated_at', 'updated_by', 'version'];
BEGIN
  IF OLD.status = 'DRAFT' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - v_mutable) IS DISTINCT FROM (to_jsonb(OLD) - v_mutable) THEN
    RAISE EXCEPTION '% % is % and cannot be changed', TG_TABLE_NAME, OLD.id, OLD.status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '__frozen',
            TABLE = TG_TABLE_NAME, SCHEMA = 'procurement';
  END IF;
  RETURN NEW;
END;
$$;

-- procurement.guard_frozen_lines(<parent table>, <parent id column>, <column>...): BEFORE INSERT,
-- UPDATE OR DELETE on document lines. While the parent is a DRAFT, lines change freely; otherwise
-- no line is added or removed and only the listed columns may change. A missing parent means the
-- lines are being removed by the cascade of a draft delete.
CREATE FUNCTION procurement.guard_frozen_lines()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, procurement
AS $$
DECLARE
  v_row     jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  v_status  text;
  v_mutable text[] := TG_ARGV[2:] || ARRAY['updated_at', 'updated_by', 'version'];
BEGIN
  EXECUTE format('SELECT status FROM procurement.%I WHERE id = $1', TG_ARGV[0])
    INTO v_status USING (v_row ->> TG_ARGV[1])::uuid;
  IF v_status IS NULL OR v_status = 'DRAFT' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP <> 'UPDATE' OR (to_jsonb(NEW) - v_mutable) IS DISTINCT FROM (to_jsonb(OLD) - v_mutable) THEN
    RAISE EXCEPTION 'lines of % % (%) cannot be changed', TG_ARGV[0], v_row ->> TG_ARGV[1], v_status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '__frozen',
            TABLE = TG_TABLE_NAME, SCHEMA = 'procurement';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION procurement.guard_frozen_document(), procurement.guard_frozen_lines() FROM PUBLIC;

-- ------------------------------------------------------------------------------- settings
CREATE TABLE procurement.settings (
  company_id                    uuid          NOT NULL,
  po_approval_threshold_base    numeric(19,4) NULL,
  price_match_tolerance_percent numeric(7,4)  NOT NULL DEFAULT 0,
  qty_match_tolerance_percent   numeric(7,4)  NOT NULL DEFAULT 0,
  require_receipt_before_bill   boolean       NOT NULL DEFAULT true,
  created_at                    timestamptz   NOT NULL DEFAULT now(),
  created_by                    uuid          NULL,
  updated_at                    timestamptz   NOT NULL DEFAULT now(),
  updated_by                    uuid          NULL,
  version                       integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_settings__po_approval_threshold
    CHECK (po_approval_threshold_base IS NULL OR po_approval_threshold_base >= 0),
  CONSTRAINT ck_settings__price_tolerance CHECK (price_match_tolerance_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_settings__qty_tolerance CHECK (qty_match_tolerance_percent BETWEEN 0 AND 100),
  -- v1: stockable goods are billed only from receipts (GRNI clearing, ADR-036).
  CONSTRAINT ck_settings__receipt_before_bill CHECK (require_receipt_before_bill = true),
  CONSTRAINT ck_settings__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('procurement.settings');

-- -------------------------------------------------------------------------- requisitions
CREATE TABLE procurement.purchase_requisitions (
  id               uuid        NOT NULL DEFAULT uuidv7(),
  company_id       uuid        NOT NULL,
  number           text        NULL,
  branch_id        uuid        NOT NULL,
  department_id    uuid        NULL,
  requested_by     uuid        NOT NULL,
  needed_by        date        NULL,
  status           text        NOT NULL DEFAULT 'DRAFT',
  submitted_by     uuid        NULL,
  submitted_at     timestamptz NULL,
  approved_by      uuid        NULL,
  approved_at      timestamptz NULL,
  rejection_reason text        NULL,
  cancel_reason    text        NULL,
  notes            text        NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid        NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid        NULL,
  version          integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_purchase_requisitions PRIMARY KEY (id),
  CONSTRAINT uq_purchase_requisitions__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_purchase_requisitions__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_purchase_requisitions__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_purchase_requisitions__branches
    FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_purchase_requisitions__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT ck_purchase_requisitions__status CHECK (status IN
    ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PARTIALLY_ORDERED', 'ORDERED', 'CANCELLED')),
  -- Numbered on submit (G-6); a draft cancelled before submission keeps no number.
  CONSTRAINT ck_purchase_requisitions__number
    CHECK (status IN ('DRAFT', 'CANCELLED') OR number IS NOT NULL),
  CONSTRAINT ck_purchase_requisitions__approved
    CHECK (status NOT IN ('APPROVED', 'PARTIALLY_ORDERED', 'ORDERED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT ck_purchase_requisitions__rejected CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL),
  CONSTRAINT ck_purchase_requisitions__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (rejection_reason IS NULL OR length(rejection_reason) <= 500)
    AND (cancel_reason IS NULL OR length(cancel_reason) <= 500)),
  CONSTRAINT ck_purchase_requisitions__version CHECK (version >= 0)
);
CREATE INDEX ix_purchase_requisitions__company_id_branch_id ON procurement.purchase_requisitions (company_id, branch_id);
CREATE INDEX ix_purchase_requisitions__company_id_department_id ON procurement.purchase_requisitions (company_id, department_id);
CREATE INDEX ix_purchase_requisitions__company_id_status ON procurement.purchase_requisitions (company_id, status);
SELECT platform.enable_company_rls('procurement.purchase_requisitions');
CREATE TRIGGER trg_purchase_requisitions_frozen
  BEFORE UPDATE OR DELETE ON procurement.purchase_requisitions
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_document(
    'status', 'approved_by', 'approved_at', 'rejection_reason', 'cancel_reason');

CREATE TABLE procurement.purchase_requisition_lines (
  id                    uuid          NOT NULL DEFAULT uuidv7(),
  company_id            uuid          NOT NULL,
  requisition_id        uuid          NOT NULL,
  line_no               integer       NOT NULL,
  variant_id            uuid          NOT NULL,
  description           text          NOT NULL,
  quantity              numeric(18,6) NOT NULL,
  uom_id                uuid          NOT NULL,
  quantity_base         numeric(18,6) NOT NULL,
  estimated_unit_price  numeric(19,6) NULL,
  suggested_supplier_id uuid          NULL,
  ordered_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  created_at            timestamptz   NOT NULL DEFAULT now(),
  created_by            uuid          NULL,
  updated_at            timestamptz   NOT NULL DEFAULT now(),
  updated_by            uuid          NULL,
  version               integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_purchase_requisition_lines PRIMARY KEY (id),
  CONSTRAINT uq_purchase_requisition_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_purchase_requisition_lines__requisition_id_line_no UNIQUE (requisition_id, line_no),
  CONSTRAINT fk_purchase_requisition_lines__purchase_requisitions
    FOREIGN KEY (company_id, requisition_id) REFERENCES procurement.purchase_requisitions (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_purchase_requisition_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_purchase_requisition_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_purchase_requisition_lines__suppliers
    FOREIGN KEY (company_id, suggested_supplier_id) REFERENCES partners.suppliers (company_id, partner_id),
  CONSTRAINT ck_purchase_requisition_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_purchase_requisition_lines__description CHECK (btrim(description) <> '' AND length(description) <= 300),
  CONSTRAINT ck_purchase_requisition_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_purchase_requisition_lines__estimated_price CHECK (estimated_unit_price IS NULL OR estimated_unit_price >= 0),
  -- Never order more than requested.
  CONSTRAINT ck_purchase_requisition_lines__ordered
    CHECK (ordered_quantity_base >= 0 AND ordered_quantity_base <= quantity_base),
  CONSTRAINT ck_purchase_requisition_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_purchase_requisition_lines__company_id_variant_id ON procurement.purchase_requisition_lines (company_id, variant_id);
CREATE INDEX ix_purchase_requisition_lines__uom_id ON procurement.purchase_requisition_lines (uom_id);
CREATE INDEX ix_purchase_requisition_lines__company_id_suggested_supplier_id
  ON procurement.purchase_requisition_lines (company_id, suggested_supplier_id);
SELECT platform.enable_company_rls('procurement.purchase_requisition_lines');
CREATE TRIGGER trg_purchase_requisition_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON procurement.purchase_requisition_lines
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_lines(
    'purchase_requisitions', 'requisition_id', 'ordered_quantity_base');

-- ------------------------------------------------------------------------ purchase orders
CREATE TABLE procurement.purchase_orders (
  id                 uuid          NOT NULL DEFAULT uuidv7(),
  company_id         uuid          NOT NULL,
  number             text          NULL,
  supplier_id        uuid          NOT NULL,
  branch_id          uuid          NOT NULL,
  warehouse_id       uuid          NOT NULL,
  department_id      uuid          NULL,
  order_date         date          NOT NULL,
  expected_date      date          NULL,
  currency_code      char(3)       NOT NULL,
  payment_terms_id   uuid          NULL,
  prices_include_tax boolean       NOT NULL DEFAULT false,
  status             text          NOT NULL DEFAULT 'DRAFT',
  billing_status     text          NOT NULL DEFAULT 'NOT_BILLED',
  subtotal           numeric(19,4) NOT NULL DEFAULT 0,
  tax_total          numeric(19,4) NOT NULL DEFAULT 0,
  total              numeric(19,4) NOT NULL DEFAULT 0,
  submitted_by       uuid          NULL,
  submitted_at       timestamptz   NULL,
  approved_by        uuid          NULL,
  approved_at        timestamptz   NULL,
  rejection_reason   text          NULL,
  cancel_reason      text          NULL,
  close_reason       text          NULL,
  notes              text          NULL,
  created_at         timestamptz   NOT NULL DEFAULT now(),
  created_by         uuid          NULL,
  updated_at         timestamptz   NOT NULL DEFAULT now(),
  updated_by         uuid          NULL,
  version            integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_purchase_orders PRIMARY KEY (id),
  CONSTRAINT uq_purchase_orders__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_purchase_orders__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_purchase_orders__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_purchase_orders__suppliers
    FOREIGN KEY (company_id, supplier_id) REFERENCES partners.suppliers (company_id, partner_id),
  CONSTRAINT fk_purchase_orders__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_purchase_orders__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_purchase_orders__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT fk_purchase_orders__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_purchase_orders__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT ck_purchase_orders__status CHECK (status IN
    ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
  CONSTRAINT ck_purchase_orders__billing_status CHECK (billing_status IN ('NOT_BILLED', 'PARTIALLY_BILLED', 'BILLED')),
  -- Numbered on submit; a PO rejected back to DRAFT keeps its number.
  CONSTRAINT ck_purchase_orders__number CHECK (status IN ('DRAFT', 'CANCELLED') OR number IS NOT NULL),
  CONSTRAINT ck_purchase_orders__approved CHECK (
    status NOT IN ('APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT ck_purchase_orders__totals CHECK (subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total),
  CONSTRAINT ck_purchase_orders__expected_date CHECK (expected_date IS NULL OR expected_date >= order_date),
  CONSTRAINT ck_purchase_orders__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (rejection_reason IS NULL OR length(rejection_reason) <= 500)
    AND (cancel_reason IS NULL OR length(cancel_reason) <= 500) AND (close_reason IS NULL OR length(close_reason) <= 500)),
  CONSTRAINT ck_purchase_orders__version CHECK (version >= 0)
);
CREATE INDEX ix_purchase_orders__company_id_supplier_id_order_date
  ON procurement.purchase_orders (company_id, supplier_id, order_date DESC);
CREATE INDEX ix_purchase_orders__company_id_status ON procurement.purchase_orders (company_id, status);
CREATE INDEX ix_purchase_orders__company_id_branch_id ON procurement.purchase_orders (company_id, branch_id);
CREATE INDEX ix_purchase_orders__company_id_warehouse_id ON procurement.purchase_orders (company_id, warehouse_id);
CREATE INDEX ix_purchase_orders__company_id_department_id ON procurement.purchase_orders (company_id, department_id);
CREATE INDEX ix_purchase_orders__currency_code ON procurement.purchase_orders (currency_code);
CREATE INDEX ix_purchase_orders__company_id_payment_terms_id ON procurement.purchase_orders (company_id, payment_terms_id);
SELECT platform.enable_company_rls('procurement.purchase_orders');
CREATE TRIGGER trg_purchase_orders_frozen
  BEFORE UPDATE OR DELETE ON procurement.purchase_orders
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_document(
    'status', 'billing_status', 'approved_by', 'approved_at', 'rejection_reason', 'cancel_reason', 'close_reason');

CREATE TABLE procurement.purchase_order_lines (
  id                     uuid          NOT NULL DEFAULT uuidv7(),
  company_id             uuid          NOT NULL,
  purchase_order_id      uuid          NOT NULL,
  line_no                integer       NOT NULL,
  variant_id             uuid          NOT NULL,
  description            text          NOT NULL,
  -- Snapshot of the product type (G-10): only stockable lines are received.
  is_stockable           boolean       NOT NULL,
  quantity               numeric(18,6) NOT NULL,
  uom_id                 uuid          NOT NULL,
  quantity_base          numeric(18,6) NOT NULL,
  unit_price             numeric(19,6) NOT NULL,
  discount_percent       numeric(7,4)  NOT NULL DEFAULT 0,
  tax_code_id            uuid          NULL,
  net_amount             numeric(19,4) NOT NULL,
  tax_amount             numeric(19,4) NOT NULL,
  total_amount           numeric(19,4) NOT NULL,
  received_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  returned_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  billed_quantity_base   numeric(18,6) NOT NULL DEFAULT 0,
  requisition_line_id    uuid          NULL,
  created_at             timestamptz   NOT NULL DEFAULT now(),
  created_by             uuid          NULL,
  updated_at             timestamptz   NOT NULL DEFAULT now(),
  updated_by             uuid          NULL,
  version                integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_purchase_order_lines PRIMARY KEY (id),
  CONSTRAINT uq_purchase_order_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_purchase_order_lines__purchase_order_id_line_no UNIQUE (purchase_order_id, line_no),
  CONSTRAINT fk_purchase_order_lines__purchase_orders
    FOREIGN KEY (company_id, purchase_order_id) REFERENCES procurement.purchase_orders (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_purchase_order_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_purchase_order_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_purchase_order_lines__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_purchase_order_lines__purchase_requisition_lines
    FOREIGN KEY (company_id, requisition_line_id) REFERENCES procurement.purchase_requisition_lines (company_id, id),
  CONSTRAINT ck_purchase_order_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_purchase_order_lines__description CHECK (btrim(description) <> '' AND length(description) <= 300),
  CONSTRAINT ck_purchase_order_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_purchase_order_lines__unit_price CHECK (unit_price >= 0),
  CONSTRAINT ck_purchase_order_lines__discount CHECK (discount_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_purchase_order_lines__amounts
    CHECK (net_amount >= 0 AND tax_amount >= 0 AND total_amount = net_amount + tax_amount),
  CONSTRAINT ck_purchase_order_lines__received
    CHECK (received_quantity_base >= 0 AND returned_quantity_base >= 0 AND returned_quantity_base <= received_quantity_base),
  CONSTRAINT ck_purchase_order_lines__not_stockable_unreceived
    CHECK (is_stockable OR received_quantity_base = 0),
  CONSTRAINT ck_purchase_order_lines__billed CHECK (billed_quantity_base >= 0),
  CONSTRAINT ck_purchase_order_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_purchase_order_lines__company_id_variant_id ON procurement.purchase_order_lines (company_id, variant_id);
CREATE INDEX ix_purchase_order_lines__uom_id ON procurement.purchase_order_lines (uom_id);
CREATE INDEX ix_purchase_order_lines__company_id_tax_code_id ON procurement.purchase_order_lines (company_id, tax_code_id);
CREATE INDEX ix_purchase_order_lines__company_id_requisition_line_id
  ON procurement.purchase_order_lines (company_id, requisition_line_id);
SELECT platform.enable_company_rls('procurement.purchase_order_lines');
CREATE TRIGGER trg_purchase_order_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON procurement.purchase_order_lines
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_lines(
    'purchase_orders', 'purchase_order_id', 'received_quantity_base', 'returned_quantity_base', 'billed_quantity_base');

-- ------------------------------------------------------------------------- goods receipts
CREATE TABLE procurement.goods_receipts (
  id                     uuid           NOT NULL DEFAULT uuidv7(),
  company_id             uuid           NOT NULL,
  number                 text           NULL,
  purchase_order_id      uuid           NOT NULL,
  supplier_id            uuid           NOT NULL,
  branch_id              uuid           NOT NULL,
  warehouse_id           uuid           NOT NULL,
  receipt_date           date           NOT NULL,
  status                 text           NOT NULL DEFAULT 'DRAFT',
  currency_code          char(3)        NOT NULL,
  -- PO currency → base currency at the receipt date (PRC-2), fixed at posting.
  exchange_rate          numeric(19,10) NULL,
  stock_movement_id      uuid           NULL,
  supplier_delivery_note text           NULL,
  notes                  text           NULL,
  posted_at              timestamptz    NULL,
  posted_by              uuid           NULL,
  created_at             timestamptz    NOT NULL DEFAULT now(),
  created_by             uuid           NULL,
  updated_at             timestamptz    NOT NULL DEFAULT now(),
  updated_by             uuid           NULL,
  version                integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_goods_receipts PRIMARY KEY (id),
  CONSTRAINT uq_goods_receipts__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_goods_receipts__company_id_number UNIQUE (company_id, number),
  -- A stock movement belongs to at most one receipt: no double inventory posting.
  CONSTRAINT uq_goods_receipts__stock_movement_id UNIQUE (stock_movement_id),
  CONSTRAINT fk_goods_receipts__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_goods_receipts__purchase_orders
    FOREIGN KEY (company_id, purchase_order_id) REFERENCES procurement.purchase_orders (company_id, id),
  CONSTRAINT fk_goods_receipts__suppliers
    FOREIGN KEY (company_id, supplier_id) REFERENCES partners.suppliers (company_id, partner_id),
  CONSTRAINT fk_goods_receipts__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_goods_receipts__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_goods_receipts__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_goods_receipts__stock_movements
    FOREIGN KEY (company_id, stock_movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT ck_goods_receipts__status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_goods_receipts__posted CHECK ((status = 'POSTED') = (
    number IS NOT NULL AND stock_movement_id IS NOT NULL AND exchange_rate IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_goods_receipts__exchange_rate CHECK (exchange_rate IS NULL OR exchange_rate > 0),
  CONSTRAINT ck_goods_receipts__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (supplier_delivery_note IS NULL OR length(supplier_delivery_note) <= 100)),
  CONSTRAINT ck_goods_receipts__version CHECK (version >= 0)
);
CREATE INDEX ix_goods_receipts__company_id_purchase_order_id ON procurement.goods_receipts (company_id, purchase_order_id);
CREATE INDEX ix_goods_receipts__company_id_supplier_id ON procurement.goods_receipts (company_id, supplier_id);
CREATE INDEX ix_goods_receipts__company_id_branch_id ON procurement.goods_receipts (company_id, branch_id);
CREATE INDEX ix_goods_receipts__company_id_warehouse_id ON procurement.goods_receipts (company_id, warehouse_id);
CREATE INDEX ix_goods_receipts__currency_code ON procurement.goods_receipts (currency_code);
SELECT platform.enable_company_rls('procurement.goods_receipts');
CREATE TRIGGER trg_goods_receipts_frozen
  BEFORE UPDATE OR DELETE ON procurement.goods_receipts
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_document();

CREATE TABLE procurement.goods_receipt_lines (
  id                     uuid          NOT NULL DEFAULT uuidv7(),
  company_id             uuid          NOT NULL,
  goods_receipt_id       uuid          NOT NULL,
  line_no                integer       NOT NULL,
  purchase_order_line_id uuid          NOT NULL,
  variant_id             uuid          NOT NULL,
  -- NULL in a draft: the warehouse's default stock location; set to the actual location at posting.
  location_id            uuid          NULL,
  quantity               numeric(18,6) NOT NULL,
  uom_id                 uuid          NOT NULL,
  quantity_base          numeric(18,6) NOT NULL,
  -- PO net unit price per base unit in the PO currency (PRC-2).
  unit_cost_doc          numeric(19,6) NOT NULL,
  -- Set at posting: unit cost in base currency and the value the inventory ledger recorded.
  unit_cost_base         numeric(19,6) NULL,
  value_base             numeric(19,4) NULL,
  -- Fulfilment counters, changed by bills, returns and debit notes after posting.
  billed_quantity_base   numeric(18,6) NOT NULL DEFAULT 0,
  billed_value_base      numeric(19,4) NOT NULL DEFAULT 0,
  returned_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  returned_value_base    numeric(19,4) NOT NULL DEFAULT 0,
  credited_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  credited_value_base    numeric(19,4) NOT NULL DEFAULT 0,
  created_at             timestamptz   NOT NULL DEFAULT now(),
  created_by             uuid          NULL,
  updated_at             timestamptz   NOT NULL DEFAULT now(),
  updated_by             uuid          NULL,
  version                integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_goods_receipt_lines PRIMARY KEY (id),
  CONSTRAINT uq_goods_receipt_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_goods_receipt_lines__goods_receipt_id_line_no UNIQUE (goods_receipt_id, line_no),
  CONSTRAINT fk_goods_receipt_lines__goods_receipts
    FOREIGN KEY (company_id, goods_receipt_id) REFERENCES procurement.goods_receipts (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_goods_receipt_lines__purchase_order_lines
    FOREIGN KEY (company_id, purchase_order_line_id) REFERENCES procurement.purchase_order_lines (company_id, id),
  CONSTRAINT fk_goods_receipt_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_goods_receipt_lines__locations
    FOREIGN KEY (company_id, location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT fk_goods_receipt_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT ck_goods_receipt_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_goods_receipt_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_goods_receipt_lines__costs CHECK (
    unit_cost_doc >= 0 AND (unit_cost_base IS NULL OR unit_cost_base >= 0) AND (value_base IS NULL OR value_base >= 0)
    AND ((unit_cost_base IS NULL) = (value_base IS NULL))),
  -- Billing may exceed the receipt within the quantity tolerance (PRC-3). Returns of billed goods
  -- are credited by debit notes, never more than was returned and billed.
  CONSTRAINT ck_goods_receipt_lines__counters CHECK (
    billed_quantity_base >= 0 AND billed_value_base >= 0
    AND returned_quantity_base >= 0 AND returned_quantity_base <= quantity_base AND returned_value_base >= 0
    AND credited_quantity_base >= 0 AND credited_quantity_base <= returned_quantity_base
    AND credited_quantity_base <= billed_quantity_base
    AND credited_value_base >= 0 AND credited_value_base <= billed_value_base),
  CONSTRAINT ck_goods_receipt_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_goods_receipt_lines__company_id_purchase_order_line_id
  ON procurement.goods_receipt_lines (company_id, purchase_order_line_id);
CREATE INDEX ix_goods_receipt_lines__company_id_variant_id ON procurement.goods_receipt_lines (company_id, variant_id);
CREATE INDEX ix_goods_receipt_lines__company_id_location_id ON procurement.goods_receipt_lines (company_id, location_id);
CREATE INDEX ix_goods_receipt_lines__uom_id ON procurement.goods_receipt_lines (uom_id);
SELECT platform.enable_company_rls('procurement.goods_receipt_lines');
CREATE TRIGGER trg_goods_receipt_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON procurement.goods_receipt_lines
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_lines(
    'goods_receipts', 'goods_receipt_id', 'billed_quantity_base', 'billed_value_base',
    'returned_quantity_base', 'returned_value_base', 'credited_quantity_base', 'credited_value_base');

-- ----------------------------------------------------------------------- purchase returns
CREATE TABLE procurement.purchase_returns (
  id                uuid        NOT NULL DEFAULT uuidv7(),
  company_id        uuid        NOT NULL,
  number            text        NULL,
  supplier_id       uuid        NOT NULL,
  purchase_order_id uuid        NOT NULL,
  goods_receipt_id  uuid        NOT NULL,
  branch_id         uuid        NOT NULL,
  warehouse_id      uuid        NOT NULL,
  return_date       date        NOT NULL,
  status            text        NOT NULL DEFAULT 'DRAFT',
  reason            text        NOT NULL,
  stock_movement_id uuid        NULL,
  posted_at         timestamptz NULL,
  posted_by         uuid        NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid        NULL,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid        NULL,
  version           integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_purchase_returns PRIMARY KEY (id),
  CONSTRAINT uq_purchase_returns__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_purchase_returns__company_id_number UNIQUE (company_id, number),
  CONSTRAINT uq_purchase_returns__stock_movement_id UNIQUE (stock_movement_id),
  CONSTRAINT fk_purchase_returns__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_purchase_returns__suppliers
    FOREIGN KEY (company_id, supplier_id) REFERENCES partners.suppliers (company_id, partner_id),
  CONSTRAINT fk_purchase_returns__purchase_orders
    FOREIGN KEY (company_id, purchase_order_id) REFERENCES procurement.purchase_orders (company_id, id),
  CONSTRAINT fk_purchase_returns__goods_receipts
    FOREIGN KEY (company_id, goods_receipt_id) REFERENCES procurement.goods_receipts (company_id, id),
  CONSTRAINT fk_purchase_returns__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_purchase_returns__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_purchase_returns__stock_movements
    FOREIGN KEY (company_id, stock_movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT ck_purchase_returns__status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_purchase_returns__posted CHECK ((status = 'POSTED') = (
    number IS NOT NULL AND stock_movement_id IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_purchase_returns__reason CHECK (btrim(reason) <> '' AND length(reason) <= 500),
  CONSTRAINT ck_purchase_returns__version CHECK (version >= 0)
);
CREATE INDEX ix_purchase_returns__company_id_supplier_id ON procurement.purchase_returns (company_id, supplier_id);
CREATE INDEX ix_purchase_returns__company_id_purchase_order_id ON procurement.purchase_returns (company_id, purchase_order_id);
CREATE INDEX ix_purchase_returns__company_id_goods_receipt_id ON procurement.purchase_returns (company_id, goods_receipt_id);
CREATE INDEX ix_purchase_returns__company_id_branch_id ON procurement.purchase_returns (company_id, branch_id);
CREATE INDEX ix_purchase_returns__company_id_warehouse_id ON procurement.purchase_returns (company_id, warehouse_id);
SELECT platform.enable_company_rls('procurement.purchase_returns');
CREATE TRIGGER trg_purchase_returns_frozen
  BEFORE UPDATE OR DELETE ON procurement.purchase_returns
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_document();

CREATE TABLE procurement.purchase_return_lines (
  id                    uuid          NOT NULL DEFAULT uuidv7(),
  company_id            uuid          NOT NULL,
  purchase_return_id    uuid          NOT NULL,
  line_no               integer       NOT NULL,
  goods_receipt_line_id uuid          NOT NULL,
  variant_id            uuid          NOT NULL,
  -- NULL in a draft: the receipt line's location; set to the actual location at posting.
  location_id           uuid          NULL,
  quantity              numeric(18,6) NOT NULL,
  uom_id                uuid          NOT NULL,
  quantity_base         numeric(18,6) NOT NULL,
  -- The receipt's unit cost; value at that cost (GRNI clearing, PRODUCT_SPEC.md §8.6), set at posting.
  unit_cost_base        numeric(19,6) NOT NULL,
  value_base            numeric(19,4) NULL,
  created_at            timestamptz   NOT NULL DEFAULT now(),
  created_by            uuid          NULL,
  updated_at            timestamptz   NOT NULL DEFAULT now(),
  updated_by            uuid          NULL,
  version               integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_purchase_return_lines PRIMARY KEY (id),
  CONSTRAINT uq_purchase_return_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_purchase_return_lines__purchase_return_id_line_no UNIQUE (purchase_return_id, line_no),
  CONSTRAINT fk_purchase_return_lines__purchase_returns
    FOREIGN KEY (company_id, purchase_return_id) REFERENCES procurement.purchase_returns (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_purchase_return_lines__goods_receipt_lines
    FOREIGN KEY (company_id, goods_receipt_line_id) REFERENCES procurement.goods_receipt_lines (company_id, id),
  CONSTRAINT fk_purchase_return_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_purchase_return_lines__locations
    FOREIGN KEY (company_id, location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT fk_purchase_return_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT ck_purchase_return_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_purchase_return_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_purchase_return_lines__costs CHECK (unit_cost_base >= 0 AND (value_base IS NULL OR value_base >= 0)),
  CONSTRAINT ck_purchase_return_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_purchase_return_lines__company_id_goods_receipt_line_id
  ON procurement.purchase_return_lines (company_id, goods_receipt_line_id);
CREATE INDEX ix_purchase_return_lines__company_id_variant_id ON procurement.purchase_return_lines (company_id, variant_id);
CREATE INDEX ix_purchase_return_lines__company_id_location_id ON procurement.purchase_return_lines (company_id, location_id);
CREATE INDEX ix_purchase_return_lines__uom_id ON procurement.purchase_return_lines (uom_id);
SELECT platform.enable_company_rls('procurement.purchase_return_lines');
CREATE TRIGGER trg_purchase_return_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON procurement.purchase_return_lines
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_lines('purchase_returns', 'purchase_return_id');

-- ------------------------------------------------------------- supplier bills / debit notes
CREATE TABLE procurement.supplier_bills (
  id                      uuid           NOT NULL DEFAULT uuidv7(),
  company_id              uuid           NOT NULL,
  document_type           text           NOT NULL,
  number                  text           NULL,
  supplier_invoice_number citext         NOT NULL,
  supplier_id             uuid           NOT NULL,
  purchase_order_id       uuid           NULL,
  original_bill_id        uuid           NULL,
  bill_date               date           NOT NULL,
  accounting_date         date           NOT NULL,
  due_date                date           NOT NULL,
  currency_code           char(3)        NOT NULL,
  exchange_rate           numeric(19,10) NOT NULL,
  prices_include_tax      boolean        NOT NULL DEFAULT false,
  payment_terms_id        uuid           NULL,
  status                  text           NOT NULL DEFAULT 'DRAFT',
  match_status            text           NOT NULL DEFAULT 'NOT_CHECKED',
  match_override_by       uuid           NULL,
  match_override_reason   text           NULL,
  subtotal                numeric(19,4)  NOT NULL DEFAULT 0,
  tax_total               numeric(19,4)  NOT NULL DEFAULT 0,
  total                   numeric(19,4)  NOT NULL DEFAULT 0,
  subtotal_base           numeric(19,4)  NOT NULL DEFAULT 0,
  tax_total_base          numeric(19,4)  NOT NULL DEFAULT 0,
  total_base              numeric(19,4)  NOT NULL DEFAULT 0,
  notes                   text           NULL,
  posted_at               timestamptz    NULL,
  posted_by               uuid           NULL,
  created_at              timestamptz    NOT NULL DEFAULT now(),
  created_by              uuid           NULL,
  updated_at              timestamptz    NOT NULL DEFAULT now(),
  updated_by              uuid           NULL,
  version                 integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_supplier_bills PRIMARY KEY (id),
  CONSTRAINT uq_supplier_bills__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_supplier_bills__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_supplier_bills__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_supplier_bills__suppliers
    FOREIGN KEY (company_id, supplier_id) REFERENCES partners.suppliers (company_id, partner_id),
  CONSTRAINT fk_supplier_bills__purchase_orders
    FOREIGN KEY (company_id, purchase_order_id) REFERENCES procurement.purchase_orders (company_id, id),
  CONSTRAINT fk_supplier_bills__supplier_bills
    FOREIGN KEY (company_id, original_bill_id) REFERENCES procurement.supplier_bills (company_id, id),
  CONSTRAINT fk_supplier_bills__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_supplier_bills__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT ck_supplier_bills__document_type CHECK (document_type IN ('BILL', 'DEBIT_NOTE')),
  CONSTRAINT ck_supplier_bills__original_bill
    CHECK ((document_type = 'DEBIT_NOTE') = (original_bill_id IS NOT NULL)),
  CONSTRAINT ck_supplier_bills__status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_supplier_bills__posted CHECK ((status = 'POSTED') = (number IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_supplier_bills__match_status
    CHECK (match_status IN ('NOT_CHECKED', 'MATCHED', 'EXCEPTION', 'OVERRIDDEN')),
  CONSTRAINT ck_supplier_bills__override
    CHECK ((match_status = 'OVERRIDDEN') = (match_override_by IS NOT NULL AND match_override_reason IS NOT NULL)),
  -- A posted bill passed the three-way match or was overridden (PRC-3).
  CONSTRAINT ck_supplier_bills__posted_matched CHECK (status <> 'POSTED' OR match_status IN ('MATCHED', 'OVERRIDDEN')),
  CONSTRAINT ck_supplier_bills__exchange_rate CHECK (exchange_rate > 0),
  CONSTRAINT ck_supplier_bills__totals CHECK (
    subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total
    AND subtotal_base >= 0 AND tax_total_base >= 0 AND total_base = subtotal_base + tax_total_base),
  CONSTRAINT ck_supplier_bills__dates CHECK (due_date >= bill_date),
  CONSTRAINT ck_supplier_bills__invoice_number
    CHECK (btrim(supplier_invoice_number) <> '' AND length(supplier_invoice_number) <= 50),
  CONSTRAINT ck_supplier_bills__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (match_override_reason IS NULL OR length(match_override_reason) <= 500)),
  CONSTRAINT ck_supplier_bills__version CHECK (version >= 0)
);
-- PRC-5: a supplier's invoice number is used once per document type (cancelled drafts excepted).
CREATE UNIQUE INDEX uq_supplier_bills__supplier_invoice_number
  ON procurement.supplier_bills (company_id, supplier_id, document_type, supplier_invoice_number)
  WHERE status <> 'CANCELLED';
CREATE INDEX ix_supplier_bills__company_id_supplier_id ON procurement.supplier_bills (company_id, supplier_id);
CREATE INDEX ix_supplier_bills__company_id_purchase_order_id ON procurement.supplier_bills (company_id, purchase_order_id);
CREATE INDEX ix_supplier_bills__company_id_original_bill_id ON procurement.supplier_bills (company_id, original_bill_id);
CREATE INDEX ix_supplier_bills__company_id_status ON procurement.supplier_bills (company_id, status);
CREATE INDEX ix_supplier_bills__currency_code ON procurement.supplier_bills (currency_code);
CREATE INDEX ix_supplier_bills__company_id_payment_terms_id ON procurement.supplier_bills (company_id, payment_terms_id);
SELECT platform.enable_company_rls('procurement.supplier_bills');
CREATE TRIGGER trg_supplier_bills_frozen
  BEFORE UPDATE OR DELETE ON procurement.supplier_bills
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_document();

CREATE TABLE procurement.supplier_bill_lines (
  id                     uuid          NOT NULL DEFAULT uuidv7(),
  company_id             uuid          NOT NULL,
  supplier_bill_id       uuid          NOT NULL,
  line_no                integer       NOT NULL,
  line_kind              text          NOT NULL,
  purchase_order_line_id uuid          NULL,
  goods_receipt_line_id  uuid          NULL,
  variant_id             uuid          NOT NULL,
  description            text          NOT NULL,
  quantity               numeric(18,6) NOT NULL,
  uom_id                 uuid          NOT NULL,
  quantity_base          numeric(18,6) NOT NULL,
  unit_price             numeric(19,6) NOT NULL,
  discount_percent       numeric(7,4)  NOT NULL DEFAULT 0,
  tax_code_id            uuid          NULL,
  net_amount             numeric(19,4) NOT NULL,
  tax_amount             numeric(19,4) NOT NULL,
  total_amount           numeric(19,4) NOT NULL,
  net_amount_base        numeric(19,4) NOT NULL,
  tax_amount_base        numeric(19,4) NOT NULL,
  -- RECEIVED_STOCK: the receipt value being invoiced (GRNI clearing), set when the bill is posted.
  receipt_value_base     numeric(19,4) NULL,
  branch_id              uuid          NULL,
  department_id          uuid          NULL,
  created_at             timestamptz   NOT NULL DEFAULT now(),
  created_by             uuid          NULL,
  updated_at             timestamptz   NOT NULL DEFAULT now(),
  updated_by             uuid          NULL,
  version                integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_supplier_bill_lines PRIMARY KEY (id),
  CONSTRAINT uq_supplier_bill_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_supplier_bill_lines__supplier_bill_id_line_no UNIQUE (supplier_bill_id, line_no),
  CONSTRAINT fk_supplier_bill_lines__supplier_bills
    FOREIGN KEY (company_id, supplier_bill_id) REFERENCES procurement.supplier_bills (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_supplier_bill_lines__purchase_order_lines
    FOREIGN KEY (company_id, purchase_order_line_id) REFERENCES procurement.purchase_order_lines (company_id, id),
  CONSTRAINT fk_supplier_bill_lines__goods_receipt_lines
    FOREIGN KEY (company_id, goods_receipt_line_id) REFERENCES procurement.goods_receipt_lines (company_id, id),
  CONSTRAINT fk_supplier_bill_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_supplier_bill_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_supplier_bill_lines__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_supplier_bill_lines__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_supplier_bill_lines__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT ck_supplier_bill_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_supplier_bill_lines__line_kind CHECK (line_kind IN ('RECEIVED_STOCK', 'SERVICE', 'NON_STOCK_GOODS')),
  -- Received goods are billed against their receipt line (and so their PO line).
  CONSTRAINT ck_supplier_bill_lines__received_stock CHECK (
    (line_kind = 'RECEIVED_STOCK') = (goods_receipt_line_id IS NOT NULL)
    AND (goods_receipt_line_id IS NULL OR purchase_order_line_id IS NOT NULL)),
  CONSTRAINT ck_supplier_bill_lines__receipt_value
    CHECK (line_kind = 'RECEIVED_STOCK' OR receipt_value_base IS NULL),
  CONSTRAINT ck_supplier_bill_lines__description CHECK (btrim(description) <> '' AND length(description) <= 300),
  CONSTRAINT ck_supplier_bill_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_supplier_bill_lines__unit_price CHECK (unit_price >= 0),
  CONSTRAINT ck_supplier_bill_lines__discount CHECK (discount_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_supplier_bill_lines__amounts CHECK (
    net_amount >= 0 AND tax_amount >= 0 AND total_amount = net_amount + tax_amount
    AND net_amount_base >= 0 AND tax_amount_base >= 0 AND (receipt_value_base IS NULL OR receipt_value_base >= 0)),
  CONSTRAINT ck_supplier_bill_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_supplier_bill_lines__company_id_purchase_order_line_id
  ON procurement.supplier_bill_lines (company_id, purchase_order_line_id);
CREATE INDEX ix_supplier_bill_lines__company_id_goods_receipt_line_id
  ON procurement.supplier_bill_lines (company_id, goods_receipt_line_id);
CREATE INDEX ix_supplier_bill_lines__company_id_variant_id ON procurement.supplier_bill_lines (company_id, variant_id);
CREATE INDEX ix_supplier_bill_lines__uom_id ON procurement.supplier_bill_lines (uom_id);
CREATE INDEX ix_supplier_bill_lines__company_id_tax_code_id ON procurement.supplier_bill_lines (company_id, tax_code_id);
CREATE INDEX ix_supplier_bill_lines__company_id_branch_id ON procurement.supplier_bill_lines (company_id, branch_id);
CREATE INDEX ix_supplier_bill_lines__company_id_department_id ON procurement.supplier_bill_lines (company_id, department_id);
SELECT platform.enable_company_rls('procurement.supplier_bill_lines');
CREATE TRIGGER trg_supplier_bill_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON procurement.supplier_bill_lines
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_lines('supplier_bills', 'supplier_bill_id');

CREATE TABLE procurement.supplier_bill_taxes (
  supplier_bill_id    uuid          NOT NULL,
  tax_code_id         uuid          NOT NULL,
  company_id          uuid          NOT NULL,
  rate_percent        numeric(7,4)  NOT NULL,
  taxable_amount      numeric(19,4) NOT NULL,
  tax_amount          numeric(19,4) NOT NULL,
  taxable_amount_base numeric(19,4) NOT NULL,
  tax_amount_base     numeric(19,4) NOT NULL,
  CONSTRAINT pk_supplier_bill_taxes PRIMARY KEY (supplier_bill_id, tax_code_id),
  CONSTRAINT fk_supplier_bill_taxes__supplier_bills
    FOREIGN KEY (company_id, supplier_bill_id) REFERENCES procurement.supplier_bills (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_supplier_bill_taxes__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT ck_supplier_bill_taxes__amounts CHECK (
    rate_percent >= 0 AND taxable_amount >= 0 AND tax_amount >= 0 AND taxable_amount_base >= 0 AND tax_amount_base >= 0)
);
CREATE INDEX ix_supplier_bill_taxes__company_id_supplier_bill_id ON procurement.supplier_bill_taxes (company_id, supplier_bill_id);
CREATE INDEX ix_supplier_bill_taxes__company_id_tax_code_id ON procurement.supplier_bill_taxes (company_id, tax_code_id);
SELECT platform.enable_company_rls('procurement.supplier_bill_taxes');
CREATE TRIGGER trg_supplier_bill_taxes_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON procurement.supplier_bill_taxes
  FOR EACH ROW EXECUTE FUNCTION procurement.guard_frozen_lines('supplier_bills', 'supplier_bill_id');
