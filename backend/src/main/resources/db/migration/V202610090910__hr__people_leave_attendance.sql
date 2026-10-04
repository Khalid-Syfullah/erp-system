-- =====================================================================================
-- HR, Phase 9 (DATABASE.md §5.9, ADR-039): personal data with field-encrypted sensitive values,
-- the self-service user link, company HR settings (weekend days), employee bank accounts and
-- documents, leave (types, append-only ledger, requests), public holidays and basic attendance.
-- =====================================================================================

-- ------------------------------------------------------------------------ employees (personal)
ALTER TABLE hr.employees
  ADD COLUMN user_id                 uuid   NULL,
  ADD COLUMN personal_email          citext NULL,
  ADD COLUMN phone                   text   NULL,
  ADD COLUMN address                 jsonb  NULL,
  -- AES-256-GCM via FieldEncryptor (SECURITY.md §7.2); associated data names the column and row.
  ADD COLUMN date_of_birth_encrypted bytea  NULL,
  ADD COLUMN national_id_encrypted   bytea  NULL,
  ADD COLUMN national_id_last4       text   NULL,
  ADD COLUMN key_version             smallint NULL,
  ADD CONSTRAINT fk_employees__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  -- A user is linked to at most one employee per company (one person can work for two companies).
  ADD CONSTRAINT uq_employees__company_id_user_id UNIQUE (company_id, user_id),
  ADD CONSTRAINT ck_employees__personal_email
    CHECK (personal_email IS NULL OR (personal_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
                                      AND length(personal_email) <= 254)),
  ADD CONSTRAINT ck_employees__phone CHECK (phone IS NULL OR phone ~ '^\+?[0-9 ()./-]{3,30}$'),
  ADD CONSTRAINT ck_employees__address CHECK (address IS NULL OR jsonb_typeof(address) = 'object'),
  ADD CONSTRAINT ck_employees__national_id_last4 CHECK (national_id_last4 IS NULL OR length(national_id_last4) <= 4),
  ADD CONSTRAINT ck_employees__national_id
    CHECK ((national_id_encrypted IS NULL) = (national_id_last4 IS NULL)),
  ADD CONSTRAINT ck_employees__key_version
    CHECK ((key_version IS NULL) = (date_of_birth_encrypted IS NULL AND national_id_encrypted IS NULL));

