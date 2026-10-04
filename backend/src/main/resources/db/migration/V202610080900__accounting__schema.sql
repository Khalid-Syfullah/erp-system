-- =====================================================================================
-- Accounting (Phase 8, ADR-038). Logical model: DATABASE.md §5.8; triggers §8.1; locks §9.
-- The general ledger is append-only: a journal entry is inserted as DRAFT, its lines are marked
-- posted, then the header flips to POSTED; from then on triggers block every change except the
-- link to its reversal. A deferred constraint trigger re-checks the balance at commit (ACC-1), a
-- period trigger refuses postings outside open periods (ACC-4), and open items, allocations and
-- posted payments and expenses only change in their state and settlement columns.
-- =====================================================================================

SELECT platform.setup_module_schema('accounting');

-- ------------------------------------------------------------------------- generic guards
-- accounting.guard_frozen_document(<column>...): BEFORE UPDATE OR DELETE on a document header. A
-- DRAFT changes freely and may be deleted; any other header changes only the listed columns (plus
-- updated_at, updated_by, version) and is never deleted.
CREATE FUNCTION accounting.guard_frozen_document()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
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
            TABLE = TG_TABLE_NAME, SCHEMA = 'accounting';
  END IF;
  RETURN NEW;
END;
$$;

-- accounting.guard_frozen_lines(<parent table>, <parent id column>): BEFORE INSERT, UPDATE OR DELETE
-- on document lines. While the parent is a DRAFT, lines change freely; otherwise not at all.
CREATE FUNCTION accounting.guard_frozen_lines()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_row    jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  v_status text;
BEGIN
  EXECUTE format('SELECT status FROM accounting.%I WHERE id = $1', TG_ARGV[0])
    INTO v_status USING (v_row ->> TG_ARGV[1])::uuid;
  IF v_status IS NULL OR v_status = 'DRAFT' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  RAISE EXCEPTION 'lines of % % (%) cannot be changed', TG_ARGV[0], v_row ->> TG_ARGV[1], v_status
    USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '__frozen',
          TABLE = TG_TABLE_NAME, SCHEMA = 'accounting';
END;
$$;

-- accounting.guard_append_only(<column>...): BEFORE UPDATE OR DELETE on a subledger table. Rows are
-- never deleted, and an update may change only the listed columns (plus updated_at, updated_by,
-- version).
CREATE FUNCTION accounting.guard_append_only()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_mutable text[] := TG_ARGV || ARRAY['updated_at', 'updated_by', 'version'];
BEGIN
  IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - v_mutable) IS DISTINCT FROM (to_jsonb(OLD) - v_mutable) THEN
    RAISE EXCEPTION '% % cannot be deleted or rewritten', TG_TABLE_NAME, OLD.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '__immutable',
            TABLE = TG_TABLE_NAME, SCHEMA = 'accounting';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION accounting.guard_frozen_document(), accounting.guard_frozen_lines(),
  accounting.guard_append_only() FROM PUBLIC;

