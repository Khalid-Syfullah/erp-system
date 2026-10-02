-- =====================================================================================
-- Organization core (ADR-024): reference data plus companies and branches, which
-- Auth (Phase 3) scopes role assignments to. The rest of Org follows in Phase 4.
-- Logical model: DATABASE.md §5.2.
-- =====================================================================================

SELECT platform.setup_module_schema('org');

-- ---------------------------------------------------------------- reference data (global)
CREATE TABLE org.currencies (
  code        char(3)  NOT NULL,
  name        text     NOT NULL,
  minor_units smallint NOT NULL,
  is_active   boolean  NOT NULL DEFAULT true,
  CONSTRAINT pk_currencies PRIMARY KEY (code),
  CONSTRAINT ck_currencies__code_format CHECK (code ~ '^[A-Z]{3}$'),
  CONSTRAINT ck_currencies__name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_currencies__minor_units_range CHECK (minor_units BETWEEN 0 AND 4)
);

CREATE TABLE org.countries (
  code char(2) NOT NULL,
  name text    NOT NULL,
  CONSTRAINT pk_countries PRIMARY KEY (code),
  CONSTRAINT ck_countries__code_format CHECK (code ~ '^[A-Z]{2}$'),
  CONSTRAINT ck_countries__name_not_blank CHECK (btrim(name) <> '')
);

-- Reference data is maintained by migrations only; the runtime role may read it.
REVOKE INSERT, UPDATE, DELETE ON org.currencies, org.countries FROM erp_app;
GRANT SELECT ON org.currencies, org.countries TO erp_reporting;

-- ------------------------------------------------------------------------------ companies
CREATE TABLE org.companies (
  id                      uuid        NOT NULL DEFAULT uuidv7(),
  code                    text        NOT NULL,
  legal_name              text        NOT NULL,
  display_name            text        NOT NULL,
  tax_registration_no     text        NULL,
  registration_no         text        NULL,
  country_code            char(2)     NOT NULL,
  base_currency           char(3)     NOT NULL,
  timezone                text        NOT NULL,
  fiscal_year_start_month smallint    NOT NULL DEFAULT 1,
  address_line1           text        NULL,
  address_line2           text        NULL,
  city                    text        NULL,
  region                  text        NULL,
  postal_code             text        NULL,
  rounding_mode           text        NOT NULL DEFAULT 'HALF_UP',
  tax_rounding            text        NOT NULL DEFAULT 'PER_LINE',
  status                  text        NOT NULL DEFAULT 'ACTIVE',
  created_at              timestamptz NOT NULL DEFAULT now(),
  created_by              uuid        NULL,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  updated_by              uuid        NULL,
  version                 integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_companies PRIMARY KEY (id),
  CONSTRAINT uq_companies__code UNIQUE (code),
  CONSTRAINT fk_companies__countries FOREIGN KEY (country_code) REFERENCES org.countries (code),
  CONSTRAINT fk_companies__currencies FOREIGN KEY (base_currency) REFERENCES org.currencies (code),
  CONSTRAINT ck_companies__code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
  CONSTRAINT ck_companies__legal_name_not_blank CHECK (btrim(legal_name) <> ''),
  CONSTRAINT ck_companies__display_name_not_blank CHECK (btrim(display_name) <> ''),
  CONSTRAINT ck_companies__timezone_not_blank CHECK (btrim(timezone) <> ''),
  CONSTRAINT ck_companies__fiscal_year_start_month CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
  CONSTRAINT ck_companies__rounding_mode CHECK (rounding_mode IN ('HALF_UP', 'HALF_EVEN')),
  CONSTRAINT ck_companies__tax_rounding CHECK (tax_rounding IN ('PER_LINE', 'PER_DOCUMENT')),
  CONSTRAINT ck_companies__status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_companies__version CHECK (version >= 0)
);

CREATE INDEX ix_companies__country_code ON org.companies (country_code);
CREATE INDEX ix_companies__base_currency ON org.companies (base_currency);

-- ------------------------------------------------------------------------------- branches
CREATE TABLE org.branches (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  company_id    uuid        NOT NULL,
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
  CONSTRAINT pk_branches PRIMARY KEY (id),
  CONSTRAINT uq_branches__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_branches__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_branches__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_branches__countries FOREIGN KEY (country_code) REFERENCES org.countries (code),
  CONSTRAINT ck_branches__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_branches__name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_branches__version CHECK (version >= 0)
);

CREATE INDEX ix_branches__country_code ON org.branches (country_code);

SELECT platform.enable_company_rls('org.branches');
