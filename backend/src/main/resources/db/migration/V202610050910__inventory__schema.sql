-- =====================================================================================
-- Inventory (Phase 5). Logical model: DATABASE.md §5.5; triggers §8.2; locks §9.
-- Every company-scoped table has RLS and composite same-company foreign keys. Stock quantities
-- change only by posting stock movements, which write the append-only inventory ledger and the
-- derived balances in one transaction.
-- =====================================================================================

SELECT platform.setup_module_schema('inventory');

-- ------------------------------------------------------------------------------- settings
CREATE TABLE inventory.settings (
  company_id                     uuid          NOT NULL,
  costing_method                 text          NOT NULL DEFAULT 'MOVING_AVERAGE',
  allow_negative_stock           boolean       NOT NULL DEFAULT false,
  over_receipt_tolerance_percent numeric(7,4)  NOT NULL DEFAULT 0,
  adjustment_approval_threshold  numeric(19,4) NULL,
  created_at                     timestamptz   NOT NULL DEFAULT now(),
  created_by                     uuid          NULL,
  updated_at                     timestamptz   NOT NULL DEFAULT now(),
  updated_by                     uuid          NULL,
  version                        integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_settings__costing_method CHECK (costing_method IN ('MOVING_AVERAGE')),
  CONSTRAINT ck_settings__no_negative_stock CHECK (allow_negative_stock = false),
  CONSTRAINT ck_settings__over_receipt_tolerance CHECK (over_receipt_tolerance_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_settings__adjustment_threshold
    CHECK (adjustment_approval_threshold IS NULL OR adjustment_approval_threshold >= 0),
  CONSTRAINT ck_settings__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('inventory.settings');

-- ------------------------------------------------------------------- units of measure (global)
CREATE TABLE inventory.uom_categories (
  id   uuid NOT NULL DEFAULT uuidv7(),
  code text NOT NULL,
  name text NOT NULL,
  CONSTRAINT pk_uom_categories PRIMARY KEY (id),
  CONSTRAINT uq_uom_categories__code UNIQUE (code),
  CONSTRAINT ck_uom_categories__code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  CONSTRAINT ck_uom_categories__name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE inventory.uoms (
  id                  uuid           NOT NULL DEFAULT uuidv7(),
  category_id         uuid           NOT NULL,
  code                text           NOT NULL,
  name                text           NOT NULL,
  factor_to_reference numeric(24,12) NOT NULL,
  rounding_scale      smallint       NOT NULL DEFAULT 6,
  is_active           boolean        NOT NULL DEFAULT true,
  CONSTRAINT pk_uoms PRIMARY KEY (id),
  CONSTRAINT uq_uoms__code UNIQUE (code),
  CONSTRAINT fk_uoms__uom_categories FOREIGN KEY (category_id) REFERENCES inventory.uom_categories (id),
  CONSTRAINT ck_uoms__code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,19}$'),
  CONSTRAINT ck_uoms__name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_uoms__factor_positive CHECK (factor_to_reference > 0),
  CONSTRAINT ck_uoms__rounding_scale CHECK (rounding_scale BETWEEN 0 AND 6)
);
CREATE INDEX ix_uoms__category_id ON inventory.uoms (category_id);

-- Reference data maintained by migrations only (R__seed_inventory_uoms.sql).
REVOKE INSERT, UPDATE, DELETE ON inventory.uom_categories, inventory.uoms FROM erp_app;
GRANT SELECT ON inventory.uom_categories, inventory.uoms TO erp_reporting;

-- ---------------------------------------------------------------------- product categories
CREATE TABLE inventory.product_categories (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  code       text        NOT NULL,
  name       text        NOT NULL,
  parent_id  uuid        NULL,
  path       ltree       NOT NULL,
  is_active  boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_product_categories PRIMARY KEY (id),
  CONSTRAINT uq_product_categories__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_product_categories__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_product_categories__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_product_categories__product_categories_parent
    FOREIGN KEY (company_id, parent_id) REFERENCES inventory.product_categories (company_id, id),
  CONSTRAINT ck_product_categories__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_product_categories__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_product_categories__not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
  CONSTRAINT ck_product_categories__version CHECK (version >= 0)
);
CREATE INDEX ix_product_categories__company_id_parent_id ON inventory.product_categories (company_id, parent_id);
CREATE INDEX ix_product_categories__path ON inventory.product_categories USING gist (path);
SELECT platform.enable_company_rls('inventory.product_categories');

-- trg_category_path (DATABASE.md §8.2): path = parent's path + this node's label (its id), so code
-- changes never touch paths. A move rejects cycles and rewrites the subtree's paths. Tree changes of
-- a company are serialized with an advisory lock.
CREATE FUNCTION inventory.category_label(p_id uuid) RETURNS ltree
  LANGUAGE sql IMMUTABLE
  SET search_path = pg_catalog, public
AS $$ SELECT text2ltree('c' || replace(p_id::text, '-', '')) $$;

CREATE FUNCTION inventory.guard_category_path()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, public, inventory
AS $$
DECLARE
  v_parent_path ltree;
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.parent_id IS NOT DISTINCT FROM OLD.parent_id THEN
    NEW.path := OLD.path;
    RETURN NEW;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('inventory.product_categories'), hashtext(NEW.company_id::text));
  IF NEW.parent_id IS NULL THEN
    NEW.path := inventory.category_label(NEW.id);
    RETURN NEW;
  END IF;
  SELECT path INTO v_parent_path
    FROM inventory.product_categories
   WHERE company_id = NEW.company_id AND id = NEW.parent_id;
  IF TG_OP = 'UPDATE' AND v_parent_path <@ OLD.path THEN
    RAISE EXCEPTION 'category % cannot be placed below its own descendant', NEW.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_product_categories__no_cycle',
            TABLE = 'product_categories', SCHEMA = 'inventory';
  END IF;
  NEW.path := v_parent_path || inventory.category_label(NEW.id);
  RETURN NEW;
END;
$$;

CREATE FUNCTION inventory.move_category_subtree()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, public, inventory
AS $$
BEGIN
  IF NEW.path IS DISTINCT FROM OLD.path THEN
    UPDATE inventory.product_categories
       SET path = NEW.path || subpath(path, nlevel(OLD.path))
     WHERE company_id = NEW.company_id AND path <@ OLD.path AND id <> NEW.id;
  END IF;
  RETURN NULL;
END;
$$;
REVOKE EXECUTE ON FUNCTION inventory.guard_category_path(), inventory.move_category_subtree() FROM PUBLIC;

CREATE TRIGGER trg_category_path
  BEFORE INSERT OR UPDATE OF parent_id ON inventory.product_categories
  FOR EACH ROW EXECUTE FUNCTION inventory.guard_category_path();
CREATE TRIGGER trg_category_subtree
  AFTER UPDATE OF parent_id ON inventory.product_categories
  FOR EACH ROW EXECUTE FUNCTION inventory.move_category_subtree();

-- ------------------------------------------------------------------------------- products
CREATE TABLE inventory.products (
  id                   uuid        NOT NULL DEFAULT uuidv7(),
  company_id           uuid        NOT NULL,
  code                 text        NOT NULL,
  name                 text        NOT NULL,
  description          text        NULL,
  category_id          uuid        NOT NULL,
  product_type         text        NOT NULL,
  base_uom_id          uuid        NOT NULL,
  purchase_uom_id      uuid        NULL,
  sales_uom_id         uuid        NULL,
  is_purchasable       boolean     NOT NULL DEFAULT true,
  is_sellable          boolean     NOT NULL DEFAULT true,
  sales_tax_code_id    uuid        NULL,
  purchase_tax_code_id uuid        NULL,
  has_variants         boolean     NOT NULL DEFAULT false,
  status               text        NOT NULL DEFAULT 'ACTIVE',
  created_at           timestamptz NOT NULL DEFAULT now(),
  created_by           uuid        NULL,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           uuid        NULL,
  version              integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_products PRIMARY KEY (id),
  CONSTRAINT uq_products__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_products__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_products__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_products__product_categories
    FOREIGN KEY (company_id, category_id) REFERENCES inventory.product_categories (company_id, id),
  CONSTRAINT fk_products__uoms_base FOREIGN KEY (base_uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_products__uoms_purchase FOREIGN KEY (purchase_uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_products__uoms_sales FOREIGN KEY (sales_uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_products__tax_codes_sales
    FOREIGN KEY (company_id, sales_tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_products__tax_codes_purchase
    FOREIGN KEY (company_id, purchase_tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT ck_products__code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9._/-]{0,39}$'),
  CONSTRAINT ck_products__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 200),
  CONSTRAINT ck_products__description CHECK (description IS NULL OR length(description) <= 4000),
  CONSTRAINT ck_products__product_type CHECK (product_type IN ('STOCKABLE', 'CONSUMABLE', 'SERVICE')),
  CONSTRAINT ck_products__status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_products__version CHECK (version >= 0)
);
CREATE INDEX ix_products__company_id_category_id ON inventory.products (company_id, category_id);
CREATE INDEX ix_products__base_uom_id ON inventory.products (base_uom_id);
CREATE INDEX ix_products__purchase_uom_id ON inventory.products (purchase_uom_id);
CREATE INDEX ix_products__sales_uom_id ON inventory.products (sales_uom_id);
CREATE INDEX ix_products__company_id_sales_tax_code_id ON inventory.products (company_id, sales_tax_code_id);
CREATE INDEX ix_products__company_id_purchase_tax_code_id ON inventory.products (company_id, purchase_tax_code_id);
CREATE INDEX ix_products__name_trgm ON inventory.products USING gin (name gin_trgm_ops);
SELECT platform.enable_company_rls('inventory.products');

CREATE TABLE inventory.product_uom_conversions (
  id             uuid           NOT NULL DEFAULT uuidv7(),
  company_id     uuid           NOT NULL,
  product_id     uuid           NOT NULL,
  uom_id         uuid           NOT NULL,
  factor_to_base numeric(24,12) NOT NULL,
  created_at     timestamptz    NOT NULL DEFAULT now(),
  created_by     uuid           NULL,
  updated_at     timestamptz    NOT NULL DEFAULT now(),
  updated_by     uuid           NULL,
  version        integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_product_uom_conversions PRIMARY KEY (id),
  CONSTRAINT uq_product_uom_conversions__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_product_uom_conversions__product_id_uom_id UNIQUE (product_id, uom_id),
  CONSTRAINT fk_product_uom_conversions__products
    FOREIGN KEY (company_id, product_id) REFERENCES inventory.products (company_id, id),
  CONSTRAINT fk_product_uom_conversions__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT ck_product_uom_conversions__factor_positive CHECK (factor_to_base > 0),
  CONSTRAINT ck_product_uom_conversions__version CHECK (version >= 0)
);
CREATE INDEX ix_product_uom_conversions__company_id_product_id ON inventory.product_uom_conversions (company_id, product_id);
CREATE INDEX ix_product_uom_conversions__uom_id ON inventory.product_uom_conversions (uom_id);
SELECT platform.enable_company_rls('inventory.product_uom_conversions');

-- --------------------------------------------------------------------- attributes, variants
CREATE TABLE inventory.product_attributes (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  code       text        NOT NULL,
  name       text        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_product_attributes PRIMARY KEY (id),
  CONSTRAINT uq_product_attributes__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_product_attributes__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_product_attributes__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_product_attributes__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_product_attributes__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_product_attributes__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('inventory.product_attributes');

CREATE TABLE inventory.product_attribute_values (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  company_id   uuid        NOT NULL,
  attribute_id uuid        NOT NULL,
  code         text        NOT NULL,
  name         text        NOT NULL,
  sort_order   integer     NOT NULL DEFAULT 0,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid        NULL,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   uuid        NULL,
  version      integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_product_attribute_values PRIMARY KEY (id),
  CONSTRAINT uq_product_attribute_values__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_product_attribute_values__company_id_attribute_id_id UNIQUE (company_id, attribute_id, id),
  CONSTRAINT uq_product_attribute_values__attribute_id_code UNIQUE (attribute_id, code),
  CONSTRAINT fk_product_attribute_values__product_attributes
    FOREIGN KEY (company_id, attribute_id) REFERENCES inventory.product_attributes (company_id, id),
  CONSTRAINT ck_product_attribute_values__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_product_attribute_values__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_product_attribute_values__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('inventory.product_attribute_values');

CREATE TABLE inventory.product_variants (
  id                  uuid          NOT NULL DEFAULT uuidv7(),
  company_id          uuid          NOT NULL,
  product_id          uuid          NOT NULL,
  sku                 text          NOT NULL,
  barcode             text          NULL,
  name                text          NOT NULL,
  is_default          boolean       NOT NULL DEFAULT false,
  status              text          NOT NULL DEFAULT 'ACTIVE',
  weight_kg           numeric(12,4) NULL,
  -- Sorted "attributeId=valueId;…" of the variant's attribute values ('' for the default variant).
  attribute_signature text          NOT NULL DEFAULT '',
  created_at          timestamptz   NOT NULL DEFAULT now(),
  created_by          uuid          NULL,
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  updated_by          uuid          NULL,
  version             integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_product_variants PRIMARY KEY (id),
  CONSTRAINT uq_product_variants__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_product_variants__company_id_sku UNIQUE (company_id, sku),
  CONSTRAINT uq_product_variants__company_id_barcode UNIQUE (company_id, barcode),
  CONSTRAINT uq_product_variants__product_id_attribute_signature UNIQUE (product_id, attribute_signature),
  CONSTRAINT fk_product_variants__products
    FOREIGN KEY (company_id, product_id) REFERENCES inventory.products (company_id, id),
  CONSTRAINT ck_product_variants__sku_format CHECK (sku ~ '^[A-Z0-9][A-Z0-9._/-]{0,39}$'),
  CONSTRAINT ck_product_variants__barcode_format CHECK (barcode IS NULL OR barcode ~ '^[0-9A-Za-z-]{4,48}$'),
  CONSTRAINT ck_product_variants__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 300),
  CONSTRAINT ck_product_variants__status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_product_variants__weight CHECK (weight_kg IS NULL OR weight_kg >= 0),
  CONSTRAINT ck_product_variants__version CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_product_variants__product_id_default ON inventory.product_variants (product_id) WHERE is_default;
CREATE INDEX ix_product_variants__company_id_product_id ON inventory.product_variants (company_id, product_id);
CREATE INDEX ix_product_variants__sku_trgm ON inventory.product_variants USING gin (sku gin_trgm_ops);
CREATE INDEX ix_product_variants__name_trgm ON inventory.product_variants USING gin (name gin_trgm_ops);
SELECT platform.enable_company_rls('inventory.product_variants');

CREATE TABLE inventory.variant_attribute_values (
  variant_id   uuid NOT NULL,
  attribute_id uuid NOT NULL,
  value_id     uuid NOT NULL,
  company_id   uuid NOT NULL,
  CONSTRAINT pk_variant_attribute_values PRIMARY KEY (variant_id, attribute_id),
  CONSTRAINT fk_variant_attribute_values__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_variant_attribute_values__product_attribute_values
    FOREIGN KEY (company_id, attribute_id, value_id)
    REFERENCES inventory.product_attribute_values (company_id, attribute_id, id)
);
CREATE INDEX ix_variant_attribute_values__company_id_attribute_id_value_id
  ON inventory.variant_attribute_values (company_id, attribute_id, value_id);
SELECT platform.enable_company_rls('inventory.variant_attribute_values');

-- ------------------------------------------------------------------- warehouses, locations
CREATE TABLE inventory.warehouses (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  company_id    uuid        NOT NULL,
  branch_id     uuid        NOT NULL,
  code          text        NOT NULL,
  name          text        NOT NULL,
  address_line1 text        NULL,
  address_line2 text        NULL,
  city          text        NULL,
  region        text        NULL,
  postal_code   text        NULL,
  country_code  char(2)     NULL,
  is_active     boolean     NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid        NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid        NULL,
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_warehouses PRIMARY KEY (id),
  CONSTRAINT uq_warehouses__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_warehouses__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_warehouses__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_warehouses__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_warehouses__countries FOREIGN KEY (country_code) REFERENCES org.countries (code),
  CONSTRAINT ck_warehouses__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_warehouses__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_warehouses__version CHECK (version >= 0)
);
CREATE INDEX ix_warehouses__company_id_branch_id ON inventory.warehouses (company_id, branch_id);
CREATE INDEX ix_warehouses__country_code ON inventory.warehouses (country_code);
SELECT platform.enable_company_rls('inventory.warehouses');

CREATE TABLE inventory.locations (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  company_id    uuid        NOT NULL,
  warehouse_id  uuid        NOT NULL,
  code          text        NOT NULL,
  name          text        NOT NULL,
  parent_id     uuid        NULL,
  location_type text        NOT NULL,
  is_active     boolean     NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid        NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid        NULL,
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_locations PRIMARY KEY (id),
  CONSTRAINT uq_locations__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_locations__company_id_warehouse_id_id UNIQUE (company_id, warehouse_id, id),
  CONSTRAINT uq_locations__warehouse_id_code UNIQUE (warehouse_id, code),
  CONSTRAINT fk_locations__warehouses FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  -- The parent lies in the same warehouse (trg_location_tree, DATABASE.md §8.2, enforced by the key).
  CONSTRAINT fk_locations__locations_parent
    FOREIGN KEY (company_id, warehouse_id, parent_id) REFERENCES inventory.locations (company_id, warehouse_id, id),
  CONSTRAINT ck_locations__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_locations__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_locations__location_type
    CHECK (location_type IN ('INTERNAL', 'RECEIVING', 'SHIPPING', 'QUARANTINE', 'TRANSIT')),
  CONSTRAINT ck_locations__not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
  CONSTRAINT ck_locations__version CHECK (version >= 0)
);
-- Exactly one TRANSIT location per warehouse (the target of two-step transfers).
CREATE UNIQUE INDEX uq_locations__warehouse_id_transit ON inventory.locations (warehouse_id) WHERE location_type = 'TRANSIT';
CREATE INDEX ix_locations__company_id_warehouse_id_parent_id ON inventory.locations (company_id, warehouse_id, parent_id);
SELECT platform.enable_company_rls('inventory.locations');

CREATE FUNCTION inventory.guard_location_tree()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, inventory
AS $$
DECLARE
  v_cycle boolean;
BEGIN
  IF NEW.parent_id IS NULL OR (TG_OP = 'UPDATE' AND NEW.parent_id IS NOT DISTINCT FROM OLD.parent_id) THEN
    RETURN NEW;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('inventory.locations'), hashtext(NEW.warehouse_id::text));
  WITH RECURSIVE ancestors (id, parent_id, depth) AS (
    SELECT l.id, l.parent_id, 1 FROM inventory.locations l
     WHERE l.company_id = NEW.company_id AND l.id = NEW.parent_id
    UNION ALL
    SELECT l.id, l.parent_id, a.depth + 1 FROM inventory.locations l
      JOIN ancestors a ON l.company_id = NEW.company_id AND l.id = a.parent_id
     WHERE a.depth < 1000
  )
  SELECT EXISTS (SELECT 1 FROM ancestors WHERE id = NEW.id) INTO v_cycle;
  IF v_cycle THEN
    RAISE EXCEPTION 'location % cannot be placed below its own descendant', NEW.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_locations__no_cycle', TABLE = 'locations', SCHEMA = 'inventory';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION inventory.guard_location_tree() FROM PUBLIC;
CREATE TRIGGER trg_location_tree
  BEFORE INSERT OR UPDATE OF parent_id ON inventory.locations
  FOR EACH ROW EXECUTE FUNCTION inventory.guard_location_tree();

-- --------------------------------------------------------------------------- reason codes
CREATE TABLE inventory.reason_codes (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  code       text        NOT NULL,
  name       text        NOT NULL,
  applies_to text        NOT NULL,
  is_active  boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_reason_codes PRIMARY KEY (id),
  CONSTRAINT uq_reason_codes__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_reason_codes__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_reason_codes__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_reason_codes__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_reason_codes__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_reason_codes__applies_to CHECK (applies_to IN ('ADJUSTMENT', 'SCRAP', 'COUNT')),
  CONSTRAINT ck_reason_codes__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('inventory.reason_codes');

-- ------------------------------------------------------------------------- stock movements
CREATE TABLE inventory.stock_movements (
  id                  uuid        NOT NULL DEFAULT uuidv7(),
  company_id          uuid        NOT NULL,
  number              text        NULL,
  movement_type       text        NOT NULL,
  status              text        NOT NULL DEFAULT 'DRAFT',
  movement_date       date        NOT NULL,
  warehouse_id        uuid        NOT NULL,
  dest_warehouse_id   uuid        NULL,
  -- Owned by Partners (no FK: inventory does not depend on partners, DATABASE.md §2.5).
  partner_id          uuid        NULL,
  reason_code_id      uuid        NULL,
  source_module       text        NULL,
  source_type         text        NULL,
  source_id           uuid        NULL,
  source_number       text        NULL,
  reversal_of_id      uuid        NULL,
  related_movement_id uuid        NULL,
  posted_at           timestamptz NULL,
  posted_by           uuid        NULL,
  notes               text        NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid        NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid        NULL,
  version             integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_stock_movements PRIMARY KEY (id),
  CONSTRAINT uq_stock_movements__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_stock_movements__company_id_number UNIQUE (company_id, number),
  CONSTRAINT uq_stock_movements__reversal_of_id UNIQUE (reversal_of_id),
  CONSTRAINT fk_stock_movements__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_stock_movements__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_stock_movements__warehouses_dest
    FOREIGN KEY (company_id, dest_warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_stock_movements__reason_codes
    FOREIGN KEY (company_id, reason_code_id) REFERENCES inventory.reason_codes (company_id, id),
  CONSTRAINT fk_stock_movements__stock_movements_reversal
    FOREIGN KEY (company_id, reversal_of_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT fk_stock_movements__stock_movements_related
    FOREIGN KEY (company_id, related_movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT ck_stock_movements__movement_type CHECK (movement_type IN (
    'OPENING', 'PURCHASE_RECEIPT', 'PURCHASE_RETURN', 'SALES_ISSUE', 'SALES_RETURN', 'TRANSFER', 'TRANSFER_SHIP',
    'TRANSFER_RECEIVE', 'ADJUSTMENT', 'SCRAP', 'COUNT_ADJUSTMENT', 'REVERSAL')),
  CONSTRAINT ck_stock_movements__status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_stock_movements__posted_has_number
    CHECK ((status = 'POSTED') = (number IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_stock_movements__reason_required
    CHECK (movement_type NOT IN ('ADJUSTMENT', 'SCRAP', 'COUNT_ADJUSTMENT') OR reason_code_id IS NOT NULL),
  CONSTRAINT ck_stock_movements__reversal_link CHECK ((movement_type = 'REVERSAL') = (reversal_of_id IS NOT NULL)),
  CONSTRAINT ck_stock_movements__dest_warehouse
    CHECK (dest_warehouse_id IS NULL OR movement_type IN ('TRANSFER', 'TRANSFER_SHIP', 'REVERSAL')),
  CONSTRAINT ck_stock_movements__source
    CHECK ((source_module IS NULL) = (source_type IS NULL) AND (source_module IS NULL) = (source_id IS NULL)),
  CONSTRAINT ck_stock_movements__notes CHECK (notes IS NULL OR length(notes) <= 2000),
  CONSTRAINT ck_stock_movements__version CHECK (version >= 0)
);
CREATE INDEX ix_stock_movements__company_id_source ON inventory.stock_movements (company_id, source_module, source_type, source_id);
CREATE INDEX ix_stock_movements__company_id_movement_date ON inventory.stock_movements (company_id, movement_date);
CREATE INDEX ix_stock_movements__company_id_warehouse_id ON inventory.stock_movements (company_id, warehouse_id);
CREATE INDEX ix_stock_movements__company_id_dest_warehouse_id ON inventory.stock_movements (company_id, dest_warehouse_id);
CREATE INDEX ix_stock_movements__company_id_reason_code_id ON inventory.stock_movements (company_id, reason_code_id);
CREATE INDEX ix_stock_movements__company_id_related_movement_id ON inventory.stock_movements (company_id, related_movement_id);
CREATE INDEX ix_stock_movements__company_id_drafts ON inventory.stock_movements (company_id, created_at) WHERE status = 'DRAFT';
-- A source document (goods receipt, delivery, return) produces at most one live movement.
CREATE UNIQUE INDEX uq_stock_movements__company_id_source
  ON inventory.stock_movements (company_id, source_module, source_type, source_id)
  WHERE source_id IS NOT NULL AND status <> 'CANCELLED' AND movement_type <> 'REVERSAL';
-- A two-step transfer is received once.
CREATE UNIQUE INDEX uq_stock_movements__related_receive
  ON inventory.stock_movements (related_movement_id)
  WHERE movement_type = 'TRANSFER_RECEIVE' AND status <> 'CANCELLED';
SELECT platform.enable_company_rls('inventory.stock_movements');

-- ------------------------------------------------------------------------ stock reservations
CREATE TABLE inventory.stock_reservations (
  id             uuid          NOT NULL DEFAULT uuidv7(),
  company_id     uuid          NOT NULL,
  variant_id     uuid          NOT NULL,
  warehouse_id   uuid          NOT NULL,
  quantity_base  numeric(18,6) NOT NULL,
  source_module  text          NOT NULL,
  source_type    text          NOT NULL,
  source_id      uuid          NOT NULL,
  source_line_id uuid          NOT NULL,
  status         text          NOT NULL DEFAULT 'ACTIVE',
  created_at     timestamptz   NOT NULL DEFAULT now(),
  created_by     uuid          NULL,
  updated_at     timestamptz   NOT NULL DEFAULT now(),
  updated_by     uuid          NULL,
  version        integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_stock_reservations PRIMARY KEY (id),
  CONSTRAINT uq_stock_reservations__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_stock_reservations__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_stock_reservations__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT ck_stock_reservations__quantity CHECK (quantity_base >= 0),
  CONSTRAINT ck_stock_reservations__status CHECK (status IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
  CONSTRAINT ck_stock_reservations__closed_is_zero CHECK (status = 'ACTIVE' OR quantity_base = 0),
  CONSTRAINT ck_stock_reservations__version CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_stock_reservations__active_source_line
  ON inventory.stock_reservations (company_id, source_module, source_line_id, warehouse_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_stock_reservations__company_id_variant_id_warehouse_id
  ON inventory.stock_reservations (company_id, variant_id, warehouse_id);
CREATE INDEX ix_stock_reservations__company_id_warehouse_id ON inventory.stock_reservations (company_id, warehouse_id);
CREATE INDEX ix_stock_reservations__company_id_source ON inventory.stock_reservations (company_id, source_module, source_type, source_id);
SELECT platform.enable_company_rls('inventory.stock_reservations');

CREATE TABLE inventory.stock_movement_lines (
  id                       uuid          NOT NULL DEFAULT uuidv7(),
  company_id               uuid          NOT NULL,
  movement_id              uuid          NOT NULL,
  line_no                  integer       NOT NULL,
  variant_id               uuid          NOT NULL,
  from_location_id         uuid          NULL,
  to_location_id           uuid          NULL,
  quantity                 numeric(18,6) NOT NULL,
  uom_id                   uuid          NOT NULL,
  quantity_base            numeric(18,6) NOT NULL,
  unit_cost_base           numeric(19,6) NULL,
  reference_unit_cost_base numeric(19,6) NULL,
  -- A sales issue line that consumes its own reservation (INV-2).
  reservation_id           uuid          NULL,
  source_line_id           uuid          NULL,
  reversal_of_line_id      uuid          NULL,
  created_at               timestamptz   NOT NULL DEFAULT now(),
  created_by               uuid          NULL,
  updated_at               timestamptz   NOT NULL DEFAULT now(),
  updated_by               uuid          NULL,
  version                  integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_stock_movement_lines PRIMARY KEY (id),
  CONSTRAINT uq_stock_movement_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_stock_movement_lines__movement_id_line_no UNIQUE (movement_id, line_no),
  CONSTRAINT fk_stock_movement_lines__stock_movements
    FOREIGN KEY (company_id, movement_id) REFERENCES inventory.stock_movements (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_stock_movement_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_stock_movement_lines__locations_from
    FOREIGN KEY (company_id, from_location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT fk_stock_movement_lines__locations_to
    FOREIGN KEY (company_id, to_location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT fk_stock_movement_lines__uoms FOREIGN KEY (uom_id) REFERENCES inventory.uoms (id),
  CONSTRAINT fk_stock_movement_lines__stock_reservations
    FOREIGN KEY (company_id, reservation_id) REFERENCES inventory.stock_reservations (company_id, id),
  CONSTRAINT fk_stock_movement_lines__stock_movement_lines_reversal
    FOREIGN KEY (company_id, reversal_of_line_id) REFERENCES inventory.stock_movement_lines (company_id, id),
  CONSTRAINT ck_stock_movement_lines__line_no CHECK (line_no > 0),
  CONSTRAINT ck_stock_movement_lines__quantity CHECK (quantity > 0),
  CONSTRAINT ck_stock_movement_lines__quantity_base CHECK (quantity_base > 0),
  CONSTRAINT ck_stock_movement_lines__unit_cost CHECK (unit_cost_base IS NULL OR unit_cost_base >= 0),
  CONSTRAINT ck_stock_movement_lines__reference_unit_cost
    CHECK (reference_unit_cost_base IS NULL OR reference_unit_cost_base >= 0),
  CONSTRAINT ck_stock_movement_lines__location_present CHECK (from_location_id IS NOT NULL OR to_location_id IS NOT NULL),
  CONSTRAINT ck_stock_movement_lines__locations_differ CHECK (from_location_id IS DISTINCT FROM to_location_id),
  CONSTRAINT ck_stock_movement_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_stock_movement_lines__company_id_movement_id ON inventory.stock_movement_lines (company_id, movement_id);
CREATE INDEX ix_stock_movement_lines__company_id_variant_id ON inventory.stock_movement_lines (company_id, variant_id);
CREATE INDEX ix_stock_movement_lines__company_id_from_location_id ON inventory.stock_movement_lines (company_id, from_location_id);
CREATE INDEX ix_stock_movement_lines__company_id_to_location_id ON inventory.stock_movement_lines (company_id, to_location_id);
CREATE INDEX ix_stock_movement_lines__uom_id ON inventory.stock_movement_lines (uom_id);
CREATE INDEX ix_stock_movement_lines__company_id_reservation_id ON inventory.stock_movement_lines (company_id, reservation_id);
CREATE INDEX ix_stock_movement_lines__company_id_reversal_of_line_id ON inventory.stock_movement_lines (company_id, reversal_of_line_id);
SELECT platform.enable_company_rls('inventory.stock_movement_lines');

-- trg_stock_movement_posted_immutable (DATABASE.md §8.2): posted and cancelled movements and their
-- lines never change; only a DRAFT may be edited, posted, cancelled or deleted.
CREATE FUNCTION inventory.guard_movement_immutable()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, inventory
AS $$
BEGIN
  IF OLD.status <> 'DRAFT' THEN
    RAISE EXCEPTION 'stock movement % is % and cannot be changed', OLD.id, OLD.status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_stock_movements__immutable',
            TABLE = 'stock_movements', SCHEMA = 'inventory';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE FUNCTION inventory.guard_movement_line_immutable()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, inventory
AS $$
DECLARE
  v_status text;
  v_movement uuid := CASE WHEN TG_OP = 'INSERT' THEN NEW.movement_id ELSE OLD.movement_id END;
BEGIN
  SELECT status INTO v_status FROM inventory.stock_movements WHERE id = v_movement;
  -- A missing parent means the line is being removed by the cascade of a draft delete.
  IF v_status IS NOT NULL AND v_status <> 'DRAFT' THEN
    RAISE EXCEPTION 'lines of stock movement % (%) cannot be changed', v_movement, v_status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_stock_movement_lines__immutable',
            TABLE = 'stock_movement_lines', SCHEMA = 'inventory';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
REVOKE EXECUTE ON FUNCTION inventory.guard_movement_immutable(), inventory.guard_movement_line_immutable() FROM PUBLIC;

CREATE TRIGGER trg_stock_movement_posted_immutable
  BEFORE UPDATE OR DELETE ON inventory.stock_movements
  FOR EACH ROW EXECUTE FUNCTION inventory.guard_movement_immutable();
CREATE TRIGGER trg_stock_movement_lines_posted_immutable
  BEFORE INSERT OR UPDATE OR DELETE ON inventory.stock_movement_lines
  FOR EACH ROW EXECUTE FUNCTION inventory.guard_movement_line_immutable();

-- ------------------------------------------------------------- inventory ledger (append-only)
CREATE TABLE inventory.inventory_transactions (
  id               uuid          NOT NULL DEFAULT uuidv7(),
  company_id       uuid          NOT NULL,
  movement_id      uuid          NOT NULL,
  movement_line_id uuid          NOT NULL,
  movement_type    text          NOT NULL,
  transaction_date date          NOT NULL,
  variant_id       uuid          NOT NULL,
  warehouse_id     uuid          NOT NULL,
  location_id      uuid          NOT NULL,
  quantity_base    numeric(18,6) NOT NULL,
  unit_cost_base   numeric(19,6) NOT NULL,
  value_base       numeric(19,4) NOT NULL,
  created_at       timestamptz   NOT NULL DEFAULT now(),
  created_by       uuid          NULL,
  CONSTRAINT pk_inventory_transactions PRIMARY KEY (id),
  CONSTRAINT fk_inventory_transactions__stock_movements
    FOREIGN KEY (company_id, movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT fk_inventory_transactions__stock_movement_lines
    FOREIGN KEY (company_id, movement_line_id) REFERENCES inventory.stock_movement_lines (company_id, id),
  CONSTRAINT fk_inventory_transactions__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_inventory_transactions__locations
    FOREIGN KEY (company_id, warehouse_id, location_id) REFERENCES inventory.locations (company_id, warehouse_id, id),
  CONSTRAINT ck_inventory_transactions__movement_type CHECK (movement_type IN (
    'OPENING', 'PURCHASE_RECEIPT', 'PURCHASE_RETURN', 'SALES_ISSUE', 'SALES_RETURN', 'TRANSFER', 'TRANSFER_SHIP',
    'TRANSFER_RECEIVE', 'ADJUSTMENT', 'SCRAP', 'COUNT_ADJUSTMENT', 'REVERSAL')),
  CONSTRAINT ck_inventory_transactions__quantity_nonzero CHECK (quantity_base <> 0),
  CONSTRAINT ck_inventory_transactions__unit_cost CHECK (unit_cost_base >= 0),
  CONSTRAINT ck_inventory_transactions__value_sign CHECK (sign(quantity_base) = sign(value_base) OR value_base = 0)
);
CREATE INDEX ix_inventory_transactions__company_id_variant_id_location_id_created_at
  ON inventory.inventory_transactions (company_id, variant_id, location_id, created_at);
CREATE INDEX ix_inventory_transactions__company_id_transaction_date ON inventory.inventory_transactions (company_id, transaction_date);
CREATE INDEX ix_inventory_transactions__company_id_movement_id ON inventory.inventory_transactions (company_id, movement_id);
CREATE INDEX ix_inventory_transactions__company_id_movement_line_id
  ON inventory.inventory_transactions (company_id, movement_line_id);
CREATE INDEX ix_inventory_transactions__company_id_warehouse_id_location_id
  ON inventory.inventory_transactions (company_id, warehouse_id, location_id);
SELECT platform.enable_company_rls('inventory.inventory_transactions');

REVOKE UPDATE, DELETE, TRUNCATE ON inventory.inventory_transactions FROM erp_app;

CREATE FUNCTION inventory.guard_ledger_append_only()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  RAISE EXCEPTION 'the inventory ledger is append-only; correct it with a reversal'
    USING ERRCODE = 'insufficient_privilege';
END;
$$;
REVOKE EXECUTE ON FUNCTION inventory.guard_ledger_append_only() FROM PUBLIC;
CREATE TRIGGER trg_inventory_transactions_append_only
  BEFORE UPDATE OR DELETE ON inventory.inventory_transactions
  FOR EACH ROW EXECUTE FUNCTION inventory.guard_ledger_append_only();

-- --------------------------------------------------------- derived balances and valuation
CREATE TABLE inventory.stock_balances (
  company_id   uuid          NOT NULL,
  variant_id   uuid          NOT NULL,
  location_id  uuid          NOT NULL,
  warehouse_id uuid          NOT NULL,
  on_hand      numeric(18,6) NOT NULL DEFAULT 0,
  updated_at   timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_stock_balances PRIMARY KEY (company_id, variant_id, location_id),
  CONSTRAINT fk_stock_balances__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_stock_balances__locations
    FOREIGN KEY (company_id, warehouse_id, location_id) REFERENCES inventory.locations (company_id, warehouse_id, id),
  CONSTRAINT ck_stock_balances__on_hand CHECK (on_hand >= 0)
);
CREATE INDEX ix_stock_balances__company_id_warehouse_id_variant_id ON inventory.stock_balances (company_id, warehouse_id, variant_id);
CREATE INDEX ix_stock_balances__company_id_location_id ON inventory.stock_balances (company_id, location_id);
SELECT platform.enable_company_rls('inventory.stock_balances');

CREATE TABLE inventory.warehouse_stock (
  company_id   uuid          NOT NULL,
  variant_id   uuid          NOT NULL,
  warehouse_id uuid          NOT NULL,
  on_hand      numeric(18,6) NOT NULL DEFAULT 0,
  reserved     numeric(18,6) NOT NULL DEFAULT 0,
  updated_at   timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_warehouse_stock PRIMARY KEY (company_id, variant_id, warehouse_id),
  CONSTRAINT fk_warehouse_stock__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_warehouse_stock__warehouses
    FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT ck_warehouse_stock__on_hand CHECK (on_hand >= 0),
  CONSTRAINT ck_warehouse_stock__reserved CHECK (reserved >= 0),
  CONSTRAINT ck_warehouse_stock__reserved_covered CHECK (reserved <= on_hand)
);
CREATE INDEX ix_warehouse_stock__company_id_warehouse_id ON inventory.warehouse_stock (company_id, warehouse_id);
SELECT platform.enable_company_rls('inventory.warehouse_stock');

CREATE TABLE inventory.item_valuations (
  company_id       uuid          NOT NULL,
  variant_id       uuid          NOT NULL,
  quantity_base    numeric(18,6) NOT NULL DEFAULT 0,
  total_value_base numeric(19,4) NOT NULL DEFAULT 0,
  updated_at       timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_item_valuations PRIMARY KEY (company_id, variant_id),
  CONSTRAINT fk_item_valuations__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT ck_item_valuations__quantity CHECK (quantity_base >= 0),
  CONSTRAINT ck_item_valuations__value CHECK (total_value_base >= 0),
  CONSTRAINT ck_item_valuations__no_residual_value CHECK (quantity_base > 0 OR total_value_base = 0)
);
SELECT platform.enable_company_rls('inventory.item_valuations');

-- trg_product_base_uom_locked (DATABASE.md §8.2)
CREATE FUNCTION inventory.guard_product_base_uom()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, inventory
AS $$
BEGIN
  IF NEW.base_uom_id IS DISTINCT FROM OLD.base_uom_id AND EXISTS (
       SELECT 1 FROM inventory.inventory_transactions t
         JOIN inventory.product_variants v ON v.company_id = t.company_id AND v.id = t.variant_id
        WHERE v.company_id = NEW.company_id AND v.product_id = NEW.id) THEN
    RAISE EXCEPTION 'the base unit of product % is locked by its inventory transactions', NEW.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_products__base_uom_locked',
            TABLE = 'products', SCHEMA = 'inventory';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION inventory.guard_product_base_uom() FROM PUBLIC;
CREATE TRIGGER trg_product_base_uom_locked
  BEFORE UPDATE OF base_uom_id ON inventory.products
  FOR EACH ROW EXECUTE FUNCTION inventory.guard_product_base_uom();

-- ------------------------------------------------------------------------ physical counts
CREATE TABLE inventory.stock_counts (
  id                     uuid        NOT NULL DEFAULT uuidv7(),
  company_id             uuid        NOT NULL,
  number                 text        NULL,
  warehouse_id           uuid        NOT NULL,
  count_date             date        NOT NULL,
  status                 text        NOT NULL DEFAULT 'DRAFT',
  reason_code_id         uuid        NULL,
  adjustment_movement_id uuid        NULL,
  notes                  text        NULL,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid        NULL,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid        NULL,
  version                integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_stock_counts PRIMARY KEY (id),
  CONSTRAINT uq_stock_counts__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_stock_counts__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_stock_counts__warehouses FOREIGN KEY (company_id, warehouse_id) REFERENCES inventory.warehouses (company_id, id),
  CONSTRAINT fk_stock_counts__reason_codes
    FOREIGN KEY (company_id, reason_code_id) REFERENCES inventory.reason_codes (company_id, id),
  CONSTRAINT fk_stock_counts__stock_movements
    FOREIGN KEY (company_id, adjustment_movement_id) REFERENCES inventory.stock_movements (company_id, id),
  CONSTRAINT ck_stock_counts__status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'POSTED', 'CANCELLED')),
  CONSTRAINT ck_stock_counts__number CHECK ((status = 'DRAFT' OR status = 'CANCELLED') OR number IS NOT NULL),
  -- A posted count without differences has no adjustment movement.
  CONSTRAINT ck_stock_counts__adjustment_when_posted CHECK (status = 'POSTED' OR adjustment_movement_id IS NULL),
  CONSTRAINT ck_stock_counts__notes CHECK (notes IS NULL OR length(notes) <= 2000),
  CONSTRAINT ck_stock_counts__version CHECK (version >= 0)
);
CREATE INDEX ix_stock_counts__company_id_warehouse_id ON inventory.stock_counts (company_id, warehouse_id);
CREATE INDEX ix_stock_counts__company_id_reason_code_id ON inventory.stock_counts (company_id, reason_code_id);
CREATE INDEX ix_stock_counts__company_id_adjustment_movement_id ON inventory.stock_counts (company_id, adjustment_movement_id);
SELECT platform.enable_company_rls('inventory.stock_counts');

CREATE TABLE inventory.stock_count_lines (
  id                    uuid          NOT NULL DEFAULT uuidv7(),
  company_id            uuid          NOT NULL,
  stock_count_id        uuid          NOT NULL,
  variant_id            uuid          NOT NULL,
  location_id           uuid          NOT NULL,
  system_quantity_base  numeric(18,6) NOT NULL,
  counted_quantity_base numeric(18,6) NULL,
  created_at            timestamptz   NOT NULL DEFAULT now(),
  created_by            uuid          NULL,
  updated_at            timestamptz   NOT NULL DEFAULT now(),
  updated_by            uuid          NULL,
  version               integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_stock_count_lines PRIMARY KEY (id),
  CONSTRAINT uq_stock_count_lines__stock_count_id_variant_id_location_id UNIQUE (stock_count_id, variant_id, location_id),
  CONSTRAINT fk_stock_count_lines__stock_counts
    FOREIGN KEY (company_id, stock_count_id) REFERENCES inventory.stock_counts (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_stock_count_lines__product_variants
    FOREIGN KEY (company_id, variant_id) REFERENCES inventory.product_variants (company_id, id),
  CONSTRAINT fk_stock_count_lines__locations
    FOREIGN KEY (company_id, location_id) REFERENCES inventory.locations (company_id, id),
  CONSTRAINT ck_stock_count_lines__system_quantity CHECK (system_quantity_base >= 0),
  CONSTRAINT ck_stock_count_lines__counted_quantity CHECK (counted_quantity_base IS NULL OR counted_quantity_base >= 0),
  CONSTRAINT ck_stock_count_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_stock_count_lines__company_id_stock_count_id ON inventory.stock_count_lines (company_id, stock_count_id);
CREATE INDEX ix_stock_count_lines__company_id_variant_id ON inventory.stock_count_lines (company_id, variant_id);
CREATE INDEX ix_stock_count_lines__company_id_location_id ON inventory.stock_count_lines (company_id, location_id);
SELECT platform.enable_company_rls('inventory.stock_count_lines');
