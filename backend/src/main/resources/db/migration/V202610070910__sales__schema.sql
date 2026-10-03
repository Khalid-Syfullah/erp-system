-- =====================================================================================
-- Sales (Phase 7, ADR-037). Logical model: DATABASE.md §5.7; triggers §8.5; locks §9.
-- Order-to-cash documents: price lists, quotations, sales orders, deliveries, sales returns and
-- invoices / credit notes. Stock changes only through Inventory's stock movements; reservations
-- live in Inventory and are linked from the order lines. Documents leaving DRAFT are frozen by
-- triggers: afterwards only the fulfilment counters and the listed state columns may change.
-- Payments and AR open items belong to Accounting (Phase 8).
-- =====================================================================================

SELECT platform.setup_module_schema('sales');

-- ------------------------------------------------------------------------- generic guards
-- sales.guard_frozen_document(<column>...): BEFORE UPDATE OR DELETE on a document header. A DRAFT
-- header changes freely and may be deleted; any other header may change only the listed columns
-- (plus updated_at, updated_by, version) and is never deleted.
CREATE FUNCTION sales.guard_frozen_document()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, sales
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
            TABLE = TG_TABLE_NAME, SCHEMA = 'sales';
  END IF;
  RETURN NEW;
END;
$$;

-- sales.guard_frozen_lines(<parent table>, <parent id column>, <column>...): BEFORE INSERT, UPDATE
-- OR DELETE on document lines. While the parent is a DRAFT, lines change freely; otherwise no line
-- is added or removed and only the listed columns may change.
CREATE FUNCTION sales.guard_frozen_lines()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, sales
AS $$
DECLARE
  v_row     jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  v_status  text;
  v_mutable text[] := TG_ARGV[2:] || ARRAY['updated_at', 'updated_by', 'version'];
BEGIN
  EXECUTE format('SELECT status FROM sales.%I WHERE id = $1', TG_ARGV[0])
    INTO v_status USING (v_row ->> TG_ARGV[1])::uuid;
  IF v_status IS NULL OR v_status = 'DRAFT' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP <> 'UPDATE' OR (to_jsonb(NEW) - v_mutable) IS DISTINCT FROM (to_jsonb(OLD) - v_mutable) THEN
    RAISE EXCEPTION 'lines of % % (%) cannot be changed', TG_ARGV[0], v_row ->> TG_ARGV[1], v_status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '__frozen',
            TABLE = TG_TABLE_NAME, SCHEMA = 'sales';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION sales.guard_frozen_document(), sales.guard_frozen_lines() FROM PUBLIC;