-- ------------------------------------------------------------------------------- accounts
CREATE TABLE accounting.accounts (
  id              uuid        NOT NULL DEFAULT uuidv7(),
  company_id      uuid        NOT NULL,
  code            text        NOT NULL,
  name            text        NOT NULL,
  account_type    text        NOT NULL,
  account_subtype text        NOT NULL,
  parent_id       uuid        NULL,
  is_postable     boolean     NOT NULL DEFAULT true,
  -- AR/AP/inventory/GRNI/tax control: system postings only (no manual lines, PRODUCT_SPEC.md §8.1).
  is_control      boolean     NOT NULL DEFAULT false,
  -- Seeded accounts the posting rules rely on: never deactivated (PRODUCT_SPEC.md §8.2).
  is_system       boolean     NOT NULL DEFAULT false,
  -- If set, only postings in this currency (bank accounts in a foreign currency).
  currency_code   char(3)     NULL,
  status          text        NOT NULL DEFAULT 'ACTIVE',
  description     text        NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid        NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid        NULL,
  version         integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_accounts PRIMARY KEY (id),
  CONSTRAINT uq_accounts__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_accounts__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_accounts__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_accounts__accounts FOREIGN KEY (company_id, parent_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT fk_accounts__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_accounts__code_format CHECK (code ~ '^[0-9A-Z.\-]{1,20}$'),
  CONSTRAINT ck_accounts__name_not_blank CHECK (btrim(name) <> '' AND length(name) <= 150),
  CONSTRAINT ck_accounts__account_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
  CONSTRAINT ck_accounts__account_subtype CHECK (
    (account_type = 'ASSET' AND account_subtype IN ('CASH', 'BANK', 'RECEIVABLE', 'INVENTORY', 'PREPAYMENT',
       'FIXED_ASSET', 'ACCUMULATED_DEPRECIATION', 'TAX_RECEIVABLE', 'OTHER_CURRENT_ASSET', 'OTHER_ASSET'))
    OR (account_type = 'LIABILITY' AND account_subtype IN ('PAYABLE', 'GRNI', 'TAX_PAYABLE', 'PAYROLL_LIABILITY',
       'ACCRUED_LIABILITY', 'CUSTOMER_ADVANCE', 'OTHER_CURRENT_LIABILITY', 'LONG_TERM_LIABILITY'))
    OR (account_type = 'EQUITY' AND account_subtype IN ('EQUITY', 'RETAINED_EARNINGS', 'OPENING_BALANCE_EQUITY'))
    OR (account_type = 'REVENUE' AND account_subtype IN ('OPERATING_REVENUE', 'OTHER_INCOME'))
    OR (account_type = 'EXPENSE' AND account_subtype IN ('COST_OF_GOODS_SOLD', 'OPERATING_EXPENSE',
       'PAYROLL_EXPENSE', 'DEPRECIATION', 'FX_GAIN_LOSS', 'OTHER_EXPENSE'))),
  CONSTRAINT ck_accounts__status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_accounts__system_active CHECK (NOT is_system OR status = 'ACTIVE'),
  CONSTRAINT ck_accounts__not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
  CONSTRAINT ck_accounts__description CHECK (description IS NULL OR length(description) <= 500),
  CONSTRAINT ck_accounts__version CHECK (version >= 0)
);
CREATE INDEX ix_accounts__company_id_parent_id ON accounting.accounts (company_id, parent_id);
CREATE INDEX ix_accounts__company_id_account_subtype ON accounting.accounts (company_id, account_subtype);
CREATE INDEX ix_accounts__currency_code ON accounting.accounts (currency_code);
SELECT platform.enable_company_rls('accounting.accounts');

-- An account cannot be placed below its own descendant (DATABASE.md §8.3). The per-company advisory
-- lock serializes concurrent moves so that two of them cannot together form a cycle.
CREATE FUNCTION accounting.guard_account_cycle()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
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
  PERFORM pg_advisory_xact_lock(hashtext('accounting.accounts'), hashtext(NEW.company_id::text));
  WITH RECURSIVE ancestors (id, parent_id, depth) AS (
    SELECT a.id, a.parent_id, 1
      FROM accounting.accounts a
     WHERE a.company_id = NEW.company_id AND a.id = NEW.parent_id
    UNION ALL
    SELECT a.id, a.parent_id, x.depth + 1
      FROM accounting.accounts a
      JOIN ancestors x ON a.company_id = NEW.company_id AND a.id = x.parent_id
     WHERE x.depth < 1000
  )
  SELECT EXISTS (SELECT 1 FROM ancestors WHERE id = NEW.id) INTO v_cycle;
  IF v_cycle THEN
    RAISE EXCEPTION 'account % cannot be placed below its own descendant', NEW.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_accounts__no_cycle',
            TABLE = 'accounts', SCHEMA = 'accounting';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION accounting.guard_account_cycle() FROM PUBLIC;
CREATE TRIGGER trg_accounts__no_cycle
  BEFORE INSERT OR UPDATE OF parent_id ON accounting.accounts
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_account_cycle();

-- ------------------------------------------------------------------------------- settings
CREATE TABLE accounting.settings (
  company_id                           uuid          NOT NULL,
  retained_earnings_account_id         uuid          NOT NULL,
  -- Privileged users (accounting.period.post_soft_closed) may post manual entries into soft-closed periods.
  allow_manual_entries_in_soft_closed  boolean       NOT NULL DEFAULT true,
  -- System entries off by at most this many minor units are balanced on ROUNDING_DIFFERENCE.
  max_rounding_difference_minor_units  integer       NOT NULL DEFAULT 1,
  -- Manual entries of at least this total (base currency) are posted by someone other than their creator.
  manual_entry_approval_threshold_base numeric(19,4) NULL,
  coa_template                         text          NOT NULL DEFAULT 'STANDARD_SME',
  created_at                           timestamptz   NOT NULL DEFAULT now(),
  created_by                           uuid          NULL,
  updated_at                           timestamptz   NOT NULL DEFAULT now(),
  updated_by                           uuid          NULL,
  version                              integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_settings__accounts
    FOREIGN KEY (company_id, retained_earnings_account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT ck_settings__rounding CHECK (max_rounding_difference_minor_units BETWEEN 0 AND 100),
  CONSTRAINT ck_settings__threshold
    CHECK (manual_entry_approval_threshold_base IS NULL OR manual_entry_approval_threshold_base >= 0),
  CONSTRAINT ck_settings__coa_template CHECK (coa_template IN ('STANDARD_SME')),
  CONSTRAINT ck_settings__version CHECK (version >= 0)
);
CREATE INDEX ix_settings__company_id_retained_earnings_account_id
  ON accounting.settings (company_id, retained_earnings_account_id);
SELECT platform.enable_company_rls('accounting.settings');

-- ------------------------------------------------------------------------ account mappings
CREATE TABLE accounting.account_mappings (
  id          uuid        NOT NULL DEFAULT uuidv7(),
  company_id  uuid        NOT NULL,
  mapping_key text        NOT NULL,
  scope_type  text        NOT NULL,
  -- An opaque ID of an upstream entity (category, warehouse, partner group, …): validated by the
  -- application against the owning module's facade; no FK by design (several target tables).
  scope_id    uuid        NULL,
  account_id  uuid        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid        NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid        NULL,
  version     integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_account_mappings PRIMARY KEY (id),
  CONSTRAINT uq_account_mappings__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_account_mappings__key_scope UNIQUE NULLS NOT DISTINCT (company_id, mapping_key, scope_type, scope_id),
  CONSTRAINT fk_account_mappings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_account_mappings__accounts FOREIGN KEY (company_id, account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT ck_account_mappings__mapping_key CHECK (mapping_key IN ('AR_CONTROL', 'AP_CONTROL', 'INVENTORY_ASSET',
    'GRNI', 'COGS', 'SALES_REVENUE', 'SALES_RETURNS', 'PURCHASE_EXPENSE', 'PURCHASE_PRICE_VARIANCE',
    'INVENTORY_ADJUSTMENT', 'INVENTORY_OPENING', 'TAX_OUTPUT', 'TAX_INPUT', 'FX_REALIZED_GAIN', 'FX_REALIZED_LOSS',
    'ROUNDING_DIFFERENCE', 'CUSTOMER_ADVANCE', 'SUPPLIER_ADVANCE', 'SALARY_EXPENSE', 'PAYROLL_DEDUCTION_LIABILITY',
    'EMPLOYER_CONTRIBUTION_EXPENSE', 'EMPLOYER_CONTRIBUTION_LIABILITY', 'SALARIES_PAYABLE')),
  CONSTRAINT ck_account_mappings__scope_type CHECK (scope_type IN ('DEFAULT', 'PRODUCT_CATEGORY', 'WAREHOUSE',
    'PARTNER_GROUP', 'TAX_CODE', 'PAY_COMPONENT', 'DEPARTMENT', 'REASON_CODE')),
  CONSTRAINT ck_account_mappings__scope CHECK ((scope_type = 'DEFAULT') = (scope_id IS NULL)),
  CONSTRAINT ck_account_mappings__version CHECK (version >= 0)
);
CREATE INDEX ix_account_mappings__company_id_account_id ON accounting.account_mappings (company_id, account_id);
SELECT platform.enable_company_rls('accounting.account_mappings');

-- ------------------------------------------------------------------- fiscal years, periods
CREATE TABLE accounting.fiscal_years (
  id               uuid        NOT NULL DEFAULT uuidv7(),
  company_id       uuid        NOT NULL,
  -- Named after the calendar year in which it starts (FiscalYears.label, ADR-035).
  code             text        NOT NULL,
  start_date       date        NOT NULL,
  end_date         date        NOT NULL,
  status           text        NOT NULL DEFAULT 'OPEN',
  closing_entry_id uuid        NULL,
  closed_at        timestamptz NULL,
  closed_by        uuid        NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       uuid        NULL,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  updated_by       uuid        NULL,
  version          integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_fiscal_years PRIMARY KEY (id),
  CONSTRAINT uq_fiscal_years__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_fiscal_years__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_fiscal_years__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_fiscal_years__dates CHECK (end_date > start_date),
  CONSTRAINT ck_fiscal_years__status CHECK (status IN ('OPEN', 'CLOSED')),
  CONSTRAINT ck_fiscal_years__closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
  CONSTRAINT ck_fiscal_years__code CHECK (code ~ '^[0-9]{4}$'),
  CONSTRAINT ck_fiscal_years__version CHECK (version >= 0),
  CONSTRAINT ex_fiscal_years__no_overlap
    EXCLUDE USING gist (company_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
);
SELECT platform.enable_company_rls('accounting.fiscal_years');

CREATE TABLE accounting.periods (
  id             uuid        NOT NULL DEFAULT uuidv7(),
  company_id     uuid        NOT NULL,
  fiscal_year_id uuid        NOT NULL,
  period_no      smallint    NOT NULL,
  start_date     date        NOT NULL,
  end_date       date        NOT NULL,
  status         text        NOT NULL DEFAULT 'OPEN',
  closed_at      timestamptz NULL,
  closed_by      uuid        NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid        NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid        NULL,
  version        integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_periods PRIMARY KEY (id),
  CONSTRAINT uq_periods__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_periods__fiscal_year_id_period_no UNIQUE (fiscal_year_id, period_no),
  CONSTRAINT fk_periods__fiscal_years
    FOREIGN KEY (company_id, fiscal_year_id) REFERENCES accounting.fiscal_years (company_id, id),
  CONSTRAINT ck_periods__period_no CHECK (period_no BETWEEN 1 AND 12),
  CONSTRAINT ck_periods__dates CHECK (end_date >= start_date),
  CONSTRAINT ck_periods__status CHECK (status IN ('OPEN', 'SOFT_CLOSED', 'CLOSED')),
  CONSTRAINT ck_periods__closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
  CONSTRAINT ck_periods__version CHECK (version >= 0),
  CONSTRAINT ex_periods__no_overlap
    EXCLUDE USING gist (company_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
);
CREATE INDEX ix_periods__company_id_start_date_end_date ON accounting.periods (company_id, start_date, end_date);
SELECT platform.enable_company_rls('accounting.periods');

-- ------------------------------------------------------------------------------- journals
CREATE TABLE accounting.journals (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  company_id   uuid        NOT NULL,
  code         text        NOT NULL,
  name         text        NOT NULL,
  journal_type text        NOT NULL,
  is_system    boolean     NOT NULL DEFAULT false,
  is_active    boolean     NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid        NULL,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   uuid        NULL,
  version      integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_journals PRIMARY KEY (id),
  CONSTRAINT uq_journals__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_journals__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_journals__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_journals__code CHECK (code ~ '^[A-Z0-9]{2,10}$'),
  CONSTRAINT ck_journals__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_journals__journal_type CHECK (journal_type IN ('GENERAL', 'SALES', 'PURCHASE', 'CASH', 'BANK',
    'INVENTORY', 'PAYROLL', 'CLOSING', 'OPENING')),
  CONSTRAINT ck_journals__system_active CHECK (NOT is_system OR is_active),
  CONSTRAINT ck_journals__version CHECK (version >= 0)
);
SELECT platform.enable_company_rls('accounting.journals');

-- ------------------------------------------------------------------------ journal entries
CREATE TABLE accounting.journal_entries (
  id               uuid           NOT NULL DEFAULT uuidv7(),
  company_id       uuid           NOT NULL,
  journal_id       uuid           NOT NULL,
  -- Gapless per (journal, fiscal year), assigned at posting.
  number           text           NULL,
  entry_date       date           NOT NULL,
  period_id        uuid           NOT NULL,
  entry_type       text           NOT NULL,
  status           text           NOT NULL DEFAULT 'DRAFT',
  description      text           NOT NULL,
  currency_code    char(3)        NOT NULL,
  exchange_rate    numeric(19,10) NOT NULL DEFAULT 1,
  source_module    text           NULL,
  source_type      text           NULL,
  source_id        uuid           NULL,
  source_number    text           NULL,
  -- The event the system entry was booked from: one entry per event (ACC-5).
  source_event_id  uuid           NULL,
  reversal_of_id   uuid           NULL,
  reversed_by_id   uuid           NULL,
  total_debit      numeric(19,4)  NOT NULL DEFAULT 0,
  total_credit     numeric(19,4)  NOT NULL DEFAULT 0,
  posted_at        timestamptz    NULL,
  posted_by        uuid           NULL,
  created_at       timestamptz    NOT NULL DEFAULT now(),
  created_by       uuid           NULL,
  updated_at       timestamptz    NOT NULL DEFAULT now(),
  updated_by       uuid           NULL,
  version          integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_journal_entries PRIMARY KEY (id),
  CONSTRAINT uq_journal_entries__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_journal_entries__company_id_journal_id_number UNIQUE (company_id, journal_id, number),
  CONSTRAINT uq_journal_entries__company_id_source_event_id UNIQUE (company_id, source_event_id),
  CONSTRAINT uq_journal_entries__reversal_of_id UNIQUE (reversal_of_id),
  CONSTRAINT uq_journal_entries__reversed_by_id UNIQUE (reversed_by_id),
  CONSTRAINT fk_journal_entries__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_journal_entries__journals
    FOREIGN KEY (company_id, journal_id) REFERENCES accounting.journals (company_id, id),
  CONSTRAINT fk_journal_entries__periods
    FOREIGN KEY (company_id, period_id) REFERENCES accounting.periods (company_id, id),
  CONSTRAINT fk_journal_entries__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_journal_entries__reversal_of
    FOREIGN KEY (company_id, reversal_of_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT fk_journal_entries__reversed_by
    FOREIGN KEY (company_id, reversed_by_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT ck_journal_entries__entry_type CHECK (entry_type IN ('MANUAL', 'SYSTEM', 'REVERSAL', 'OPENING',
    'CLOSING', 'ADJUSTMENT')),
  CONSTRAINT ck_journal_entries__status CHECK (status IN ('DRAFT', 'POSTED')),
  CONSTRAINT ck_journal_entries__posted CHECK ((status = 'POSTED') = (number IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_journal_entries__balanced CHECK (status = 'DRAFT' OR total_debit = total_credit),
  CONSTRAINT ck_journal_entries__totals CHECK (total_debit >= 0 AND total_credit >= 0),
  CONSTRAINT ck_journal_entries__reversal CHECK (entry_type <> 'REVERSAL' OR reversal_of_id IS NOT NULL),
  CONSTRAINT ck_journal_entries__exchange_rate CHECK (exchange_rate > 0),
  CONSTRAINT ck_journal_entries__description CHECK (btrim(description) <> '' AND length(description) <= 500),
  CONSTRAINT ck_journal_entries__version CHECK (version >= 0)
);
CREATE INDEX ix_journal_entries__company_id_entry_date ON accounting.journal_entries (company_id, entry_date);
CREATE INDEX ix_journal_entries__company_id_period_id ON accounting.journal_entries (company_id, period_id);
CREATE INDEX ix_journal_entries__company_id_journal_id ON accounting.journal_entries (company_id, journal_id);
CREATE INDEX ix_journal_entries__source
  ON accounting.journal_entries (company_id, source_module, source_type, source_id);
CREATE INDEX ix_journal_entries__company_id_status ON accounting.journal_entries (company_id, status)
  WHERE status = 'DRAFT';
-- A source document gets at most one system entry (no double posting even with a new event ID).
CREATE UNIQUE INDEX uq_journal_entries__system_source
  ON accounting.journal_entries (company_id, source_module, source_type, source_id)
  WHERE entry_type = 'SYSTEM';
SELECT platform.enable_company_rls('accounting.journal_entries');

ALTER TABLE accounting.fiscal_years
  ADD CONSTRAINT fk_fiscal_years__journal_entries
  FOREIGN KEY (company_id, closing_entry_id) REFERENCES accounting.journal_entries (company_id, id);
CREATE INDEX ix_fiscal_years__company_id_closing_entry_id ON accounting.fiscal_years (company_id, closing_entry_id);

-- ------------------------------------------------------------------------- open items
CREATE TABLE accounting.open_items (
  id                   uuid           NOT NULL DEFAULT uuidv7(),
  company_id           uuid           NOT NULL,
  kind                 text           NOT NULL,
  partner_id           uuid           NOT NULL,
  account_id           uuid           NOT NULL,
  source_module        text           NOT NULL,
  source_type          text           NOT NULL,
  source_id            uuid           NOT NULL,
  document_number      text           NOT NULL,
  document_date        date           NOT NULL,
  due_date             date           NOT NULL,
  currency_code        char(3)        NOT NULL,
  -- Signed from the control account's view: + raises the balance (invoice, bill), − lowers it
  -- (credit note, debit note, unapplied payment).
  original_amount      numeric(19,4)  NOT NULL,
  open_amount          numeric(19,4)  NOT NULL,
  original_amount_base numeric(19,4)  NOT NULL,
  open_amount_base     numeric(19,4)  NOT NULL,
  exchange_rate        numeric(19,10) NOT NULL,
  journal_entry_id     uuid           NOT NULL,
  status               text           NOT NULL DEFAULT 'OPEN',
  settled_at           timestamptz    NULL,
  created_at           timestamptz    NOT NULL DEFAULT now(),
  created_by           uuid           NULL,
  updated_at           timestamptz    NOT NULL DEFAULT now(),
  updated_by           uuid           NULL,
  version              integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_open_items PRIMARY KEY (id),
  CONSTRAINT uq_open_items__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_open_items__source UNIQUE (company_id, source_module, source_type, source_id),
  CONSTRAINT fk_open_items__partners FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id),
  CONSTRAINT fk_open_items__accounts FOREIGN KEY (company_id, account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT fk_open_items__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_open_items__journal_entries
    FOREIGN KEY (company_id, journal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT ck_open_items__kind CHECK (kind IN ('RECEIVABLE', 'PAYABLE')),
  CONSTRAINT ck_open_items__amounts CHECK (
    original_amount <> 0
    AND (open_amount = 0 OR sign(open_amount) = sign(original_amount))
    AND abs(open_amount) <= abs(original_amount)
    AND (open_amount_base = 0 OR sign(open_amount_base) = sign(original_amount))),
  -- VOIDED: the item of a voided payment (ADR-038).
  CONSTRAINT ck_open_items__status CHECK (status IN ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'VOIDED')),
  CONSTRAINT ck_open_items__settled CHECK ((status IN ('SETTLED', 'VOIDED')) = (open_amount = 0 AND open_amount_base = 0)),
  CONSTRAINT ck_open_items__dates CHECK (due_date >= document_date),
  CONSTRAINT ck_open_items__exchange_rate CHECK (exchange_rate > 0),
  CONSTRAINT ck_open_items__version CHECK (version >= 0)
);
CREATE INDEX ix_open_items__unsettled_partner
  ON accounting.open_items (company_id, kind, partner_id, status) WHERE status NOT IN ('SETTLED', 'VOIDED');
CREATE INDEX ix_open_items__unsettled_due
  ON accounting.open_items (company_id, kind, due_date) WHERE status NOT IN ('SETTLED', 'VOIDED');
CREATE INDEX ix_open_items__company_id_partner_id ON accounting.open_items (company_id, partner_id);
CREATE INDEX ix_open_items__company_id_account_id ON accounting.open_items (company_id, account_id);
CREATE INDEX ix_open_items__company_id_journal_entry_id ON accounting.open_items (company_id, journal_entry_id);
CREATE INDEX ix_open_items__currency_code ON accounting.open_items (currency_code);
SELECT platform.enable_company_rls('accounting.open_items');
CREATE TRIGGER trg_open_items_append_only
  BEFORE UPDATE OR DELETE ON accounting.open_items
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_append_only('open_amount', 'open_amount_base', 'status', 'settled_at');

-- ------------------------------------------------------------------------- journal lines
CREATE TABLE accounting.journal_lines (
  id               uuid          NOT NULL DEFAULT uuidv7(),
  company_id       uuid          NOT NULL,
  journal_entry_id uuid          NOT NULL,
  line_no          integer       NOT NULL,
  account_id       uuid          NOT NULL,
  debit            numeric(19,4) NOT NULL DEFAULT 0,
  credit           numeric(19,4) NOT NULL DEFAULT 0,
  currency_code    char(3)       NOT NULL,
  -- Signed: + debit, − credit, in currency_code.
  amount_currency  numeric(19,4) NOT NULL,
  partner_id       uuid          NULL,
  branch_id        uuid          NULL,
  department_id    uuid          NULL,
  tax_code_id      uuid          NULL,
  open_item_id     uuid          NULL,
  description      text          NULL,
  -- Denormalized from the header for indexing and the immutability guard.
  entry_date       date          NOT NULL,
  period_id        uuid          NOT NULL,
  is_posted        boolean       NOT NULL DEFAULT false,
  created_at       timestamptz   NOT NULL DEFAULT now(),
  created_by       uuid          NULL,
  CONSTRAINT pk_journal_lines PRIMARY KEY (id),
  CONSTRAINT uq_journal_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_journal_lines__journal_entry_id_line_no UNIQUE (journal_entry_id, line_no),
  CONSTRAINT fk_journal_lines__journal_entries
    FOREIGN KEY (company_id, journal_entry_id) REFERENCES accounting.journal_entries (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_journal_lines__accounts FOREIGN KEY (company_id, account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT fk_journal_lines__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_journal_lines__partners FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id),
  CONSTRAINT fk_journal_lines__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_journal_lines__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT fk_journal_lines__tax_codes FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_journal_lines__open_items
    FOREIGN KEY (company_id, open_item_id) REFERENCES accounting.open_items (company_id, id),
  CONSTRAINT fk_journal_lines__periods FOREIGN KEY (company_id, period_id) REFERENCES accounting.periods (company_id, id),
  CONSTRAINT ck_journal_lines__line_no CHECK (line_no BETWEEN 1 AND 10000),
  -- ACC-2: exactly one non-zero side, never negative.
  CONSTRAINT ck_journal_lines__one_side CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)),
  CONSTRAINT ck_journal_lines__amount_currency_sign CHECK (sign(amount_currency) = sign(debit - credit)),
  CONSTRAINT ck_journal_lines__description CHECK (description IS NULL OR length(description) <= 500)
);
CREATE INDEX ix_journal_lines__posted_account
  ON accounting.journal_lines (company_id, account_id, entry_date) WHERE is_posted;
CREATE INDEX ix_journal_lines__partner_account
  ON accounting.journal_lines (company_id, partner_id, account_id) WHERE partner_id IS NOT NULL;
CREATE INDEX ix_journal_lines__journal_entry_id ON accounting.journal_lines (journal_entry_id);
CREATE INDEX ix_journal_lines__company_id_account_id ON accounting.journal_lines (company_id, account_id);
CREATE INDEX ix_journal_lines__company_id_branch_id ON accounting.journal_lines (company_id, branch_id);
CREATE INDEX ix_journal_lines__company_id_department_id ON accounting.journal_lines (company_id, department_id);
CREATE INDEX ix_journal_lines__company_id_tax_code_id ON accounting.journal_lines (company_id, tax_code_id);
CREATE INDEX ix_journal_lines__company_id_open_item_id ON accounting.journal_lines (company_id, open_item_id);
CREATE INDEX ix_journal_lines__company_id_period_id ON accounting.journal_lines (company_id, period_id);
CREATE INDEX ix_journal_lines__currency_code ON accounting.journal_lines (currency_code);
SELECT platform.enable_company_rls('accounting.journal_lines');

-- A1 / ACC-1: the balance is checked again at commit, after all lines are written. A posted entry
-- has at least two lines, all posted, dated and in the period of the header, debits equal credits
-- and equal the header totals.
CREATE FUNCTION accounting.assert_entry_balanced()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_count      bigint;
  v_debit      numeric;
  v_credit     numeric;
  v_mismatched bigint;
BEGIN
  SELECT count(*), coalesce(sum(debit), 0), coalesce(sum(credit), 0),
         count(*) FILTER (WHERE NOT is_posted OR entry_date <> NEW.entry_date OR period_id <> NEW.period_id)
    INTO v_count, v_debit, v_credit, v_mismatched
    FROM accounting.journal_lines
   WHERE journal_entry_id = NEW.id;
  IF v_count < 2 OR v_debit <> v_credit OR v_debit <> NEW.total_debit OR v_credit <> NEW.total_credit
     OR v_mismatched > 0 THEN
    RAISE EXCEPTION 'journal entry % is not balanced (% lines, debit %, credit %)', NEW.id, v_count, v_debit, v_credit
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_balanced',
            TABLE = 'journal_entries', SCHEMA = 'accounting';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ck_journal_balanced
  AFTER INSERT OR UPDATE ON accounting.journal_entries
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
  WHEN (NEW.status = 'POSTED')
  EXECUTE FUNCTION accounting.assert_entry_balanced();

-- A3 / ACC-3: a posted entry is never deleted; an update may only link it to its reversal (once).
CREATE FUNCTION accounting.guard_posted_entry()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_mutable text[] := ARRAY['reversed_by_id', 'updated_at', 'updated_by', 'version'];
BEGIN
  IF OLD.status = 'DRAFT' THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_OP = 'DELETE'
     OR (to_jsonb(NEW) - v_mutable) IS DISTINCT FROM (to_jsonb(OLD) - v_mutable)
     OR (OLD.reversed_by_id IS NOT NULL AND NEW.reversed_by_id IS DISTINCT FROM OLD.reversed_by_id) THEN
    RAISE EXCEPTION 'journal entry % is posted and cannot be changed', OLD.id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_entries__immutable',
            TABLE = 'journal_entries', SCHEMA = 'accounting';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_journal_immutable
  BEFORE UPDATE OR DELETE ON accounting.journal_entries
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_posted_entry();

-- Lines of a posted entry are never inserted, changed or deleted. Posting marks the lines posted
-- while the header is still DRAFT, then flips the header.
CREATE FUNCTION accounting.guard_posted_lines()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_status text;
BEGIN
  SELECT status INTO v_status
    FROM accounting.journal_entries
   WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_entry_id ELSE NEW.journal_entry_id END;
  IF v_status = 'POSTED' THEN
    RAISE EXCEPTION 'lines of posted journal entry % cannot be changed',
      CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_entry_id ELSE NEW.journal_entry_id END
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_lines__immutable',
            TABLE = 'journal_lines', SCHEMA = 'accounting';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER trg_journal_lines_immutable
  BEFORE INSERT OR UPDATE OR DELETE ON accounting.journal_lines
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_posted_lines();

-- A4 / ACC-4: a posted entry lies in an OPEN period of its company; SOFT_CLOSED only when the
-- posting service set app.allow_soft_closed after checking accounting.period.post_soft_closed; a
-- CLOSED period only for the year-end CLOSING entry (app.allow_closing_entry, ADR-038).
CREATE FUNCTION accounting.assert_period_open()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_period accounting.periods%ROWTYPE;
BEGIN
  SELECT * INTO v_period FROM accounting.periods WHERE id = NEW.period_id;
  IF NOT FOUND OR v_period.company_id <> NEW.company_id
     OR NEW.entry_date NOT BETWEEN v_period.start_date AND v_period.end_date THEN
    RAISE EXCEPTION 'journal entry % is dated % outside its period', NEW.id, NEW.entry_date
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_entries__period_open',
            TABLE = 'journal_entries', SCHEMA = 'accounting';
  END IF;
  IF v_period.status = 'OPEN'
     OR (v_period.status = 'SOFT_CLOSED' AND current_setting('app.allow_soft_closed', true) = 'on')
     OR (v_period.status = 'CLOSED' AND NEW.entry_type = 'CLOSING'
         AND current_setting('app.allow_closing_entry', true) = 'on') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'period % is % and takes no postings', v_period.id, v_period.status
    USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_entries__period_open',
          TABLE = 'journal_entries', SCHEMA = 'accounting';
END;
$$;
CREATE TRIGGER trg_journal_period_check
  BEFORE INSERT OR UPDATE OF status, entry_date, period_id ON accounting.journal_entries
  FOR EACH ROW WHEN (NEW.status = 'POSTED')
  EXECUTE FUNCTION accounting.assert_period_open();

-- A5: lines go to active postable accounts of the company, in the account's currency if it has
-- one; control accounts take no lines of user-made entries (MANUAL, ADJUSTMENT, OPENING).
CREATE FUNCTION accounting.assert_account_postable()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, accounting
AS $$
DECLARE
  v_account accounting.accounts%ROWTYPE;
  v_type    text;
BEGIN
  SELECT * INTO v_account FROM accounting.accounts WHERE id = NEW.account_id AND company_id = NEW.company_id;
  IF NOT FOUND OR NOT v_account.is_postable OR v_account.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'account % takes no postings', NEW.account_id
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_lines__account_postable',
            TABLE = 'journal_lines', SCHEMA = 'accounting';
  END IF;
  IF v_account.currency_code IS NOT NULL AND v_account.currency_code <> NEW.currency_code THEN
    RAISE EXCEPTION 'account % takes postings in % only', NEW.account_id, v_account.currency_code
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_lines__account_currency',
            TABLE = 'journal_lines', SCHEMA = 'accounting';
  END IF;
  IF v_account.is_control THEN
    SELECT entry_type INTO v_type FROM accounting.journal_entries WHERE id = NEW.journal_entry_id;
    IF v_type IN ('MANUAL', 'ADJUSTMENT', 'OPENING') THEN
      RAISE EXCEPTION 'control account % takes no manual postings', NEW.account_id
        USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_journal_lines__control_account',
              TABLE = 'journal_lines', SCHEMA = 'accounting';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_journal_line_account_check
  BEFORE INSERT OR UPDATE OF account_id, currency_code ON accounting.journal_lines
  FOR EACH ROW EXECUTE FUNCTION accounting.assert_account_postable();
REVOKE EXECUTE ON FUNCTION accounting.assert_entry_balanced(), accounting.guard_posted_entry(),
  accounting.guard_posted_lines(), accounting.assert_period_open(), accounting.assert_account_postable() FROM PUBLIC;

-- -------------------------------------------------------------------------- bank accounts
CREATE TABLE accounting.bank_accounts (
  id                       uuid        NOT NULL DEFAULT uuidv7(),
  company_id               uuid        NOT NULL,
  name                     text        NOT NULL,
  -- The GL account (subtype BANK or CASH) the account's money is booked on.
  account_id               uuid        NOT NULL,
  currency_code            char(3)     NOT NULL,
  bank_name                text        NULL,
  account_number_encrypted bytea       NULL,
  iban_encrypted           bytea       NULL,
  key_version              smallint    NULL,
  account_number_last4     char(4)     NULL,
  is_active                boolean     NOT NULL DEFAULT true,
  created_at               timestamptz NOT NULL DEFAULT now(),
  created_by               uuid        NULL,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  updated_by               uuid        NULL,
  version                  integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_bank_accounts PRIMARY KEY (id),
  CONSTRAINT uq_bank_accounts__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_bank_accounts__company_id_name UNIQUE (company_id, name),
  CONSTRAINT uq_bank_accounts__account_id UNIQUE (account_id),
  CONSTRAINT fk_bank_accounts__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_bank_accounts__accounts FOREIGN KEY (company_id, account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT fk_bank_accounts__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_bank_accounts__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_bank_accounts__bank_name CHECK (bank_name IS NULL OR length(bank_name) <= 100),
  CONSTRAINT ck_bank_accounts__encrypted
    CHECK ((account_number_encrypted IS NULL AND iban_encrypted IS NULL) OR key_version IS NOT NULL),
  CONSTRAINT ck_bank_accounts__last4 CHECK (account_number_last4 IS NULL OR account_number_last4 ~ '^[0-9A-Z]{1,4}$'),
  CONSTRAINT ck_bank_accounts__version CHECK (version >= 0)
);
CREATE INDEX ix_bank_accounts__currency_code ON accounting.bank_accounts (currency_code);
SELECT platform.enable_company_rls('accounting.bank_accounts');

-- ------------------------------------------------------------------------------- payments
CREATE TABLE accounting.payments (
  id                    uuid           NOT NULL DEFAULT uuidv7(),
  company_id            uuid           NOT NULL,
  number                text           NULL,
  direction             text           NOT NULL,
  partner_id            uuid           NULL,
  payment_kind          text           NOT NULL,
  bank_account_id       uuid           NOT NULL,
  payment_date          date           NOT NULL,
  currency_code         char(3)        NOT NULL,
  amount                numeric(19,4)  NOT NULL,
  exchange_rate         numeric(19,10) NOT NULL,
  amount_base           numeric(19,4)  NOT NULL,
  method                text           NOT NULL,
  reference             text           NULL,
  notes                 text           NULL,
  status                text           NOT NULL DEFAULT 'DRAFT',
  -- Allocations requested on the draft ([{openItemId, amount}]), applied when it is posted (ADR-038).
  requested_allocations jsonb          NOT NULL DEFAULT '[]'::jsonb,
  -- The payment's own open item (the whole amount, negative), from which allocations settle items.
  open_item_id          uuid           NULL,
  journal_entry_id      uuid           NULL,
  void_journal_entry_id uuid           NULL,
  voided_reason         text           NULL,
  voided_at             timestamptz    NULL,
  voided_by             uuid           NULL,
  source_module         text           NULL,
  source_type           text           NULL,
  source_id             uuid           NULL,
  posted_at             timestamptz    NULL,
  posted_by             uuid           NULL,
  created_at            timestamptz    NOT NULL DEFAULT now(),
  created_by            uuid           NULL,
  updated_at            timestamptz    NOT NULL DEFAULT now(),
  updated_by            uuid           NULL,
  version               integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_payments PRIMARY KEY (id),
  CONSTRAINT uq_payments__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_payments__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_payments__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_payments__partners FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id),
  CONSTRAINT fk_payments__bank_accounts
    FOREIGN KEY (company_id, bank_account_id) REFERENCES accounting.bank_accounts (company_id, id),
  CONSTRAINT fk_payments__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_payments__open_items FOREIGN KEY (company_id, open_item_id) REFERENCES accounting.open_items (company_id, id),
  CONSTRAINT fk_payments__journal_entries
    FOREIGN KEY (company_id, journal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT fk_payments__void_journal_entries
    FOREIGN KEY (company_id, void_journal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT ck_payments__direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
  CONSTRAINT ck_payments__payment_kind CHECK (payment_kind IN ('CUSTOMER', 'SUPPLIER', 'OTHER')),
  CONSTRAINT ck_payments__kind_direction CHECK (
    (payment_kind = 'CUSTOMER' AND direction = 'INBOUND') OR (payment_kind = 'SUPPLIER' AND direction = 'OUTBOUND')
    OR payment_kind = 'OTHER'),
  CONSTRAINT ck_payments__partner CHECK (payment_kind = 'OTHER' OR partner_id IS NOT NULL),
  CONSTRAINT ck_payments__amounts CHECK (amount > 0 AND amount_base > 0 AND exchange_rate > 0),
  CONSTRAINT ck_payments__method CHECK (method IN ('CASH', 'BANK_TRANSFER', 'CHEQUE', 'CARD', 'OTHER')),
  CONSTRAINT ck_payments__status CHECK (status IN ('DRAFT', 'POSTED', 'VOIDED')),
  CONSTRAINT ck_payments__posted CHECK (status = 'DRAFT' OR (number IS NOT NULL AND journal_entry_id IS NOT NULL
    AND posted_at IS NOT NULL)),
  CONSTRAINT ck_payments__voided CHECK ((status = 'VOIDED') = (void_journal_entry_id IS NOT NULL
    AND voided_reason IS NOT NULL AND voided_at IS NOT NULL)),
  CONSTRAINT ck_payments__requested CHECK (jsonb_typeof(requested_allocations) = 'array'),
  CONSTRAINT ck_payments__texts CHECK ((reference IS NULL OR length(reference) <= 100)
    AND (notes IS NULL OR length(notes) <= 2000) AND (voided_reason IS NULL OR length(voided_reason) <= 500)),
  CONSTRAINT ck_payments__version CHECK (version >= 0)
);
CREATE INDEX ix_payments__company_id_partner_id ON accounting.payments (company_id, partner_id);
CREATE INDEX ix_payments__company_id_bank_account_id ON accounting.payments (company_id, bank_account_id);
CREATE INDEX ix_payments__company_id_payment_date ON accounting.payments (company_id, payment_date DESC);
CREATE INDEX ix_payments__company_id_open_item_id ON accounting.payments (company_id, open_item_id);
CREATE INDEX ix_payments__company_id_journal_entry_id ON accounting.payments (company_id, journal_entry_id);
CREATE INDEX ix_payments__company_id_void_journal_entry_id ON accounting.payments (company_id, void_journal_entry_id);
CREATE INDEX ix_payments__currency_code ON accounting.payments (currency_code);
SELECT platform.enable_company_rls('accounting.payments');
CREATE TRIGGER trg_payments_frozen
  BEFORE UPDATE OR DELETE ON accounting.payments
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_frozen_document(
    'status', 'void_journal_entry_id', 'voided_reason', 'voided_at', 'voided_by');

CREATE TABLE accounting.payment_allocations (
  id                        uuid           NOT NULL DEFAULT uuidv7(),
  company_id                uuid           NOT NULL,
  -- NULL when a credit note or debit note is netted against an invoice or bill.
  payment_id                uuid           NULL,
  -- The item being settled (positive) and the item settling it (negative: payment, credit note).
  open_item_id              uuid           NOT NULL,
  counter_open_item_id      uuid           NOT NULL,
  allocation_date           date           NOT NULL,
  -- In the items' currency; reduces |open_amount| on both sides.
  amount                    numeric(19,4)  NOT NULL,
  amount_base               numeric(19,4)  NOT NULL,
  -- Realized FX difference in base currency: loss (+) or gain (−), booked by journal_entry_id.
  fx_difference_base        numeric(19,4)  NOT NULL DEFAULT 0,
  journal_entry_id          uuid           NULL,
  reversed_at               timestamptz    NULL,
  reversed_by               uuid           NULL,
  reversal_journal_entry_id uuid           NULL,
  created_at                timestamptz    NOT NULL DEFAULT now(),
  created_by                uuid           NULL,
  updated_at                timestamptz    NOT NULL DEFAULT now(),
  updated_by                uuid           NULL,
  version                   integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_payment_allocations PRIMARY KEY (id),
  CONSTRAINT uq_payment_allocations__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_payment_allocations__payments
    FOREIGN KEY (company_id, payment_id) REFERENCES accounting.payments (company_id, id),
  CONSTRAINT fk_payment_allocations__open_items
    FOREIGN KEY (company_id, open_item_id) REFERENCES accounting.open_items (company_id, id),
  CONSTRAINT fk_payment_allocations__counter_open_items
    FOREIGN KEY (company_id, counter_open_item_id) REFERENCES accounting.open_items (company_id, id),
  CONSTRAINT fk_payment_allocations__journal_entries
    FOREIGN KEY (company_id, journal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT fk_payment_allocations__reversal_journal_entries
    FOREIGN KEY (company_id, reversal_journal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT ck_payment_allocations__amounts CHECK (amount > 0 AND amount_base >= 0),
  CONSTRAINT ck_payment_allocations__items CHECK (open_item_id <> counter_open_item_id),
  CONSTRAINT ck_payment_allocations__fx CHECK ((fx_difference_base = 0) = (journal_entry_id IS NULL)),
  CONSTRAINT ck_payment_allocations__reversed CHECK (
    (reversed_at IS NULL) = (reversed_by IS NULL)
    AND (reversal_journal_entry_id IS NULL OR reversed_at IS NOT NULL)),
  CONSTRAINT ck_payment_allocations__version CHECK (version >= 0)
);
CREATE INDEX ix_payment_allocations__company_id_payment_id ON accounting.payment_allocations (company_id, payment_id);
CREATE INDEX ix_payment_allocations__company_id_open_item_id ON accounting.payment_allocations (company_id, open_item_id);
CREATE INDEX ix_payment_allocations__company_id_counter_open_item_id
  ON accounting.payment_allocations (company_id, counter_open_item_id);
CREATE INDEX ix_payment_allocations__company_id_journal_entry_id
  ON accounting.payment_allocations (company_id, journal_entry_id);
CREATE INDEX ix_payment_allocations__company_id_reversal_journal_entry_id
  ON accounting.payment_allocations (company_id, reversal_journal_entry_id);
SELECT platform.enable_company_rls('accounting.payment_allocations');
CREATE TRIGGER trg_payment_allocations_append_only
  BEFORE UPDATE OR DELETE ON accounting.payment_allocations
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_append_only('reversed_at', 'reversed_by', 'reversal_journal_entry_id');

-- ------------------------------------------------------------------------------- expenses
CREATE TABLE accounting.expenses (
  id                 uuid           NOT NULL DEFAULT uuidv7(),
  company_id         uuid           NOT NULL,
  number             text           NULL,
  expense_date       date           NOT NULL,
  accounting_date    date           NOT NULL,
  payee_name         text           NOT NULL,
  partner_id         uuid           NULL,
  bank_account_id    uuid           NOT NULL,
  currency_code      char(3)        NOT NULL,
  exchange_rate      numeric(19,10) NOT NULL DEFAULT 1,
  prices_include_tax boolean        NOT NULL DEFAULT false,
  subtotal           numeric(19,4)  NOT NULL DEFAULT 0,
  tax_total          numeric(19,4)  NOT NULL DEFAULT 0,
  total              numeric(19,4)  NOT NULL DEFAULT 0,
  total_base         numeric(19,4)  NOT NULL DEFAULT 0,
  reference          text           NULL,
  notes              text           NULL,
  status             text           NOT NULL DEFAULT 'DRAFT',
  journal_entry_id   uuid           NULL,
  reversal_entry_id  uuid           NULL,
  reversal_reason    text           NULL,
  posted_at          timestamptz    NULL,
  posted_by          uuid           NULL,
  created_at         timestamptz    NOT NULL DEFAULT now(),
  created_by         uuid           NULL,
  updated_at         timestamptz    NOT NULL DEFAULT now(),
  updated_by         uuid           NULL,
  version            integer        NOT NULL DEFAULT 0,
  CONSTRAINT pk_expenses PRIMARY KEY (id),
  CONSTRAINT uq_expenses__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_expenses__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_expenses__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_expenses__partners FOREIGN KEY (company_id, partner_id) REFERENCES partners.partners (company_id, id),
  CONSTRAINT fk_expenses__bank_accounts
    FOREIGN KEY (company_id, bank_account_id) REFERENCES accounting.bank_accounts (company_id, id),
  CONSTRAINT fk_expenses__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_expenses__journal_entries
    FOREIGN KEY (company_id, journal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT fk_expenses__reversal_entries
    FOREIGN KEY (company_id, reversal_entry_id) REFERENCES accounting.journal_entries (company_id, id),
  CONSTRAINT ck_expenses__status CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
  CONSTRAINT ck_expenses__posted CHECK (status = 'DRAFT' OR (number IS NOT NULL AND journal_entry_id IS NOT NULL)),
  CONSTRAINT ck_expenses__reversed CHECK ((status = 'REVERSED') = (reversal_entry_id IS NOT NULL)),
  CONSTRAINT ck_expenses__totals CHECK (subtotal >= 0 AND tax_total >= 0 AND total = subtotal + tax_total
    AND total_base >= 0),
  CONSTRAINT ck_expenses__exchange_rate CHECK (exchange_rate > 0),
  CONSTRAINT ck_expenses__texts CHECK (btrim(payee_name) <> '' AND length(payee_name) <= 200
    AND (reference IS NULL OR length(reference) <= 100) AND (notes IS NULL OR length(notes) <= 2000)
    AND (reversal_reason IS NULL OR length(reversal_reason) <= 500)),
  CONSTRAINT ck_expenses__version CHECK (version >= 0)
);
CREATE INDEX ix_expenses__company_id_partner_id ON accounting.expenses (company_id, partner_id);
CREATE INDEX ix_expenses__company_id_bank_account_id ON accounting.expenses (company_id, bank_account_id);
CREATE INDEX ix_expenses__company_id_journal_entry_id ON accounting.expenses (company_id, journal_entry_id);
CREATE INDEX ix_expenses__company_id_reversal_entry_id ON accounting.expenses (company_id, reversal_entry_id);
CREATE INDEX ix_expenses__currency_code ON accounting.expenses (currency_code);
SELECT platform.enable_company_rls('accounting.expenses');
CREATE TRIGGER trg_expenses_frozen
  BEFORE UPDATE OR DELETE ON accounting.expenses
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_frozen_document('status', 'reversal_entry_id', 'reversal_reason');

CREATE TABLE accounting.expense_lines (
  id            uuid          NOT NULL DEFAULT uuidv7(),
  company_id    uuid          NOT NULL,
  expense_id    uuid          NOT NULL,
  line_no       integer       NOT NULL,
  account_id    uuid          NOT NULL,
  description   text          NULL,
  net_amount    numeric(19,4) NOT NULL,
  tax_code_id   uuid          NULL,
  tax_amount    numeric(19,4) NOT NULL DEFAULT 0,
  total_amount  numeric(19,4) NOT NULL,
  branch_id     uuid          NULL,
  department_id uuid          NULL,
  created_at    timestamptz   NOT NULL DEFAULT now(),
  created_by    uuid          NULL,
  updated_at    timestamptz   NOT NULL DEFAULT now(),
  updated_by    uuid          NULL,
  version       integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_expense_lines PRIMARY KEY (id),
  CONSTRAINT uq_expense_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_expense_lines__expense_id_line_no UNIQUE (expense_id, line_no),
  CONSTRAINT fk_expense_lines__expenses
    FOREIGN KEY (company_id, expense_id) REFERENCES accounting.expenses (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_expense_lines__accounts FOREIGN KEY (company_id, account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT fk_expense_lines__tax_codes FOREIGN KEY (company_id, tax_code_id) REFERENCES org.tax_codes (company_id, id),
  CONSTRAINT fk_expense_lines__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_expense_lines__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT ck_expense_lines__line_no CHECK (line_no BETWEEN 1 AND 500),
  CONSTRAINT ck_expense_lines__amounts CHECK (net_amount > 0 AND tax_amount >= 0 AND total_amount = net_amount + tax_amount),
  CONSTRAINT ck_expense_lines__description CHECK (description IS NULL OR length(description) <= 300),
  CONSTRAINT ck_expense_lines__version CHECK (version >= 0)
);
CREATE INDEX ix_expense_lines__company_id_expense_id ON accounting.expense_lines (company_id, expense_id);
CREATE INDEX ix_expense_lines__company_id_account_id ON accounting.expense_lines (company_id, account_id);
CREATE INDEX ix_expense_lines__company_id_tax_code_id ON accounting.expense_lines (company_id, tax_code_id);
CREATE INDEX ix_expense_lines__company_id_branch_id ON accounting.expense_lines (company_id, branch_id);
CREATE INDEX ix_expense_lines__company_id_department_id ON accounting.expense_lines (company_id, department_id);
SELECT platform.enable_company_rls('accounting.expense_lines');
CREATE TRIGGER trg_expense_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON accounting.expense_lines
  FOR EACH ROW EXECUTE FUNCTION accounting.guard_frozen_lines('expenses', 'expense_id');

-- ------------------------------------------------------------------ bank reconciliation
-- A posted bank or cash line marked as reconciled against a statement; deleting the mark
-- un-reconciles it. The journal line itself is never modified.
CREATE TABLE accounting.bank_reconciliation_marks (
  journal_line_id     uuid        NOT NULL,
  company_id          uuid        NOT NULL,
  bank_account_id     uuid        NOT NULL,
  statement_reference text        NOT NULL,
  statement_date      date        NOT NULL,
  reconciled_at       timestamptz NOT NULL DEFAULT now(),
  reconciled_by       uuid        NOT NULL,
  CONSTRAINT pk_bank_reconciliation_marks PRIMARY KEY (journal_line_id),
  CONSTRAINT fk_bank_reconciliation_marks__journal_lines
    FOREIGN KEY (company_id, journal_line_id) REFERENCES accounting.journal_lines (company_id, id),
  CONSTRAINT fk_bank_reconciliation_marks__bank_accounts
    FOREIGN KEY (company_id, bank_account_id) REFERENCES accounting.bank_accounts (company_id, id),
  CONSTRAINT ck_bank_reconciliation_marks__reference
    CHECK (btrim(statement_reference) <> '' AND length(statement_reference) <= 100)
);
CREATE INDEX ix_bank_reconciliation_marks__company_id_bank_account_id
  ON accounting.bank_reconciliation_marks (company_id, bank_account_id);
CREATE INDEX ix_bank_reconciliation_marks__company_id_journal_line_id
  ON accounting.bank_reconciliation_marks (company_id, journal_line_id);
SELECT platform.enable_company_rls('accounting.bank_reconciliation_marks');

-- ------------------------------------------------------------------------ period balances
-- Snapshots written when a period is closed and deleted when it is reopened (PRODUCT_SPEC.md §8.5).
CREATE TABLE accounting.period_balances (
  company_id      uuid          NOT NULL,
  period_id       uuid          NOT NULL,
  account_id      uuid          NOT NULL,
  opening_balance numeric(19,4) NOT NULL,
  debit_total     numeric(19,4) NOT NULL,
  credit_total    numeric(19,4) NOT NULL,
  closing_balance numeric(19,4) NOT NULL,
  computed_at     timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_period_balances PRIMARY KEY (company_id, period_id, account_id),
  CONSTRAINT fk_period_balances__periods FOREIGN KEY (company_id, period_id) REFERENCES accounting.periods (company_id, id),
  CONSTRAINT fk_period_balances__accounts FOREIGN KEY (company_id, account_id) REFERENCES accounting.accounts (company_id, id),
  CONSTRAINT ck_period_balances__closing CHECK (closing_balance = opening_balance + debit_total - credit_total)
);
CREATE INDEX ix_period_balances__company_id_account_id ON accounting.period_balances (company_id, account_id);
SELECT platform.enable_company_rls('accounting.period_balances');
