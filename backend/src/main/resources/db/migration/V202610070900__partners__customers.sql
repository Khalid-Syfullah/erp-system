-- =====================================================================================
-- Customer profiles (Phase 7, ADR-037; DATABASE.md §5.4): a partner becomes a customer with its
-- profile. The credit limit is in the company's base currency; a customer on hold cannot have
-- orders confirmed without sales.order.override_credit (SAL-2).
-- =====================================================================================

CREATE TABLE partners.customers (
  partner_id          uuid          NOT NULL,
  company_id          uuid          NOT NULL,
  customer_group_id   uuid          NULL,
  -- Constant, so that the typed foreign key below only accepts CUSTOMER groups.
  group_applies_to    text          GENERATED ALWAYS AS ('CUSTOMER') STORED,
  currency_code       char(3)       NOT NULL,
  payment_terms_id    uuid          NULL,
  default_tax_code_id uuid          NULL,
  credit_limit        numeric(19,4) NULL,
  is_on_hold          boolean       NOT NULL DEFAULT false,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  created_by          uuid          NULL,
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  updated_by          uuid          NULL,
  version             integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_customers PRIMARY KEY (partner_id),
  CONSTRAINT uq_customers__company_id_partner_id UNIQUE (company_id, partner_id),
  CONSTRAINT fk_customers__partners
    FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id),
  CONSTRAINT fk_customers__partner_groups
    FOREIGN KEY (company_id, customer_group_id, group_applies_to)
    REFERENCES partners.partner_groups (company_id, id, applies_to),
  CONSTRAINT fk_customers__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_customers__payment_terms
    FOREIGN KEY (company_id, payment_terms_id) REFERENCES org.payment_terms (company_id, id),
  CONSTRAINT fk_customers__tax_codes
    FOREIGN KEY (company_id, default_tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT ck_customers__credit_limit CHECK (credit_limit IS NULL OR credit_limit >= 0),
  CONSTRAINT ck_customers__version CHECK (version >= 0)
);
CREATE INDEX ix_customers__company_id_customer_group_id ON partners.customers (company_id, customer_group_id);
CREATE INDEX ix_customers__currency_code ON partners.customers (currency_code);
CREATE INDEX ix_customers__company_id_payment_terms_id ON partners.customers (company_id, payment_terms_id);
CREATE INDEX ix_customers__company_id_default_tax_code_id ON partners.customers (company_id, default_tax_code_id);
SELECT platform.enable_company_rls('partners.customers');