-- ------------------------------------------------------------------------------- settings
CREATE TABLE sales.settings (
  company_id                           uuid         NOT NULL,
  default_invoice_policy               text         NOT NULL DEFAULT 'DELIVERED',
  credit_check_mode                    text         NOT NULL DEFAULT 'WARN',
  quotation_validity_days              integer      NOT NULL DEFAULT 30,
  reserve_on_confirm                   boolean      NOT NULL DEFAULT true,
  -- SAL-1: a line discount above this percentage needs sales.order.discount_high (NULL: no limit).
  discount_approval_threshold_percent  numeric(7,4) NULL,
  created_at                           timestamptz  NOT NULL DEFAULT now(),
  created_by                           uuid         NULL,
  updated_at                           timestamptz  NOT NULL DEFAULT now(),
  updated_by                           uuid         NULL,
  version                              integer      NOT NULL DEFAULT 0,
  CONSTRAINT pk_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_settings__invoice_policy CHECK (default_invoice_policy IN ('ORDERED', 'DELIVERED')),
  CONSTRAINT ck_settings__credit_check_mode CHECK (credit_check_mode IN ('NONE', 'WARN', 'BLOCK')),
  CONSTRAINT ck_settings__quotation_validity CHECK (quotation_validity_days BETWEEN 1 AND 365),
  CONSTRAINT ck_settings__discount_threshold
    CHECK (discount_approval_threshold_percent IS NULL OR discount_approval_threshold_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_settings__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('sales.settings');

-- ---------------------------------------------------------------------------- price lists
CREATE TABLE sales.price_lists (
  id                 uuid        NOT NULL DEFAULT uuidv7(),
  company_id         uuid        NOT NULL,
  code               text        NOT NULL,
  name               text        NOT NULL,
  currency_code      char(3)     NOT NULL,
  prices_include_tax boolean     NOT NULL DEFAULT false,
  customer_group_id  uuid        NULL,
  -- Constant, so that the typed foreign key below only accepts CUSTOMER groups.
  group_applies_to   text        GENERATED ALWAYS AS ('CUSTOMER') STORED,
  is_default         boolean     NOT NULL DEFAULT false,
  valid_from         date        NULL,
  valid_to           date        NULL,
  is_active          boolean     NOT NULL DEFAULT true,
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid        NULL,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid        NULL,
  version            integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_price_lists PRIMARY KEY (id),
  CONSTRAINT uq_price_lists__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_price_lists__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_price_lists__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_price_lists__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_price_lists__partner_groups
    FOREIGN KEY (company_id, customer_group_id, group_applies_to)
    REFERENCES partners.partner_groups (company_id, id, applies_to),
  CONSTRAINT ck_price_lists__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_price_lists__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_price_lists__validity CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_price_lists__version CHECK (version >= 0)
);
-- One default list per currency (SAL-1, the last fallback).
CREATE UNIQUE INDEX uq_price_lists__default ON sales.price_lists (company_id, currency_code) WHERE is_default;
CREATE INDEX ix_price_lists__currency_code ON sales.price_lists (currency_code);
CREATE INDEX ix_price_lists__company_id_customer_group_id ON sales.price_lists (company_id, customer_group_id);
SELECT platform.enable_company_rls('sales.price_lists');

CREATE TABLE sales.price_list_items (
  id            uuid          NOT NULL DEFAULT uuidv7(),
  company_id    uuid          NOT NULL,
  price_list_id uuid          NOT NULL,
  variant_id    uuid          NOT NULL,
  uom_id        uuid          NOT NULL,
  min_quantity  numeric(18,6) NOT NULL DEFAULT 0,
  unit_price    numeric(19,6) NOT NULL,
  valid_from    date          NULL,
  valid_to      date          NULL,
  created_at    timestamptz   NOT NULL DEFAULT now(),
  created_by    uuid          NULL,
  updated_at    timestamptz   NOT NULL DEFAULT now(),
  updated_by    uuid          NULL,
  version       integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_price_list_items PRIMARY KEY (id),
  CONSTRAINT uq_price_list_items__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_price_list_items__tier UNIQUE NULLS NOT DISTINCT (price_list_id, variant_id, uom_id, min_quantity, valid_from),
  CONSTRAINT fk_price_list_items__price_lists
    FOREIGN KEY (company_id, price_list_id) REFERENCES sales.price_lists (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_price_list_items__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_price_list_items__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT ck_price_list_items__min_quantity CHECK (min_quantity >= 0),
  CONSTRAINT ck_price_list_items__unit_price CHECK (unit_price >= 0),
  CONSTRAINT ck_price_list_items__validity CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_price_list_items__version CHECK (version >= 0)
);
CREATE INDEX ix_price_list_items__company_id_price_list_id_variant_id
  ON sales.price_list_items (company_id, price_list_id, variant_id);
CREATE INDEX ix_price_list_items__company_id_variant_id ON sales.price_list_items (company_id, variant_id);
CREATE INDEX ix_price_list_items__uom_id ON sales.price_list_items (uom_id);
SELECT platform.enable_company_rls('sales.price_list_items');

-- ----------------------------------------------------------------------------- quotations
CREATE TABLE sales.quotations (
  id                 uuid          NOT NULL DEFAULT uuidv7(),
  company_id         uuid          NOT NULL,
  number             text          NULL,
  customer_id        uuid          NOT NULL,
  branch_id          uuid          NOT NULL,
  warehouse_id       uuid          NOT NULL,
  quotation_date     date          NOT NULL,
  valid_until        date          NOT NULL,
  currency_code      char(3)       NOT NULL,
  price_list_id      uuid          NULL,
  prices_include_tax boolean       NOT NULL DEFAULT false,
  payment_terms_id   uuid          NULL,
  status             text          NOT NULL DEFAULT 'DRAFT',
  subtotal           numeric(19,4) NOT NULL DEFAULT 0,
  tax_total          numeric(19,4) NOT NULL DEFAULT 0,
  total              numeric(19,4) NOT NULL DEFAULT 0,
  sales_order_id     uuid          NULL,
  sent_at            timestamptz   NULL,
  sent_by            uuid          NULL,
  rejection_reason   text          NULL,
  notes              text          NULL,
  created_at         timestamptz   NOT NULL DEFAULT now(),
  created_by         uuid          NULL,
  updated_at         timestamptz   NOT NULL DEFAULT now(),
  updated_by         uuid          NULL,
  version            integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_quotations PRIMARY KEY (id),
  CONSTRAINT uq_quotations__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_quotations__company_id_number UNIQUE (company_id, number),
  CONSTRAINT uq_quotations__sales_order_id UNIQUE (sales_order_id),
  CONSTRAINT fk_quotations__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_quotations__customers
    FOREIGN KEY (company_id, customer_id) REFERENCES partners.customers (company_id, partner_id),
  CONSTRAINT fk_quotations__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_quotations__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_quotations__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_quotations__price_lists
    FOREIGN KEY (company_id, price_list_id) REFERENCES sales.price_lists (company_id, id),
  CONSTRAINT fk_quotations__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT ck_quotations__status
    CHECK (status IN ('DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
  -- Numbered when sent; a draft cancelled before sending keeps no number.
  CONSTRAINT ck_quotations__number CHECK (status IN ('DRAFT', 'CANCELLED') OR number IS NOT NULL),
  CONSTRAINT ck_quotations__accepted CHECK ((status = 'ACCEPTED') = (sales_order_id IS NOT NULL)),
  CONSTRAINT ck_quotations__validity CHECK (valid_until >= quotation_date),
  CONSTRAINT ck_quotations__totals CHECK (subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total),
  CONSTRAINT ck_quotations__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (rejection_reason IS NULL OR length(rejection_reason) <= 500)),
  CONSTRAINT ck_quotations__version CHECK (version >= 0)
);
CREATE INDEX ix_quotations__company_id_customer_id ON sales.quotations (company_id, customer_id);
CREATE INDEX ix_quotations__company_id_branch_id ON sales.quotations (company_id, branch_id);
CREATE INDEX ix_quotations__company_id_warehouse_id ON sales.quotations (company_id, warehouse_id);
CREATE INDEX ix_quotations__currency_code ON sales.quotations (currency_code);
CREATE INDEX ix_quotations__company_id_price_list_id ON sales.quotations (company_id, price_list_id);
CREATE INDEX ix_quotations__company_id_payment_terms_id ON sales.quotations (company_id, payment_terms_id);
CREATE INDEX ix_quotations__company_id_status_valid_until ON sales.quotations (company_id, status, valid_until);
SELECT platform.enable_company_rls('sales.quotations');
CREATE TRIGGER trg_quotations_frozen
  BEFORE UPDATE OR DELETE ON sales.quotations
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_document('status', 'sales_order_id', 'rejection_reason');

CREATE TABLE sales.quotation_lines (
  id               uuid          NOT NULL DEFAULT uuidv7(),
  company_id       uuid          NOT NULL,
  quotation_id     uuid          NOT NULL,
  line_no          integer       NOT NULL,
  variant_id       uuid          NOT NULL,
  description      text          NOT NULL,
  quantity         numeric(18,6) NOT NULL,
  uom_id           uuid          NOT NULL,
  quantity_base    numeric(18,6) NOT NULL,
  unit_price       numeric(19,6) NOT NULL,
  discount_percent numeric(7,4)  NOT NULL DEFAULT 0,
  tax_code_id      uuid          NULL,
  net_amount       numeric(19,4) NOT NULL,
  tax_amount       numeric(19,4) NOT NULL,
  total_amount     numeric(19,4) NOT NULL,
  created_at       timestamptz   NOT NULL DEFAULT now(),
  created_by       uuid          NULL,
  updated_at       timestamptz   NOT NULL DEFAULT now(),
  updated_by       uuid          NULL,
  version          integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_quotation_lines PRIMARY KEY (id),
  CONSTRAINT uq_quotation_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_quotation_lines__quotation_id_line_no UNIQUE (quotation_id, line_no),
  CONSTRAINT fk_quotation_lines__quotations
    FOREIGN KEY (company_id, quotation_id) REFERENCES sales.quotations (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_quotation_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_quotation_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_quotation_lines__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT ck_quotation_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_quotation_lines__description CHECK (btrim(description) <> '' AND length(description) <= 300),
  CONSTRAINT ck_quotation_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_quotation_lines__unit_price CHECK (unit_price >= 0),
  CONSTRAINT ck_quotation_lines__discount CHECK (discount_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_quotation_lines__amounts
    CHECK (net_amount >= 0 AND tax_amount >= 0 AND total_amount = net_amount + tax_amount),
  CONSTRAINT ck_quotation_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_quotation_lines__company_id_variant_id ON sales.quotation_lines (company_id, variant_id);
CREATE INDEX ix_quotation_lines__uom_id ON sales.quotation_lines (uom_id);
CREATE INDEX ix_quotation_lines__company_id_tax_code_id ON sales.quotation_lines (company_id, tax_code_id);
SELECT platform.enable_company_rls('sales.quotation_lines');
CREATE TRIGGER trg_quotation_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON sales.quotation_lines
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_lines('quotations', 'quotation_id');

-- --------------------------------------------------------------------------- sales orders
CREATE TABLE sales.sales_orders (
  id                     uuid           NOT NULL DEFAULT uuidv7(),
  company_id             uuid           NOT NULL,
  number                 text           NULL,
  customer_id            uuid           NOT NULL,
  quotation_id           uuid           NULL,
  branch_id              uuid           NOT NULL,
  warehouse_id           uuid           NOT NULL,
  order_date             date           NOT NULL,
  requested_date         date           NULL,
  customer_reference     text           NULL,
  currency_code          char(3)        NOT NULL,
  price_list_id          uuid           NULL,
  prices_include_tax     boolean        NOT NULL DEFAULT false,
  payment_terms_id       uuid           NULL,
  invoice_policy         text           NOT NULL,
  -- Snapshots (G-10): immutable copies of the customer's default addresses at order time.
  shipping_address       jsonb          NOT NULL DEFAULT '{}'::jsonb,
  billing_address        jsonb          NOT NULL DEFAULT '{}'::jsonb,
  status                 text           NOT NULL DEFAULT 'DRAFT',
  invoice_status         text           NOT NULL DEFAULT 'NOT_INVOICED',
  -- Set at confirmation: the rate the credit check used (document currency → base).
  exchange_rate          numeric(19,10) NULL,
  credit_check_result    text           NULL,
  credit_override_by     uuid           NULL,
  credit_override_reason text           NULL,
  subtotal               numeric(19,4)  NOT NULL DEFAULT 0,
  tax_total              numeric(19,4)  NOT NULL DEFAULT 0,
  total                  numeric(19,4)  NOT NULL DEFAULT 0,
  confirmed_at           timestamptz    NULL,
  confirmed_by           uuid           NULL,
  cancel_reason          text           NULL,
  close_reason           text           NULL,
  notes                  text           NULL,
  created_at             timestamptz    NOT NULL DEFAULT now(),
  created_by             uuid           NULL,
  updated_at             timestamptz    NOT NULL DEFAULT now(),
  updated_by             uuid           NULL,
  version                integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_sales_orders PRIMARY KEY (id),
  CONSTRAINT uq_sales_orders__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_sales_orders__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_sales_orders__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_sales_orders__customers
    FOREIGN KEY (company_id, customer_id) REFERENCES partners.customers (company_id, partner_id),
  CONSTRAINT fk_sales_orders__quotations
    FOREIGN KEY (company_id, quotation_id) REFERENCES sales.quotations (company_id, id),
  CONSTRAINT fk_sales_orders__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_sales_orders__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_sales_orders__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_sales_orders__price_lists
    FOREIGN KEY (company_id, price_list_id) REFERENCES sales.price_lists (company_id, id),
  CONSTRAINT fk_sales_orders__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT ck_sales_orders__status CHECK (status IN
    ('DRAFT', 'CONFIRMED', 'PARTIALLY_DELIVERED', 'DELIVERED', 'CLOSED', 'CANCELLED')),
  CONSTRAINT ck_sales_orders__invoice_status
    CHECK (invoice_status IN ('NOT_INVOICED', 'PARTIALLY_INVOICED', 'INVOICED')),
  CONSTRAINT ck_sales_orders__invoice_policy CHECK (invoice_policy IN ('ORDERED', 'DELIVERED')),
  CONSTRAINT ck_sales_orders__credit_check_result
    CHECK (credit_check_result IS NULL OR credit_check_result IN ('PASSED', 'WARNED', 'OVERRIDDEN')),
  CONSTRAINT ck_sales_orders__credit_override CHECK (
    (credit_check_result = 'OVERRIDDEN') = (credit_override_by IS NOT NULL AND credit_override_reason IS NOT NULL)),
  -- Numbered and credit-checked at confirmation (G-6, SAL-2).
  CONSTRAINT ck_sales_orders__confirmed CHECK (status IN ('DRAFT', 'CANCELLED') OR (
    number IS NOT NULL AND confirmed_at IS NOT NULL AND exchange_rate IS NOT NULL AND credit_check_result IS NOT NULL)),
  CONSTRAINT ck_sales_orders__exchange_rate CHECK (exchange_rate IS NULL OR exchange_rate > 0),
  CONSTRAINT ck_sales_orders__addresses
    CHECK (jsonb_typeof(shipping_address) = 'object' AND jsonb_typeof(billing_address) = 'object'),
  CONSTRAINT ck_sales_orders__totals CHECK (subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total),
  CONSTRAINT ck_sales_orders__requested_date CHECK (requested_date IS NULL OR requested_date >= order_date),
  CONSTRAINT ck_sales_orders__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (customer_reference IS NULL OR length(customer_reference) <= 100)
    AND (cancel_reason IS NULL OR length(cancel_reason) <= 500) AND (close_reason IS NULL OR length(close_reason) <= 500)
    AND (credit_override_reason IS NULL OR length(credit_override_reason) <= 500)),
  CONSTRAINT ck_sales_orders__version CHECK (version >= 0)
);
CREATE INDEX ix_sales_orders__company_id_customer_id_order_date
  ON sales.sales_orders (company_id, customer_id, order_date DESC);
CREATE INDEX ix_sales_orders__company_id_status ON sales.sales_orders (company_id, status);
CREATE INDEX ix_sales_orders__company_id_quotation_id ON sales.sales_orders (company_id, quotation_id);
CREATE INDEX ix_sales_orders__company_id_branch_id ON sales.sales_orders (company_id, branch_id);
CREATE INDEX ix_sales_orders__company_id_warehouse_id ON sales.sales_orders (company_id, warehouse_id);
CREATE INDEX ix_sales_orders__currency_code ON sales.sales_orders (currency_code);
CREATE INDEX ix_sales_orders__company_id_price_list_id ON sales.sales_orders (company_id, price_list_id);
CREATE INDEX ix_sales_orders__company_id_payment_terms_id ON sales.sales_orders (company_id, payment_terms_id);
SELECT platform.enable_company_rls('sales.sales_orders');
CREATE TRIGGER trg_sales_orders_frozen
  BEFORE UPDATE OR DELETE ON sales.sales_orders
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_document('status', 'invoice_status', 'cancel_reason', 'close_reason');

ALTER TABLE sales.quotations
  ADD CONSTRAINT fk_quotations__sales_orders
  FOREIGN KEY (company_id, sales_order_id) REFERENCES sales.sales_orders (company_id, id);

CREATE TABLE sales.sales_order_lines (
  id                      uuid          NOT NULL DEFAULT uuidv7(),
  company_id              uuid          NOT NULL,
  sales_order_id          uuid          NOT NULL,
  line_no                 integer       NOT NULL,
  variant_id              uuid          NOT NULL,
  description             text          NOT NULL,
  -- Product type snapshot (G-10): only stockable lines are reserved and delivered.
  is_stockable            boolean       NOT NULL,
  quantity                numeric(18,6) NOT NULL,
  uom_id                  uuid          NOT NULL,
  quantity_base           numeric(18,6) NOT NULL,
  unit_price              numeric(19,6) NOT NULL,
  discount_percent        numeric(7,4)  NOT NULL DEFAULT 0,
  tax_code_id             uuid          NULL,
  net_amount              numeric(19,4) NOT NULL,
  tax_amount              numeric(19,4) NOT NULL,
  total_amount            numeric(19,4) NOT NULL,
  -- The line's reservation in Inventory (authority: inventory.stock_reservations) and its mirror.
  reservation_id          uuid          NULL,
  reserved_quantity_base  numeric(18,6) NOT NULL DEFAULT 0,
  delivered_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  returned_quantity_base  numeric(18,6) NOT NULL DEFAULT 0,
  invoiced_quantity_base  numeric(18,6) NOT NULL DEFAULT 0,
  created_at              timestamptz   NOT NULL DEFAULT now(),
  created_by              uuid          NULL,
  updated_at              timestamptz   NOT NULL DEFAULT now(),
  updated_by              uuid          NULL,
  version                 integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_sales_order_lines PRIMARY KEY (id),
  CONSTRAINT uq_sales_order_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_sales_order_lines__sales_order_id_line_no UNIQUE (sales_order_id, line_no),
  CONSTRAINT fk_sales_order_lines__sales_orders
    FOREIGN KEY (company_id, sales_order_id) REFERENCES sales.sales_orders (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_sales_order_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_sales_order_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_sales_order_lines__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_sales_order_lines__stock_reservations
    FOREIGN KEY (company_id, reservation_id) REFERENCES inventory.stock_reservations (company_id, id),
  CONSTRAINT ck_sales_order_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_sales_order_lines__description CHECK (btrim(description) <> '' AND length(description) <= 300),
  CONSTRAINT ck_sales_order_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_sales_order_lines__unit_price CHECK (unit_price >= 0),
  CONSTRAINT ck_sales_order_lines__discount CHECK (discount_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_sales_order_lines__amounts
    CHECK (net_amount >= 0 AND tax_amount >= 0 AND total_amount = net_amount + tax_amount),
  -- SAL-4 and SAL-7: never deliver more than ordered, never return more than delivered.
  CONSTRAINT ck_sales_order_lines__delivered CHECK (
    delivered_quantity_base >= 0 AND delivered_quantity_base <= quantity_base
    AND returned_quantity_base >= 0 AND returned_quantity_base <= delivered_quantity_base),
  CONSTRAINT ck_sales_order_lines__not_stockable_undelivered
    CHECK (is_stockable OR (delivered_quantity_base = 0 AND reserved_quantity_base = 0)),
  CONSTRAINT ck_sales_order_lines__reserved CHECK (reserved_quantity_base >= 0),
  CONSTRAINT ck_sales_order_lines__invoiced CHECK (invoiced_quantity_base >= 0),
  CONSTRAINT ck_sales_order_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_sales_order_lines__company_id_variant_id ON sales.sales_order_lines (company_id, variant_id);
CREATE INDEX ix_sales_order_lines__uom_id ON sales.sales_order_lines (uom_id);
CREATE INDEX ix_sales_order_lines__company_id_tax_code_id ON sales.sales_order_lines (company_id, tax_code_id);
CREATE INDEX ix_sales_order_lines__company_id_reservation_id ON sales.sales_order_lines (company_id, reservation_id);
SELECT platform.enable_company_rls('sales.sales_order_lines');
CREATE TRIGGER trg_sales_order_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON sales.sales_order_lines
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_lines(
    'sales_orders', 'sales_order_id', 'reservation_id', 'reserved_quantity_base',
    'delivered_quantity_base', 'returned_quantity_base', 'invoiced_quantity_base');

-- ----------------------------------------------------------------------------- deliveries
CREATE TABLE sales.deliveries (
  id                uuid        NOT NULL DEFAULT uuidv7(),
  company_id        uuid        NOT NULL,
  number            text        NULL,
  sales_order_id    uuid        NOT NULL,
  customer_id       uuid        NOT NULL,
  branch_id         uuid        NOT NULL,
  warehouse_id      uuid        NOT NULL,
  delivery_date     date        NOT NULL,
  status            text        NOT NULL DEFAULT 'DRAFT',
  shipping_address  jsonb       NOT NULL DEFAULT '{}'::jsonb,
  carrier           text        NULL,
  tracking_number   text        NULL,
  stock_movement_id uuid        NULL,
  notes             text        NULL,
  posted_at         timestamptz NULL,
  posted_by         uuid        NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid        NULL,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid        NULL,
  version           integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_deliveries PRIMARY KEY (id),
  CONSTRAINT uq_deliveries__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_deliveries__company_id_number UNIQUE (company_id, number),
  -- A stock movement belongs to at most one delivery: no double stock issue.
  CONSTRAINT uq_deliveries__stock_movement_id UNIQUE (stock_movement_id),
  CONSTRAINT fk_deliveries__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_deliveries__sales_orders
    FOREIGN KEY (company_id, sales_order_id) REFERENCES sales.sales_orders (company_id, id),
  CONSTRAINT fk_deliveries__customers
    FOREIGN KEY (company_id, customer_id) REFERENCES partners.customers (company_id, partner_id),
  CONSTRAINT fk_deliveries__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_deliveries__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_deliveries__stock_movements
    FOREIGN KEY (company_id, stock_movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT ck_deliveries__status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_deliveries__posted CHECK ((status = 'POSTED') = (
    number IS NOT NULL AND stock_movement_id IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_deliveries__address CHECK (jsonb_typeof(shipping_address) = 'object'),
  CONSTRAINT ck_deliveries__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000) AND (carrier IS NULL OR length(carrier) <= 100)
    AND (tracking_number IS NULL OR length(tracking_number) <= 100)),
  CONSTRAINT ck_deliveries__version CHECK (version >= 0)
);
CREATE INDEX ix_deliveries__company_id_sales_order_id ON sales.deliveries (company_id, sales_order_id);
CREATE INDEX ix_deliveries__company_id_customer_id ON sales.deliveries (company_id, customer_id);
CREATE INDEX ix_deliveries__company_id_branch_id ON sales.deliveries (company_id, branch_id);
CREATE INDEX ix_deliveries__company_id_warehouse_id ON sales.deliveries (company_id, warehouse_id);
SELECT platform.enable_company_rls('sales.deliveries');
CREATE TRIGGER trg_deliveries_frozen
  BEFORE UPDATE OR DELETE ON sales.deliveries
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_document();

CREATE TABLE sales.delivery_lines (
  id                     uuid          NOT NULL DEFAULT uuidv7(),
  company_id             uuid          NOT NULL,
  delivery_id            uuid          NOT NULL,
  line_no                integer       NOT NULL,
  sales_order_line_id    uuid          NOT NULL,
  variant_id             uuid          NOT NULL,
  -- NULL in a draft: the warehouse's default stock location; set to the actual location at posting.
  location_id            uuid          NULL,
  quantity               numeric(18,6) NOT NULL,
  uom_id                 uuid          NOT NULL,
  quantity_base          numeric(18,6) NOT NULL,
  -- Set at posting from the inventory ledger (moving average): returns come back at this cost (SAL-7).
  unit_cost_base         numeric(19,6) NULL,
  value_base             numeric(19,4) NULL,
  returned_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  created_at             timestamptz   NOT NULL DEFAULT now(),
  created_by             uuid          NULL,
  updated_at             timestamptz   NOT NULL DEFAULT now(),
  updated_by             uuid          NULL,
  version                integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_delivery_lines PRIMARY KEY (id),
  CONSTRAINT uq_delivery_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_delivery_lines__delivery_id_line_no UNIQUE (delivery_id, line_no),
  CONSTRAINT fk_delivery_lines__deliveries
    FOREIGN KEY (company_id, delivery_id) REFERENCES sales.deliveries (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_delivery_lines__sales_order_lines
    FOREIGN KEY (company_id, sales_order_line_id) REFERENCES sales.sales_order_lines (company_id, id),
  CONSTRAINT fk_delivery_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_delivery_lines__locations
    FOREIGN KEY (company_id, location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT fk_delivery_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT ck_delivery_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_delivery_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_delivery_lines__costs CHECK (
    (unit_cost_base IS NULL) = (value_base IS NULL) AND (unit_cost_base IS NULL OR unit_cost_base >= 0)),
  CONSTRAINT ck_delivery_lines__returned
    CHECK (returned_quantity_base >= 0 AND returned_quantity_base <= quantity_base),
  CONSTRAINT ck_delivery_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_delivery_lines__company_id_sales_order_line_id ON sales.delivery_lines (company_id, sales_order_line_id);
CREATE INDEX ix_delivery_lines__company_id_variant_id ON sales.delivery_lines (company_id, variant_id);
CREATE INDEX ix_delivery_lines__company_id_location_id ON sales.delivery_lines (company_id, location_id);
CREATE INDEX ix_delivery_lines__uom_id ON sales.delivery_lines (uom_id);
SELECT platform.enable_company_rls('sales.delivery_lines');
CREATE TRIGGER trg_delivery_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON sales.delivery_lines
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_lines('deliveries', 'delivery_id', 'returned_quantity_base');

-- -------------------------------------------------------------------------- sales returns
CREATE TABLE sales.sales_returns (
  id                uuid        NOT NULL DEFAULT uuidv7(),
  company_id        uuid        NOT NULL,
  number            text        NULL,
  customer_id       uuid        NOT NULL,
  sales_order_id    uuid        NOT NULL,
  delivery_id       uuid        NOT NULL,
  branch_id         uuid        NOT NULL,
  warehouse_id      uuid        NOT NULL,
  return_date       date        NOT NULL,
  reason            text        NOT NULL,
  status            text        NOT NULL DEFAULT 'DRAFT',
  stock_movement_id uuid        NULL,
  received_at       timestamptz NULL,
  received_by       uuid        NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        uuid        NULL,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  updated_by        uuid        NULL,
  version           integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_sales_returns PRIMARY KEY (id),
  CONSTRAINT uq_sales_returns__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_sales_returns__company_id_number UNIQUE (company_id, number),
  CONSTRAINT uq_sales_returns__stock_movement_id UNIQUE (stock_movement_id),
  CONSTRAINT fk_sales_returns__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_sales_returns__customers
    FOREIGN KEY (company_id, customer_id) REFERENCES partners.customers (company_id, partner_id),
  CONSTRAINT fk_sales_returns__sales_orders
    FOREIGN KEY (company_id, sales_order_id) REFERENCES sales.sales_orders (company_id, id),
  CONSTRAINT fk_sales_returns__deliveries
    FOREIGN KEY (company_id, delivery_id) REFERENCES sales.deliveries (company_id, id),
  CONSTRAINT fk_sales_returns__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_sales_returns__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_sales_returns__stock_movements
    FOREIGN KEY (company_id, stock_movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT ck_sales_returns__status CHECK (status IN ('DRAFT', 'RECEIVED', 'CANCELLED')),
  CONSTRAINT ck_sales_returns__received CHECK ((status = 'RECEIVED') = (
    number IS NOT NULL AND stock_movement_id IS NOT NULL AND received_at IS NOT NULL)),
  CONSTRAINT ck_sales_returns__reason CHECK (btrim(reason) <> '' AND length(reason) <= 500),
  CONSTRAINT ck_sales_returns__version CHECK (version >= 0)
);
CREATE INDEX ix_sales_returns__company_id_customer_id ON sales.sales_returns (company_id, customer_id);
CREATE INDEX ix_sales_returns__company_id_sales_order_id ON sales.sales_returns (company_id, sales_order_id);
CREATE INDEX ix_sales_returns__company_id_delivery_id ON sales.sales_returns (company_id, delivery_id);
CREATE INDEX ix_sales_returns__company_id_branch_id ON sales.sales_returns (company_id, branch_id);
CREATE INDEX ix_sales_returns__company_id_warehouse_id ON sales.sales_returns (company_id, warehouse_id);
SELECT platform.enable_company_rls('sales.sales_returns');
CREATE TRIGGER trg_sales_returns_frozen
  BEFORE UPDATE OR DELETE ON sales.sales_returns
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_document();

CREATE TABLE sales.sales_return_lines (
  id                     uuid          NOT NULL DEFAULT uuidv7(),
  company_id             uuid          NOT NULL,
  sales_return_id        uuid          NOT NULL,
  line_no                integer       NOT NULL,
  delivery_line_id       uuid          NOT NULL,
  variant_id             uuid          NOT NULL,
  location_id            uuid          NULL,
  quantity               numeric(18,6) NOT NULL,
  uom_id                 uuid          NOT NULL,
  quantity_base          numeric(18,6) NOT NULL,
  -- The delivery's unit cost: stock comes back at the original cost (SAL-7).
  unit_cost_base         numeric(19,6) NOT NULL,
  value_base             numeric(19,4) NULL,
  -- Quantity already credited by credit notes referencing this return (SAL-6).
  credited_quantity_base numeric(18,6) NOT NULL DEFAULT 0,
  created_at             timestamptz   NOT NULL DEFAULT now(),
  created_by             uuid          NULL,
  updated_at             timestamptz   NOT NULL DEFAULT now(),
  updated_by             uuid          NULL,
  version                integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_sales_return_lines PRIMARY KEY (id),
  CONSTRAINT uq_sales_return_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_sales_return_lines__sales_return_id_line_no UNIQUE (sales_return_id, line_no),
  CONSTRAINT fk_sales_return_lines__sales_returns
    FOREIGN KEY (company_id, sales_return_id) REFERENCES sales.sales_returns (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_sales_return_lines__delivery_lines
    FOREIGN KEY (company_id, delivery_line_id) REFERENCES sales.delivery_lines (company_id, id),
  CONSTRAINT fk_sales_return_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_sales_return_lines__locations
    FOREIGN KEY (company_id, location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT fk_sales_return_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT ck_sales_return_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_sales_return_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_sales_return_lines__costs CHECK (unit_cost_base >= 0 AND (value_base IS NULL OR value_base >= 0)),
  CONSTRAINT ck_sales_return_lines__credited
    CHECK (credited_quantity_base >= 0 AND credited_quantity_base <= quantity_base),
  CONSTRAINT ck_sales_return_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_sales_return_lines__company_id_delivery_line_id ON sales.sales_return_lines (company_id, delivery_line_id);
CREATE INDEX ix_sales_return_lines__company_id_variant_id ON sales.sales_return_lines (company_id, variant_id);
CREATE INDEX ix_sales_return_lines__company_id_location_id ON sales.sales_return_lines (company_id, location_id);
CREATE INDEX ix_sales_return_lines__uom_id ON sales.sales_return_lines (uom_id);
SELECT platform.enable_company_rls('sales.sales_return_lines');
CREATE TRIGGER trg_sales_return_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON sales.sales_return_lines
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_lines('sales_returns', 'sales_return_id', 'credited_quantity_base');

-- ------------------------------------------------------------- invoices and credit notes
CREATE TABLE sales.invoices (
  id                          uuid           NOT NULL DEFAULT uuidv7(),
  company_id                  uuid           NOT NULL,
  document_type               text           NOT NULL,
  number                      text           NULL,
  customer_id                 uuid           NOT NULL,
  sales_order_id              uuid           NULL,
  original_invoice_id         uuid           NULL,
  sales_return_id             uuid           NULL,
  invoice_date                date           NOT NULL,
  accounting_date             date           NOT NULL,
  due_date                    date           NOT NULL,
  currency_code               char(3)        NOT NULL,
  exchange_rate               numeric(19,10) NOT NULL,
  prices_include_tax          boolean        NOT NULL DEFAULT false,
  payment_terms_id            uuid           NULL,
  -- Snapshots (G-10): the billing address and tax registration as invoiced.
  billing_address             jsonb          NOT NULL DEFAULT '{}'::jsonb,
  customer_tax_registration_no text          NULL,
  status                      text           NOT NULL DEFAULT 'DRAFT',
  subtotal                    numeric(19,4)  NOT NULL DEFAULT 0,
  tax_total                   numeric(19,4)  NOT NULL DEFAULT 0,
  total                       numeric(19,4)  NOT NULL DEFAULT 0,
  subtotal_base               numeric(19,4)  NOT NULL DEFAULT 0,
  tax_total_base              numeric(19,4)  NOT NULL DEFAULT 0,
  total_base                  numeric(19,4)  NOT NULL DEFAULT 0,
  notes                       text           NULL,
  posted_at                   timestamptz    NULL,
  posted_by                   uuid           NULL,
  created_at                  timestamptz    NOT NULL DEFAULT now(),
  created_by                  uuid           NULL,
  updated_at                  timestamptz    NOT NULL DEFAULT now(),
  updated_by                  uuid           NULL,
  version                     integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_invoices PRIMARY KEY (id),
  CONSTRAINT uq_invoices__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_invoices__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_invoices__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_invoices__customers
    FOREIGN KEY (company_id, customer_id) REFERENCES partners.customers (company_id, partner_id),
  CONSTRAINT fk_invoices__sales_orders
    FOREIGN KEY (company_id, sales_order_id) REFERENCES sales.sales_orders (company_id, id),
  CONSTRAINT fk_invoices__invoices
    FOREIGN KEY (company_id, original_invoice_id) REFERENCES sales.invoices (company_id, id),
  CONSTRAINT fk_invoices__sales_returns
    FOREIGN KEY (company_id, sales_return_id) REFERENCES sales.sales_returns (company_id, id),
  CONSTRAINT fk_invoices__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_invoices__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT ck_invoices__document_type CHECK (document_type IN ('INVOICE', 'CREDIT_NOTE')),
  -- A credit note always credits an invoice (SAL-6); it may also refer to the return it credits.
  CONSTRAINT ck_invoices__credit_note CHECK (
    (document_type = 'CREDIT_NOTE') = (original_invoice_id IS NOT NULL)
    AND (sales_return_id IS NULL OR document_type = 'CREDIT_NOTE')),
  CONSTRAINT ck_invoices__status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_invoices__posted CHECK ((status = 'POSTED') = (number IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_invoices__exchange_rate CHECK (exchange_rate > 0),
  CONSTRAINT ck_invoices__totals CHECK (
    subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total
    AND subtotal_base >= 0 AND tax_total_base >= 0 AND total_base = subtotal_base + tax_total_base),
  CONSTRAINT ck_invoices__dates CHECK (due_date >= invoice_date),
  CONSTRAINT ck_invoices__address CHECK (jsonb_typeof(billing_address) = 'object'),
  CONSTRAINT ck_invoices__texts CHECK (
    (notes IS NULL OR length(notes) <= 2000)
    AND (customer_tax_registration_no IS NULL OR length(customer_tax_registration_no) <= 50)),
  CONSTRAINT ck_invoices__version CHECK (version >= 0)
);
CREATE INDEX ix_invoices__company_id_customer_id_invoice_date ON sales.invoices (company_id, customer_id, invoice_date DESC);
CREATE INDEX ix_invoices__company_id_status_invoice_date ON sales.invoices (company_id, status, invoice_date);
CREATE INDEX ix_invoices__company_id_sales_order_id ON sales.invoices (company_id, sales_order_id);
CREATE INDEX ix_invoices__company_id_original_invoice_id ON sales.invoices (company_id, original_invoice_id);
CREATE INDEX ix_invoices__company_id_sales_return_id ON sales.invoices (company_id, sales_return_id);
CREATE INDEX ix_invoices__currency_code ON sales.invoices (currency_code);
CREATE INDEX ix_invoices__company_id_payment_terms_id ON sales.invoices (company_id, payment_terms_id);
SELECT platform.enable_company_rls('sales.invoices');
CREATE TRIGGER trg_invoices_frozen
  BEFORE UPDATE OR DELETE ON sales.invoices
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_document();

CREATE TABLE sales.invoice_lines (
  id                       uuid          NOT NULL DEFAULT uuidv7(),
  company_id               uuid          NOT NULL,
  invoice_id               uuid          NOT NULL,
  line_no                  integer       NOT NULL,
  sales_order_line_id      uuid          NULL,
  delivery_line_id         uuid          NULL,
  -- Credit notes: the invoice line credited, and the return line when goods came back.
  original_invoice_line_id uuid          NULL,
  sales_return_line_id     uuid          NULL,
  variant_id               uuid          NOT NULL,
  description              text          NOT NULL,
  quantity                 numeric(18,6) NOT NULL,
  uom_id                   uuid          NOT NULL,
  quantity_base            numeric(18,6) NOT NULL,
  unit_price               numeric(19,6) NOT NULL,
  discount_percent         numeric(7,4)  NOT NULL DEFAULT 0,
  tax_code_id              uuid          NULL,
  net_amount               numeric(19,4) NOT NULL,
  tax_amount               numeric(19,4) NOT NULL,
  total_amount             numeric(19,4) NOT NULL,
  net_amount_base          numeric(19,4) NOT NULL,
  tax_amount_base          numeric(19,4) NOT NULL,
  branch_id                uuid          NULL,
  department_id            uuid          NULL,
  -- Invoices: quantity already credited by credit notes (SAL-6).
  credited_quantity_base   numeric(18,6) NOT NULL DEFAULT 0,
  created_at               timestamptz   NOT NULL DEFAULT now(),
  created_by               uuid          NULL,
  updated_at               timestamptz   NOT NULL DEFAULT now(),
  updated_by               uuid          NULL,
  version                  integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_invoice_lines PRIMARY KEY (id),
  CONSTRAINT uq_invoice_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_invoice_lines__invoice_id_line_no UNIQUE (invoice_id, line_no),
  CONSTRAINT fk_invoice_lines__invoices
    FOREIGN KEY (company_id, invoice_id) REFERENCES sales.invoices (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_invoice_lines__sales_order_lines
    FOREIGN KEY (company_id, sales_order_line_id) REFERENCES sales.sales_order_lines (company_id, id),
  CONSTRAINT fk_invoice_lines__delivery_lines
    FOREIGN KEY (company_id, delivery_line_id) REFERENCES sales.delivery_lines (company_id, id),
  CONSTRAINT fk_invoice_lines__invoice_lines
    FOREIGN KEY (company_id, original_invoice_line_id) REFERENCES sales.invoice_lines (company_id, id),
  CONSTRAINT fk_invoice_lines__sales_return_lines
    FOREIGN KEY (company_id, sales_return_line_id) REFERENCES sales.sales_return_lines (company_id, id),
  CONSTRAINT fk_invoice_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_invoice_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_invoice_lines__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_invoice_lines__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_invoice_lines__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT ck_invoice_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_invoice_lines__description CHECK (btrim(description) <> '' AND length(description) <= 300),
  CONSTRAINT ck_invoice_lines__quantity CHECK (quantity > 0 AND quantity_base > 0),
  CONSTRAINT ck_invoice_lines__unit_price CHECK (unit_price >= 0),
  CONSTRAINT ck_invoice_lines__discount CHECK (discount_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_invoice_lines__amounts CHECK (
    net_amount >= 0 AND tax_amount >= 0 AND total_amount = net_amount + tax_amount
    AND net_amount_base >= 0 AND tax_amount_base >= 0),
  CONSTRAINT ck_invoice_lines__return_link CHECK (sales_return_line_id IS NULL OR original_invoice_line_id IS NOT NULL),
  -- SAL-6: never credit more than was invoiced on the line.
  CONSTRAINT ck_invoice_lines__credited
    CHECK (credited_quantity_base >= 0 AND credited_quantity_base <= quantity_base),
  CONSTRAINT ck_invoice_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_invoice_lines__company_id_sales_order_line_id ON sales.invoice_lines (company_id, sales_order_line_id);
CREATE INDEX ix_invoice_lines__company_id_delivery_line_id ON sales.invoice_lines (company_id, delivery_line_id);
CREATE INDEX ix_invoice_lines__company_id_original_invoice_line_id
  ON sales.invoice_lines (company_id, original_invoice_line_id);
CREATE INDEX ix_invoice_lines__company_id_sales_return_line_id ON sales.invoice_lines (company_id, sales_return_line_id);
CREATE INDEX ix_invoice_lines__company_id_variant_id ON sales.invoice_lines (company_id, variant_id);
CREATE INDEX ix_invoice_lines__uom_id ON sales.invoice_lines (uom_id);
CREATE INDEX ix_invoice_lines__company_id_tax_code_id ON sales.invoice_lines (company_id, tax_code_id);
CREATE INDEX ix_invoice_lines__company_id_branch_id ON sales.invoice_lines (company_id, branch_id);
CREATE INDEX ix_invoice_lines__company_id_department_id ON sales.invoice_lines (company_id, department_id);
SELECT platform.enable_company_rls('sales.invoice_lines');
CREATE TRIGGER trg_invoice_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON sales.invoice_lines
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_lines('invoices', 'invoice_id', 'credited_quantity_base');

CREATE TABLE sales.invoice_taxes (
  invoice_id          uuid          NOT NULL,
  tax_code_id         uuid          NOT NULL,
  company_id          uuid          NOT NULL,
  rate_percent        numeric(7,4)  NOT NULL,
  taxable_amount      numeric(19,4) NOT NULL,
  tax_amount          numeric(19,4) NOT NULL,
  taxable_amount_base numeric(19,4) NOT NULL,
  tax_amount_base     numeric(19,4) NOT NULL,
  CONSTRAINT pk_invoice_taxes PRIMARY KEY (invoice_id, tax_code_id),
  CONSTRAINT fk_invoice_taxes__invoices
    FOREIGN KEY (company_id, invoice_id) REFERENCES sales.invoices (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_invoice_taxes__tax_codes
    FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT ck_invoice_taxes__amounts CHECK (
    rate_percent >= 0 AND taxable_amount >= 0 AND tax_amount >= 0 AND taxable_amount_base >= 0 AND tax_amount_base >= 0)
);
CREATE INDEX ix_invoice_taxes__company_id_invoice_id ON sales.invoice_taxes (company_id, invoice_id);
CREATE INDEX ix_invoice_taxes__company_id_tax_code_id ON sales.invoice_taxes (company_id, tax_code_id);
SELECT platform.enable_company_rls('sales.invoice_taxes');
CREATE TRIGGER trg_invoice_taxes_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON sales.invoice_taxes
  FOR EACH ROW EXECUTE FUNCTION sales.guard_frozen_lines('invoices', 'invoice_id');