-- ------------------------------------------------------------------------------- settings
CREATE TABLE hr.settings (
  company_id            uuid        NOT NULL,
  -- ISO day numbers (1 = Monday … 7 = Sunday) that are not working days (PRODUCT_SPEC.md §10.1).
  weekend_days          smallint[]  NOT NULL DEFAULT '{6,7}',
  standard_work_minutes integer     NOT NULL DEFAULT 480,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid        NULL,
  updated_at            timestamptz NOT NULL DEFAULT now(),
  updated_by            uuid        NULL,
  version               integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_settings__weekend_days
    CHECK (weekend_days <@ ARRAY[1,2,3,4,5,6,7]::smallint[] AND cardinality(weekend_days) <= 6),
  CONSTRAINT ck_settings__standard_work_minutes CHECK (standard_work_minutes BETWEEN 60 AND 1440),
  CONSTRAINT ck_settings__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('hr.settings');

-- ------------------------------------------------------------------- employee bank accounts
CREATE TABLE hr.employee_bank_accounts (
  id                       uuid        NOT NULL DEFAULT uuidv7(),
  company_id               uuid        NOT NULL,
  employee_id              uuid        NOT NULL,
  bank_name                text        NOT NULL,
  account_holder           text        NOT NULL,
  account_number_encrypted bytea       NOT NULL,
  iban_encrypted           bytea       NULL,
  swift_bic                text        NULL,
  last4                    text        NOT NULL,
  key_version              smallint    NOT NULL,
  is_primary               boolean     NOT NULL DEFAULT false,
  created_at               timestamptz NOT NULL DEFAULT now(),
  created_by               uuid        NULL,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  updated_by               uuid        NULL,
  version                  integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_employee_bank_accounts PRIMARY KEY (id),
  CONSTRAINT uq_employee_bank_accounts__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_employee_bank_accounts__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT ck_employee_bank_accounts__bank_name CHECK (btrim(bank_name) <> '' AND length(bank_name) <= 100),
  CONSTRAINT ck_employee_bank_accounts__account_holder
    CHECK (btrim(account_holder) <> '' AND length(account_holder) <= 150),
  CONSTRAINT ck_employee_bank_accounts__swift_bic
    CHECK (swift_bic IS NULL OR swift_bic ~ '^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$'),
  CONSTRAINT ck_employee_bank_accounts__last4 CHECK (length(last4) BETWEEN 1 AND 4),
  CONSTRAINT ck_employee_bank_accounts__version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_employee_bank_accounts__primary
  ON hr.employee_bank_accounts (employee_id) WHERE is_primary;
CREATE INDEX ix_employee_bank_accounts__company_id_employee_id
  ON hr.employee_bank_accounts (company_id, employee_id);

SELECT platform.enable_company_rls('hr.employee_bank_accounts');

-- ----------------------------------------------------------------------- employee documents
CREATE TABLE hr.employee_documents (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  company_id    uuid        NOT NULL,
  employee_id   uuid        NOT NULL,
  document_type text        NOT NULL,
  title         text        NOT NULL,
  file_id       uuid        NOT NULL,
  -- Copied from platform.files so that HR lists documents without reading the platform schema.
  file_name     text        NOT NULL,
  content_type  text        NOT NULL,
  size_bytes    bigint      NOT NULL,
  valid_until   date        NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid        NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid        NULL,
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_employee_documents PRIMARY KEY (id),
  CONSTRAINT uq_employee_documents__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_employee_documents__file_id UNIQUE (file_id),
  CONSTRAINT fk_employee_documents__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_employee_documents__files FOREIGN KEY (company_id, file_id) REFERENCES platform.files (company_id, id),
  CONSTRAINT ck_employee_documents__document_type
    CHECK (document_type IN ('CONTRACT', 'IDENTITY', 'CERTIFICATE', 'WORK_PERMIT', 'REVIEW', 'OTHER')),
  CONSTRAINT ck_employee_documents__title CHECK (btrim(title) <> '' AND length(title) <= 200),
  CONSTRAINT ck_employee_documents__size CHECK (size_bytes > 0),
  CONSTRAINT ck_employee_documents__version CHECK (version >= 0)
);

CREATE INDEX ix_employee_documents__company_id_employee_id ON hr.employee_documents (company_id, employee_id);

SELECT platform.enable_company_rls('hr.employee_documents');

-- ------------------------------------------------------------------------------ leave types
CREATE TABLE hr.leave_types (
  id                      uuid         NOT NULL DEFAULT uuidv7(),
  company_id              uuid         NOT NULL,
  code                    text         NOT NULL,
  name                    text         NOT NULL,
  is_paid                 boolean      NOT NULL DEFAULT true,
  annual_entitlement_days numeric(6,2) NOT NULL DEFAULT 0,
  -- ANNUAL: the year's entitlement at the leave-year start (pro rata for later hires); MONTHLY: 1/12 per month.
  accrual_method          text         NOT NULL DEFAULT 'ANNUAL',
  max_carry_forward_days  numeric(6,2) NOT NULL DEFAULT 0,
  allow_negative_balance  boolean      NOT NULL DEFAULT false,
  is_active               boolean      NOT NULL DEFAULT true,
  created_at              timestamptz  NOT NULL DEFAULT now(),
  created_by              uuid         NULL,
  updated_at              timestamptz  NOT NULL DEFAULT now(),
  updated_by              uuid         NULL,
  version                 integer      NOT NULL DEFAULT 0,
  CONSTRAINT pk_leave_types PRIMARY KEY (id),
  CONSTRAINT uq_leave_types__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_leave_types__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_leave_types__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_leave_types__code CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_leave_types__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_leave_types__entitlement CHECK (annual_entitlement_days BETWEEN 0 AND 366),
  CONSTRAINT ck_leave_types__accrual_method CHECK (accrual_method IN ('ANNUAL', 'MONTHLY')),
  CONSTRAINT ck_leave_types__carry_forward CHECK (max_carry_forward_days BETWEEN 0 AND 366),
  CONSTRAINT ck_leave_types__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('hr.leave_types');

-- --------------------------------------------------------------------------- leave requests
CREATE TABLE hr.leave_requests (
  id            uuid         NOT NULL DEFAULT uuidv7(),
  company_id    uuid         NOT NULL,
  employee_id   uuid         NOT NULL,
  leave_type_id uuid         NOT NULL,
  start_date    date         NOT NULL,
  end_date      date         NOT NULL,
  days          numeric(6,2) NOT NULL,
  reason        text         NULL,
  status        text         NOT NULL DEFAULT 'DRAFT',
  submitted_at  timestamptz  NULL,
  decided_by    uuid         NULL,
  decided_at    timestamptz  NULL,
  decision_note text         NULL,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  created_by    uuid         NULL,
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  updated_by    uuid         NULL,
  version       integer      NOT NULL DEFAULT 0,
  CONSTRAINT pk_leave_requests PRIMARY KEY (id),
  CONSTRAINT uq_leave_requests__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_leave_requests__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_leave_requests__leave_types
    FOREIGN KEY (company_id, leave_type_id) REFERENCES hr.leave_types (company_id, id),
  CONSTRAINT ck_leave_requests__dates CHECK (end_date >= start_date),
  -- Leave is booked per leave year (calendar year, Q-19): a request does not cross a year end.
  CONSTRAINT ck_leave_requests__one_year CHECK (extract(year FROM start_date) = extract(year FROM end_date)),
  CONSTRAINT ck_leave_requests__days CHECK (days > 0),
  CONSTRAINT ck_leave_requests__status
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
  CONSTRAINT ck_leave_requests__decided
    CHECK ((status IN ('APPROVED', 'REJECTED')) <= (decided_at IS NOT NULL AND decided_by IS NOT NULL)),
  CONSTRAINT ck_leave_requests__reason CHECK (reason IS NULL OR length(reason) <= 500),
  CONSTRAINT ck_leave_requests__decision_note CHECK (decision_note IS NULL OR length(decision_note) <= 500),
  CONSTRAINT ck_leave_requests__version CHECK (version >= 0),
  -- HR-2: submitted and approved leave of one employee never overlaps.
  CONSTRAINT ex_leave_requests__no_overlap
    EXCLUDE USING gist (employee_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
    WHERE (status IN ('SUBMITTED', 'APPROVED'))
);

CREATE INDEX ix_leave_requests__company_id_employee_id ON hr.leave_requests (company_id, employee_id);
CREATE INDEX ix_leave_requests__company_id_leave_type_id ON hr.leave_requests (company_id, leave_type_id);
CREATE INDEX ix_leave_requests__company_id_status ON hr.leave_requests (company_id, status);

SELECT platform.enable_company_rls('hr.leave_requests');

-- ----------------------------------------------------------------------------- leave ledger
-- Balances are sums over this append-only ledger (PRODUCT_SPEC.md §10.1).
CREATE TABLE hr.leave_ledger (
  id               uuid         NOT NULL DEFAULT uuidv7(),
  company_id       uuid         NOT NULL,
  employee_id      uuid         NOT NULL,
  leave_type_id    uuid         NOT NULL,
  leave_year       smallint     NOT NULL,
  accrual_month    smallint     NULL,
  entry_type       text         NOT NULL,
  days             numeric(6,2) NOT NULL,
  leave_request_id uuid         NULL,
  note             text         NULL,
  created_at       timestamptz  NOT NULL DEFAULT now(),
  created_by       uuid         NULL,
  CONSTRAINT pk_leave_ledger PRIMARY KEY (id),
  CONSTRAINT uq_leave_ledger__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_leave_ledger__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_leave_ledger__leave_types
    FOREIGN KEY (company_id, leave_type_id) REFERENCES hr.leave_types (company_id, id),
  CONSTRAINT fk_leave_ledger__leave_requests
    FOREIGN KEY (company_id, leave_request_id) REFERENCES hr.leave_requests (company_id, id),
  CONSTRAINT ck_leave_ledger__entry_type
    CHECK (entry_type IN ('ACCRUAL', 'TAKEN', 'ADJUSTMENT', 'CARRY_FORWARD', 'EXPIRY')),
  CONSTRAINT ck_leave_ledger__days CHECK (days <> 0),
  CONSTRAINT ck_leave_ledger__sign CHECK (
    (entry_type IN ('ACCRUAL', 'CARRY_FORWARD') AND days > 0)
    OR (entry_type = 'EXPIRY' AND days < 0)
    OR entry_type IN ('TAKEN', 'ADJUSTMENT')),
  CONSTRAINT ck_leave_ledger__leave_year CHECK (leave_year BETWEEN 2000 AND 2200),
  CONSTRAINT ck_leave_ledger__accrual_month
    CHECK (accrual_month IS NULL OR (entry_type = 'ACCRUAL' AND accrual_month BETWEEN 1 AND 12)),
  CONSTRAINT ck_leave_ledger__taken_request CHECK (entry_type <> 'TAKEN' OR leave_request_id IS NOT NULL),
  CONSTRAINT ck_leave_ledger__note CHECK (note IS NULL OR length(note) <= 500)
);

-- The accrual job is idempotent: one annual grant, or one grant per month, per employee, type and year.
CREATE UNIQUE INDEX uq_leave_ledger__annual_accrual ON hr.leave_ledger (employee_id, leave_type_id, leave_year)
  WHERE entry_type = 'ACCRUAL' AND accrual_month IS NULL;
CREATE UNIQUE INDEX uq_leave_ledger__monthly_accrual
  ON hr.leave_ledger (employee_id, leave_type_id, leave_year, accrual_month)
  WHERE entry_type = 'ACCRUAL' AND accrual_month IS NOT NULL;
CREATE UNIQUE INDEX uq_leave_ledger__carry_forward ON hr.leave_ledger (employee_id, leave_type_id, leave_year)
  WHERE entry_type IN ('CARRY_FORWARD');
CREATE UNIQUE INDEX uq_leave_ledger__expiry ON hr.leave_ledger (employee_id, leave_type_id, leave_year)
  WHERE entry_type IN ('EXPIRY');
CREATE INDEX ix_leave_ledger__balance ON hr.leave_ledger (company_id, employee_id, leave_type_id, leave_year);
CREATE INDEX ix_leave_ledger__company_id_leave_type_id ON hr.leave_ledger (company_id, leave_type_id);
CREATE INDEX ix_leave_ledger__company_id_leave_request_id ON hr.leave_ledger (company_id, leave_request_id);

CREATE FUNCTION hr.guard_ledger_append_only()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, hr
AS $$
BEGIN
  RAISE EXCEPTION 'leave ledger entry % cannot be changed or deleted', OLD.id
    USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_leave_ledger__immutable',
          TABLE = 'leave_ledger', SCHEMA = 'hr';
END;
$$;
REVOKE EXECUTE ON FUNCTION hr.guard_ledger_append_only() FROM PUBLIC;
CREATE TRIGGER trg_leave_ledger_append_only
  BEFORE UPDATE OR DELETE ON hr.leave_ledger
  FOR EACH ROW EXECUTE FUNCTION hr.guard_ledger_append_only();

SELECT platform.enable_company_rls('hr.leave_ledger');

-- -------------------------------------------------------------------------- public holidays
CREATE TABLE hr.public_holidays (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  company_id   uuid        NOT NULL,
  branch_id    uuid        NULL,
  holiday_date date        NOT NULL,
  name         text        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid        NULL,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   uuid        NULL,
  version      integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_public_holidays PRIMARY KEY (id),
  CONSTRAINT uq_public_holidays__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_public_holidays__company_id_branch_id_holiday_date
    UNIQUE NULLS NOT DISTINCT (company_id, branch_id, holiday_date),
  CONSTRAINT fk_public_holidays__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_public_holidays__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT ck_public_holidays__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_public_holidays__version CHECK (version >= 0)
);

CREATE INDEX ix_public_holidays__company_id_holiday_date ON hr.public_holidays (company_id, holiday_date);
CREATE INDEX ix_public_holidays__company_id_branch_id ON hr.public_holidays (company_id, branch_id);

SELECT platform.enable_company_rls('hr.public_holidays');

-- ------------------------------------------------------------------------------- attendance
-- Basic daily attendance (ADR-039): kept by HR or clocked in and out by the employee. Payroll does
-- not read it; overtime and unpaid absence are entered as payroll inputs.
CREATE TABLE hr.attendance_records (
  id             uuid        NOT NULL DEFAULT uuidv7(),
  company_id     uuid        NOT NULL,
  employee_id    uuid        NOT NULL,
  work_date      date        NOT NULL,
  status         text        NOT NULL,
  check_in       timestamptz NULL,
  check_out      timestamptz NULL,
  worked_minutes integer     NULL,
  source         text        NOT NULL DEFAULT 'MANUAL',
  note           text        NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid        NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid        NULL,
  version        integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_attendance_records PRIMARY KEY (id),
  CONSTRAINT uq_attendance_records__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_attendance_records__company_id_employee_id_work_date UNIQUE (company_id, employee_id, work_date),
  CONSTRAINT fk_attendance_records__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT ck_attendance_records__status
    CHECK (status IN ('PRESENT', 'ABSENT', 'HALF_DAY', 'REMOTE', 'ON_LEAVE', 'HOLIDAY')),
  CONSTRAINT ck_attendance_records__source CHECK (source IN ('MANUAL', 'SELF')),
  CONSTRAINT ck_attendance_records__times CHECK (check_out IS NULL OR (check_in IS NOT NULL AND check_out >= check_in)),
  CONSTRAINT ck_attendance_records__worked_minutes CHECK (worked_minutes IS NULL OR worked_minutes BETWEEN 0 AND 1440),
  CONSTRAINT ck_attendance_records__note CHECK (note IS NULL OR length(note) <= 500),
  CONSTRAINT ck_attendance_records__version CHECK (version >= 0)
);

CREATE INDEX ix_attendance_records__company_id_work_date ON hr.attendance_records (company_id, work_date);

SELECT platform.enable_company_rls('hr.attendance_records');
