-- =====================================================================================
-- Partners (Phase 6, ADR-007/ADR-036). Logical model: DATABASE.md §5.4. A partner is the shared
-- identity of customers and suppliers; the supplier profile makes it usable by Procurement. The
-- customer profile arrives with Sales (Phase 7). Bank account numbers are field-encrypted
-- (SECURITY.md §7.2); only the last four digits are stored in clear.
-- =====================================================================================

SELECT platform.setup_module_schema('partners');

-- -------------------------------------------------------------------------- partner groups
CREATE TABLE partners.partner_groups (
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
  CONSTRAINT pk_partner_groups PRIMARY KEY (id),
  CONSTRAINT uq_partner_groups__company_id_id UNIQUE (company_id, id),
  -- Target of the typed foreign keys from the profiles (a supplier group must apply to suppliers).
  CONSTRAINT uq_partner_groups__company_id_id_applies_to UNIQUE (company_id, id, applies_to),
  CONSTRAINT uq_partner_groups__company_id_applies_to_code UNIQUE (company_id, applies_to, code),
  CONSTRAINT fk_partner_groups__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_partner_groups__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_partner_groups__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_partner_groups__applies_to CHECK (applies_to IN ('CUSTOMER', 'SUPPLIER')),
  CONSTRAINT ck_partner_groups__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('partners.partner_groups');

-- -------------------------------------------------------------------------------- partners
CREATE TABLE partners.partners (
  id                  uuid        NOT NULL DEFAULT uuidv7(),
  company_id          uuid        NOT NULL,
  code                text        NOT NULL,
  name                text        NOT NULL,
  legal_name          text        NULL,
  partner_type        text        NOT NULL,
  tax_registration_no text        NULL,
  email               citext      NULL,
  phone               text        NULL,
  website             text        NULL,
  status              text        NOT NULL DEFAULT 'ACTIVE',
  notes               text        NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid        NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid        NULL,
  version             integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_partners PRIMARY KEY (id),
  CONSTRAINT uq_partners__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_partners__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_partners__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_partners__code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9._/-]{0,29}$'),
  CONSTRAINT ck_partners__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 200),
  CONSTRAINT ck_partners__legal_name CHECK (legal_name IS NULL OR (btrim(legal_name) <> '' AND length(legal_name) <= 200)),
  CONSTRAINT ck_partners__partner_type CHECK (partner_type IN ('ORGANIZATION', 'INDIVIDUAL')),
  CONSTRAINT ck_partners__tax_registration_no CHECK (tax_registration_no IS NULL OR length(tax_registration_no) <= 50),
  CONSTRAINT ck_partners__email CHECK (email IS NULL OR (length(email) <= 254 AND email ~ '^[^@\s]+@[^@\s]+$')),
  CONSTRAINT ck_partners__phone CHECK (phone IS NULL OR length(phone) <= 40),
  CONSTRAINT ck_partners__website CHECK (website IS NULL OR length(website) <= 200),
  CONSTRAINT ck_partners__status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED')),
  CONSTRAINT ck_partners__notes CHECK (notes IS NULL OR length(notes) <= 4000),
  CONSTRAINT ck_partners__version CHECK (version >= 0)
);
CREATE INDEX ix_partners__name_trgm ON partners.partners USING gin (name gin_trgm_ops);
CREATE INDEX ix_partners__company_id_status ON partners.partners (company_id, status);
SELECT platform.enable_company_rls('partners.partners');

