-- =====================================================================================
-- Organization, Phase 4: departments (company tree, optional branch) and the company
-- reference data used by later modules: exchange rates, tax codes, payment terms.
-- Logical model: DATABASE.md §5.2. All tables are company-scoped (RLS, composite FKs).
-- =====================================================================================

-- ---------------------------------------------------------------------------- departments
CREATE TABLE org.departments (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  code       text        NOT NULL,
  name       text        NOT NULL,
  parent_id  uuid        NULL,
  branch_id  uuid        NULL,
  is_active  boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_departments PRIMARY KEY (id),
  CONSTRAINT uq_departments__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_departments__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_departments__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_departments__departments_parent
    FOREIGN KEY (company_id, parent_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT fk_departments__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT ck_departments__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_departments__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_departments__not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
  CONSTRAINT ck_departments__version CHECK (version >= 0)
);

CREATE INDEX ix_departments__company_id_parent_id ON org.departments (company_id, parent_id);
CREATE INDEX ix_departments__company_id_branch_id ON org.departments (company_id, branch_id);

SELECT platform.enable_company_rls('org.departments');

-- Tree cycle guard (DATABASE.md §8.3). Changes to a company's tree are serialized with a
-- transaction-scoped advisory lock, so two concurrent moves cannot each pass the check and
-- together form a cycle; after the lock, the ancestor walk sees all committed moves.
CREATE FUNCTION org.guard_department_cycle()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, org
AS $$
DECLARE
  v_cycle boolean;
BEGIN
  IF NEW.parent_id IS NULL THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' AND NEW.parent_id IS NOT DISTINCT FROM OLD.parent_id THEN
    RETURN NEW;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('org.departments'), hashtext(NEW.company_id::text));
  WITH RECURSIVE ancestors (id, parent_id, depth) AS (
    SELECT d.id, d.parent_id, 1
      FROM org.departments d
     WHERE d.company_id = NEW.company_id AND d.id = NEW.parent_id
    UNION ALL
    SELECT d.id, d.parent_id, a.depth + 1
      FROM org.departments d
      JOIN ancestors a ON d.company_id = NEW.company_id AND d.id = a.parent_id
     WHERE a.depth < 1000
  )
  SELECT EXISTS (SELECT 1 FROM ancestors WHERE id = NEW.id) INTO v_cycle;
  IF v_cycle THEN
    RAISE EXCEPTION 'department % cannot be placed below its own descendant', NEW.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_departments__no_cycle',
            TABLE = 'departments', SCHEMA = 'org';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION org.guard_department_cycle() FROM PUBLIC;

CREATE TRIGGER trg_departments__no_cycle
  BEFORE INSERT OR UPDATE OF parent_id ON org.departments
  FOR EACH ROW EXECUTE FUNCTION org.guard_department_cycle();

-- ------------------------------------------------------------------------- exchange rates
CREATE TABLE org.exchange_rates (
  id            uuid           NOT NULL DEFAULT uuidv7(),
  company_id    uuid           NOT NULL,
  currency_code char(3)        NOT NULL,
  rate_date     date           NOT NULL,
  rate          numeric(19,10) NOT NULL,
  source        text           NOT NULL DEFAULT 'MANUAL',
  created_at    timestamptz    NOT NULL DEFAULT now(),
  created_by    uuid           NULL,
  updated_at    timestamptz    NOT NULL DEFAULT now(),
  updated_by    uuid           NULL,
  version       integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_exchange_rates PRIMARY KEY (id),
  CONSTRAINT uq_exchange_rates__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_exchange_rates__company_id_currency_code_rate_date UNIQUE (company_id, currency_code, rate_date),
  CONSTRAINT fk_exchange_rates__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_exchange_rates__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_exchange_rates__rate_positive CHECK (rate > 0),
  CONSTRAINT ck_exchange_rates__source CHECK (source IN ('MANUAL', 'IMPORT')),
  CONSTRAINT ck_exchange_rates__version CHECK (version >= 0)
);

-- The unique constraint's index serves the lookup "latest rate_date <= date" per currency.
CREATE INDEX ix_exchange_rates__currency_code ON org.exchange_rates (currency_code);

SELECT platform.enable_company_rls('org.exchange_rates');

-- ------------------------------------------------------------------------------ tax codes
CREATE TABLE org.tax_codes (
  id           uuid         NOT NULL DEFAULT uuidv7(),
  company_id   uuid         NOT NULL,
  code         text         NOT NULL,
  name         text         NOT NULL,
  scope        text         NOT NULL,
  rate_percent numeric(7,4) NOT NULL,
  is_exempt    boolean      NOT NULL DEFAULT false,
  valid_from   date         NULL,
  valid_to     date         NULL,
  is_active    boolean      NOT NULL DEFAULT true,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  created_by   uuid         NULL,
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  updated_by   uuid         NULL,
  version      integer      NOT NULL DEFAULT 0,
  CONSTRAINT pk_tax_codes PRIMARY KEY (id),
  CONSTRAINT uq_tax_codes__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_tax_codes__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_tax_codes__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_tax_codes__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_tax_codes__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_tax_codes__scope CHECK (scope IN ('SALES', 'PURCHASE', 'BOTH')),
  CONSTRAINT ck_tax_codes__rate_percent CHECK (rate_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_tax_codes__exempt_is_zero CHECK (NOT is_exempt OR rate_percent = 0),
  CONSTRAINT ck_tax_codes__validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_tax_codes__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('org.tax_codes');

-- -------------------------------------------------------------------------- payment terms
CREATE TABLE org.payment_terms (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  code       text        NOT NULL,
  name       text        NOT NULL,
  due_days   integer     NOT NULL,
  due_basis  text        NOT NULL DEFAULT 'DOCUMENT_DATE',
  is_active  boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_payment_terms PRIMARY KEY (id),
  CONSTRAINT uq_payment_terms__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_payment_terms__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_payment_terms__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_payment_terms__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_payment_terms__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_payment_terms__due_days CHECK (due_days BETWEEN 0 AND 3650),
  CONSTRAINT ck_payment_terms__due_basis CHECK (due_basis IN ('DOCUMENT_DATE', 'END_OF_MONTH')),
  CONSTRAINT ck_payment_terms__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('org.payment_terms');