-- ------------------------------------------------------------------------------- addresses
CREATE TABLE partners.partner_addresses (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  company_id   uuid        NOT NULL,
  partner_id   uuid        NOT NULL,
  address_type text        NOT NULL,
  line1        text        NOT NULL,
  line2        text        NULL,
  city         text        NULL,
  region       text        NULL,
  postal_code  text        NULL,
  country_code char(2)     NOT NULL,
  is_default   boolean     NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid        NULL,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   uuid        NULL,
  version      integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_partner_addresses PRIMARY KEY (id),
  CONSTRAINT fk_partner_addresses__partners
    FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_partner_addresses__countries FOREIGN KEY (country_code) REFERENCES org.countries (code),
  CONSTRAINT ck_partner_addresses__address_type CHECK (address_type IN ('BILLING', 'SHIPPING', 'OTHER')),
  CONSTRAINT ck_partner_addresses__line1 CHECK (btrim(line1) <> '' AND length(line1) <= 200),
  CONSTRAINT ck_partner_addresses__line2 CHECK (line2 IS NULL OR length(line2) <= 200),
  CONSTRAINT ck_partner_addresses__city CHECK (city IS NULL OR length(city) <= 100),
  CONSTRAINT ck_partner_addresses__region CHECK (region IS NULL OR length(region) <= 100),
  CONSTRAINT ck_partner_addresses__postal_code CHECK (postal_code IS NULL OR length(postal_code) <= 20),
  CONSTRAINT ck_partner_addresses__version CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_partner_addresses__default ON partners.partner_addresses (partner_id, address_type) WHERE is_default;
CREATE INDEX ix_partner_addresses__company_id_partner_id ON partners.partner_addresses (company_id, partner_id);
CREATE INDEX ix_partner_addresses__country_code ON partners.partner_addresses (country_code);
SELECT platform.enable_company_rls('partners.partner_addresses');

-- -------------------------------------------------------------------------------- contacts
CREATE TABLE partners.partner_contacts (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  partner_id uuid        NOT NULL,
  name       text        NOT NULL,
  email      citext      NULL,
  phone      text        NULL,
  role_title text        NULL,
  is_primary boolean     NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_partner_contacts PRIMARY KEY (id),
  CONSTRAINT fk_partner_contacts__partners
    FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id) ON DELETE CASCADE,
  CONSTRAINT ck_partner_contacts__name CHECK (btrim(name) <> '' AND length(name) <= 200),
  CONSTRAINT ck_partner_contacts__email CHECK (email IS NULL OR (length(email) <= 254 AND email ~ '^[^@\s]+@[^@\s]+$')),
  CONSTRAINT ck_partner_contacts__phone CHECK (phone IS NULL OR length(phone) <= 40),
  CONSTRAINT ck_partner_contacts__role_title CHECK (role_title IS NULL OR length(role_title) <= 100),
  CONSTRAINT ck_partner_contacts__version CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_partner_contacts__primary ON partners.partner_contacts (partner_id) WHERE is_primary;
CREATE INDEX ix_partner_contacts__company_id_partner_id ON partners.partner_contacts (company_id, partner_id);
SELECT platform.enable_company_rls('partners.partner_contacts');

-- --------------------------------------------------------------------------- bank accounts
-- account_number_encrypted / iban_encrypted: FieldEncryptor output (version || nonce || ciphertext
-- || tag) with the associated data 'partners.partner_bank_accounts.<column>:<id>'.
CREATE TABLE partners.partner_bank_accounts (
  id                       uuid        NOT NULL DEFAULT uuidv7(),
  company_id               uuid        NOT NULL,
  partner_id               uuid        NOT NULL,
  bank_name                text        NOT NULL,
  account_holder           text        NOT NULL,
  account_number_encrypted bytea       NOT NULL,
  iban_encrypted           bytea       NULL,
  swift_bic                text        NULL,
  last4                    char(4)     NOT NULL,
  key_version              smallint    NOT NULL,
  currency_code            char(3)     NULL,
  is_default               boolean     NOT NULL DEFAULT false,
  created_at               timestamptz NOT NULL DEFAULT now(),
  created_by               uuid        NULL,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  updated_by               uuid        NULL,
  version                  integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_partner_bank_accounts PRIMARY KEY (id),
  CONSTRAINT fk_partner_bank_accounts__partners
    FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_partner_bank_accounts__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_partner_bank_accounts__bank_name CHECK (btrim(bank_name) <> '' AND length(bank_name) <= 100),
  CONSTRAINT ck_partner_bank_accounts__account_holder CHECK (btrim(account_holder) <> '' AND length(account_holder) <= 200),
  CONSTRAINT ck_partner_bank_accounts__swift_bic CHECK (swift_bic IS NULL OR swift_bic ~ '^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$'),
  CONSTRAINT ck_partner_bank_accounts__last4 CHECK (last4 ~ '^[A-Z0-9]{4}$'),
  CONSTRAINT ck_partner_bank_accounts__key_version CHECK (key_version BETWEEN 1 AND 255),
  CONSTRAINT ck_partner_bank_accounts__version CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_partner_bank_accounts__default ON partners.partner_bank_accounts (partner_id) WHERE is_default;
CREATE INDEX ix_partner_bank_accounts__company_id_partner_id ON partners.partner_bank_accounts (company_id, partner_id);
CREATE INDEX ix_partner_bank_accounts__currency_code ON partners.partner_bank_accounts (currency_code);
SELECT platform.enable_company_rls('partners.partner_bank_accounts');

-- ------------------------------------------------------------------------ supplier profiles
CREATE TABLE partners.suppliers (
  partner_id          uuid        NOT NULL,
  company_id          uuid        NOT NULL,
  supplier_group_id   uuid        NULL,
  -- Constant, so that the typed foreign key below only accepts SUPPLIER groups.
  group_applies_to    text        GENERATED ALWAYS AS ('SUPPLIER') STORED,
  currency_code       char(3)     NOT NULL,
  payment_terms_id    uuid        NULL,
  default_tax_code_id uuid        NULL,
  lead_time_days      integer     NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid        NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid        NULL,
  version             integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_suppliers PRIMARY KEY (partner_id),
  CONSTRAINT uq_suppliers__company_id_partner_id UNIQUE (company_id, partner_id),
  CONSTRAINT fk_suppliers__partners
    FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id),
  CONSTRAINT fk_suppliers__partner_groups
    FOREIGN KEY (company_id, supplier_group_id, group_applies_to)
    REFERENCES partners.partner_groups (company_id, id, applies_to),
  CONSTRAINT fk_suppliers__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_suppliers__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT fk_suppliers__tax_codes
    FOREIGN KEY (company_id, default_tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT ck_suppliers__lead_time_days CHECK (lead_time_days IS NULL OR lead_time_days BETWEEN 0 AND 3650),
  CONSTRAINT ck_suppliers__version CHECK (version >= 0)
);
CREATE INDEX ix_suppliers__company_id_supplier_group_id ON partners.suppliers (company_id, supplier_group_id);
CREATE INDEX ix_suppliers__currency_code ON partners.suppliers (currency_code);
CREATE INDEX ix_suppliers__company_id_payment_terms_id ON partners.suppliers (company_id, payment_terms_id);
CREATE INDEX ix_suppliers__company_id_default_tax_code_id ON partners.suppliers (company_id, default_tax_code_id);
SELECT platform.enable_company_rls('partners.suppliers');
