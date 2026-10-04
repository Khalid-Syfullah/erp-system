-- =====================================================================================
-- Payroll (DATABASE.md §5.10, PRODUCT_SPEC.md §11, ADR-039): components with pluggable statutory
-- rules, salary structures, pay schedules and periods, effective-dated compensations with overrides,
-- period inputs, payroll runs with their issues, and payslips. Approved runs and their payslips are
-- frozen by triggers; posted runs change only to PAID.
-- =====================================================================================

SELECT platform.setup_module_schema('payroll');

-- ------------------------------------------------------------------------------- settings
CREATE TABLE payroll.settings (
  company_id      uuid        NOT NULL,
  -- PAY-1: partial periods are prorated by calendar days (default) or by working days.
  proration_basis text        NOT NULL DEFAULT 'CALENDAR_DAYS',
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid        NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid        NULL,
  version         integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_settings__proration_basis CHECK (proration_basis IN ('CALENDAR_DAYS', 'WORKING_DAYS')),
  CONSTRAINT ck_settings__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('payroll.settings');

-- ------------------------------------------------------------------------- pay components
CREATE TABLE payroll.pay_components (
  id                  uuid          NOT NULL DEFAULT uuidv7(),
  company_id          uuid          NOT NULL,
  code                text          NOT NULL,
  name                text          NOT NULL,
  kind                text          NOT NULL,
  calculation         text          NOT NULL,
  -- A percentage (PERCENT_*, STATUTORY) or a unit rate (INPUT: amount per quantity).
  default_rate        numeric(19,6) NULL,
  default_amount      numeric(19,4) NULL,
  is_taxable          boolean       NOT NULL DEFAULT true,
  statutory_rule_code text          NULL,
  sequence            integer       NOT NULL,
  is_active           boolean       NOT NULL DEFAULT true,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  created_by          uuid          NULL,
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  updated_by          uuid          NULL,
  version             integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_pay_components PRIMARY KEY (id),
  CONSTRAINT uq_pay_components__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_pay_components__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_pay_components__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_pay_components__code CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_pay_components__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_pay_components__kind CHECK (kind IN ('EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION')),
  CONSTRAINT ck_pay_components__calculation
    CHECK (calculation IN ('FIXED', 'PERCENT_OF_BASE', 'PERCENT_OF_GROSS', 'INPUT', 'STATUTORY')),
  -- Earnings make up the gross; they cannot depend on it.
  CONSTRAINT ck_pay_components__earning_calculation
    CHECK (kind <> 'EARNING' OR calculation IN ('FIXED', 'PERCENT_OF_BASE', 'INPUT')),
  CONSTRAINT ck_pay_components__statutory
    CHECK ((calculation = 'STATUTORY') = (statutory_rule_code IS NOT NULL)),
  CONSTRAINT ck_pay_components__statutory_rule_code
    CHECK (statutory_rule_code IS NULL OR statutory_rule_code ~ '^[A-Z0-9_]{1,40}$'),
  CONSTRAINT ck_pay_components__default_rate CHECK (default_rate IS NULL OR default_rate >= 0),
  CONSTRAINT ck_pay_components__default_amount CHECK (default_amount IS NULL OR default_amount >= 0),
  CONSTRAINT ck_pay_components__sequence CHECK (sequence BETWEEN 1 AND 9999),
  CONSTRAINT ck_pay_components__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('payroll.pay_components');

-- ---------------------------------------------------------------------- salary structures
CREATE TABLE payroll.salary_structures (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  company_id uuid        NOT NULL,
  code       text        NOT NULL,
  name       text        NOT NULL,
  is_active  boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_salary_structures PRIMARY KEY (id),
  CONSTRAINT uq_salary_structures__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_salary_structures__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_salary_structures__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_salary_structures__code CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_salary_structures__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_salary_structures__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('payroll.salary_structures');

CREATE TABLE payroll.salary_structure_components (
  company_id   uuid          NOT NULL,
  structure_id uuid          NOT NULL,
  component_id uuid          NOT NULL,
  rate         numeric(19,6) NULL,
  amount       numeric(19,4) NULL,
  CONSTRAINT pk_salary_structure_components PRIMARY KEY (structure_id, component_id),
  CONSTRAINT fk_salary_structure_components__salary_structures
    FOREIGN KEY (company_id, structure_id) REFERENCES payroll.salary_structures (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_salary_structure_components__pay_components
    FOREIGN KEY (company_id, component_id) REFERENCES payroll.pay_components (company_id, id),
  CONSTRAINT ck_salary_structure_components__rate CHECK (rate IS NULL OR rate >= 0),
  CONSTRAINT ck_salary_structure_components__amount CHECK (amount IS NULL OR amount >= 0)
);

CREATE INDEX ix_salary_structure_components__company_id_component_id
  ON payroll.salary_structure_components (company_id, component_id);

SELECT platform.enable_company_rls('payroll.salary_structure_components');

-- -------------------------------------------------------------------------- pay schedules
CREATE TABLE payroll.pay_schedules (
  id              uuid        NOT NULL DEFAULT uuidv7(),
  company_id      uuid        NOT NULL,
  code            text        NOT NULL,
  name            text        NOT NULL,
  frequency       text        NOT NULL,
  currency_code   char(3)     NOT NULL,
  -- WEEKLY / BIWEEKLY periods start on this date and every 7 / 14 days from it.
  anchor_date     date        NULL,
  pay_day_offset  smallint    NOT NULL DEFAULT 0,
  is_active       boolean     NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid        NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid        NULL,
  version         integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_pay_schedules PRIMARY KEY (id),
  CONSTRAINT uq_pay_schedules__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_pay_schedules__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_pay_schedules__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_pay_schedules__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_pay_schedules__code CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_pay_schedules__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_pay_schedules__frequency CHECK (frequency IN ('MONTHLY', 'SEMI_MONTHLY', 'BIWEEKLY', 'WEEKLY')),
  CONSTRAINT ck_pay_schedules__anchor
    CHECK ((frequency IN ('BIWEEKLY', 'WEEKLY')) = (anchor_date IS NOT NULL)),
  CONSTRAINT ck_pay_schedules__pay_day_offset CHECK (pay_day_offset BETWEEN -31 AND 31),
  CONSTRAINT ck_pay_schedules__version CHECK (version >= 0)
);

CREATE INDEX ix_pay_schedules__currency_code ON payroll.pay_schedules (currency_code);

SELECT platform.enable_company_rls('payroll.pay_schedules');

-- ------------------------------------------------------------------------ payroll periods
CREATE TABLE payroll.payroll_periods (
  id              uuid        NOT NULL DEFAULT uuidv7(),
  company_id      uuid        NOT NULL,
  pay_schedule_id uuid        NOT NULL,
  start_date      date        NOT NULL,
  end_date        date        NOT NULL,
  pay_date        date        NOT NULL,
  status          text        NOT NULL DEFAULT 'OPEN',
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid        NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid        NULL,
  version         integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_payroll_periods PRIMARY KEY (id),
  CONSTRAINT uq_payroll_periods__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_payroll_periods__pay_schedule_id_start_date UNIQUE (pay_schedule_id, start_date),
  CONSTRAINT fk_payroll_periods__pay_schedules
    FOREIGN KEY (company_id, pay_schedule_id) REFERENCES payroll.pay_schedules (company_id, id),
  CONSTRAINT ck_payroll_periods__dates CHECK (end_date >= start_date),
  CONSTRAINT ck_payroll_periods__status CHECK (status IN ('OPEN', 'PROCESSED', 'CLOSED')),
  CONSTRAINT ck_payroll_periods__version CHECK (version >= 0),
  CONSTRAINT ex_payroll_periods__no_overlap
    EXCLUDE USING gist (pay_schedule_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
);

CREATE INDEX ix_payroll_periods__company_id_pay_schedule_id ON payroll.payroll_periods (company_id, pay_schedule_id);

SELECT platform.enable_company_rls('payroll.payroll_periods');

-- ------------------------------------------------------------------ employee compensations
CREATE TABLE payroll.employee_compensations (
  id                  uuid          NOT NULL DEFAULT uuidv7(),
  company_id          uuid          NOT NULL,
  employee_id         uuid          NOT NULL,
  pay_schedule_id     uuid          NOT NULL,
  salary_structure_id uuid          NOT NULL,
  -- The base pay of one full pay period of the schedule, in the schedule's currency.
  base_amount         numeric(19,4) NOT NULL,
  currency_code       char(3)       NOT NULL,
  effective_from      date          NOT NULL,
  effective_to        date          NULL,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  created_by          uuid          NULL,
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  updated_by          uuid          NULL,
  version             integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_employee_compensations PRIMARY KEY (id),
  CONSTRAINT uq_employee_compensations__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_employee_compensations__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_employee_compensations__pay_schedules
    FOREIGN KEY (company_id, pay_schedule_id) REFERENCES payroll.pay_schedules (company_id, id),
  CONSTRAINT fk_employee_compensations__salary_structures
    FOREIGN KEY (company_id, salary_structure_id) REFERENCES payroll.salary_structures (company_id, id),
  CONSTRAINT fk_employee_compensations__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_employee_compensations__base_amount CHECK (base_amount >= 0),
  CONSTRAINT ck_employee_compensations__dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT ck_employee_compensations__version CHECK (version >= 0),
  CONSTRAINT ex_employee_compensations__no_overlap
    EXCLUDE USING gist (employee_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);

CREATE INDEX ix_employee_compensations__company_id_employee_id ON payroll.employee_compensations (company_id, employee_id);
CREATE INDEX ix_employee_compensations__company_id_pay_schedule_id
  ON payroll.employee_compensations (company_id, pay_schedule_id);
CREATE INDEX ix_employee_compensations__company_id_salary_structure_id
  ON payroll.employee_compensations (company_id, salary_structure_id);
CREATE INDEX ix_employee_compensations__currency_code ON payroll.employee_compensations (currency_code);

SELECT platform.enable_company_rls('payroll.employee_compensations');

CREATE TABLE payroll.employee_component_overrides (
  id                       uuid          NOT NULL DEFAULT uuidv7(),
  company_id               uuid          NOT NULL,
  employee_compensation_id uuid          NOT NULL,
  component_id             uuid          NOT NULL,
  rate                     numeric(19,6) NULL,
  amount                   numeric(19,4) NULL,
  created_at               timestamptz   NOT NULL DEFAULT now(),
  created_by               uuid          NULL,
  CONSTRAINT pk_employee_component_overrides PRIMARY KEY (id),
  CONSTRAINT uq_employee_component_overrides__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_employee_component_overrides__compensation_component UNIQUE (employee_compensation_id, component_id),
  CONSTRAINT fk_employee_component_overrides__employee_compensations
    FOREIGN KEY (company_id, employee_compensation_id) REFERENCES payroll.employee_compensations (company_id, id)
      ON DELETE CASCADE,
  CONSTRAINT fk_employee_component_overrides__pay_components
    FOREIGN KEY (company_id, component_id) REFERENCES payroll.pay_components (company_id, id),
  CONSTRAINT ck_employee_component_overrides__value CHECK (rate IS NOT NULL OR amount IS NOT NULL),
  CONSTRAINT ck_employee_component_overrides__rate CHECK (rate IS NULL OR rate >= 0),
  CONSTRAINT ck_employee_component_overrides__amount CHECK (amount IS NULL OR amount >= 0)
);

CREATE INDEX ix_employee_component_overrides__company_id_component_id
  ON payroll.employee_component_overrides (company_id, component_id);

SELECT platform.enable_company_rls('payroll.employee_component_overrides');

-- --------------------------------------------------------------------------- payroll runs
CREATE TABLE payroll.payroll_runs (
  id                          uuid          NOT NULL DEFAULT uuidv7(),
  company_id                  uuid          NOT NULL,
  number                      text          NULL,
  payroll_period_id           uuid          NOT NULL,
  run_type                    text          NOT NULL,
  description                 text          NULL,
  status                      text          NOT NULL DEFAULT 'DRAFT',
  accounting_date             date          NOT NULL,
  currency_code               char(3)       NOT NULL,
  employee_count              integer       NOT NULL DEFAULT 0,
  gross_total                 numeric(19,4) NOT NULL DEFAULT 0,
  deduction_total             numeric(19,4) NOT NULL DEFAULT 0,
  employer_contribution_total numeric(19,4) NOT NULL DEFAULT 0,
  net_total                   numeric(19,4) NOT NULL DEFAULT 0,
  calculation_requested_by    uuid          NULL,
  calculated_at               timestamptz   NULL,
  calculated_by               uuid          NULL,
  approved_at                 timestamptz   NULL,
  approved_by                 uuid          NULL,
  posted_at                   timestamptz   NULL,
  posted_by                   uuid          NULL,
  paid_at                     timestamptz   NULL,
  paid_by                     uuid          NULL,
  payment_date                date          NULL,
  -- accounting.bank_accounts.id: an Accounting-owned ID without FK (ARCHITECTURE.md §7).
  payment_bank_account_id     uuid          NULL,
  cancelled_at                timestamptz   NULL,
  created_at                  timestamptz   NOT NULL DEFAULT now(),
  created_by                  uuid          NULL,
  updated_at                  timestamptz   NOT NULL DEFAULT now(),
  updated_by                  uuid          NULL,
  version                     integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_payroll_runs PRIMARY KEY (id),
  CONSTRAINT uq_payroll_runs__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_payroll_runs__company_id_number UNIQUE (company_id, number),
  CONSTRAINT fk_payroll_runs__payroll_periods
    FOREIGN KEY (company_id, payroll_period_id) REFERENCES payroll.payroll_periods (company_id, id),
  CONSTRAINT fk_payroll_runs__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT ck_payroll_runs__run_type CHECK (run_type IN ('REGULAR', 'OFF_CYCLE', 'FINAL_SETTLEMENT')),
  CONSTRAINT ck_payroll_runs__status
    CHECK (status IN ('DRAFT', 'CALCULATING', 'CALCULATED', 'APPROVED', 'POSTED', 'PAID', 'CANCELLED')),
  CONSTRAINT ck_payroll_runs__net CHECK (net_total = gross_total - deduction_total),
  CONSTRAINT ck_payroll_runs__totals
    CHECK (gross_total >= 0 AND deduction_total >= 0 AND employer_contribution_total >= 0 AND employee_count >= 0),
  CONSTRAINT ck_payroll_runs__posted CHECK ((status IN ('POSTED', 'PAID')) = (number IS NOT NULL AND posted_at IS NOT NULL)),
  CONSTRAINT ck_payroll_runs__paid
    CHECK ((status = 'PAID') = (paid_at IS NOT NULL AND payment_date IS NOT NULL AND payment_bank_account_id IS NOT NULL)),
  CONSTRAINT ck_payroll_runs__approved CHECK ((status IN ('APPROVED', 'POSTED', 'PAID')) <= (approved_at IS NOT NULL)),
  CONSTRAINT ck_payroll_runs__description CHECK (description IS NULL OR length(description) <= 200),
  CONSTRAINT ck_payroll_runs__version CHECK (version >= 0)
);

-- PAY-5: one regular run per period unless cancelled.
CREATE UNIQUE INDEX uq_payroll_runs__regular ON payroll.payroll_runs (payroll_period_id)
  WHERE run_type = 'REGULAR' AND status <> 'CANCELLED';
CREATE INDEX ix_payroll_runs__company_id_payroll_period_id ON payroll.payroll_runs (company_id, payroll_period_id);
CREATE INDEX ix_payroll_runs__currency_code ON payroll.payroll_runs (currency_code);
-- The calculation job picks queued runs.
CREATE INDEX ix_payroll_runs__calculating ON payroll.payroll_runs (calculated_at) WHERE status = 'CALCULATING';

SELECT platform.enable_company_rls('payroll.payroll_runs');

-- PAY-4: an approved run changes only along its lifecycle; posted and paid runs are immutable apart
-- from the payment columns of the POSTED → PAID step.
CREATE FUNCTION payroll.guard_run()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, payroll
AS $$
DECLARE
  v_mutable text[] := ARRAY['status', 'updated_at', 'updated_by', 'version'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'payroll run % is % and cannot be deleted', OLD.id, OLD.status
        USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_payroll_runs__frozen', TABLE = 'payroll_runs',
              SCHEMA = 'payroll';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status = 'APPROVED' AND NEW.status IN ('CALCULATED', 'POSTED') THEN
    v_mutable := v_mutable || ARRAY['approved_at', 'approved_by', 'number', 'posted_at', 'posted_by'];
  ELSIF OLD.status = 'POSTED' AND NEW.status = 'PAID' THEN
    v_mutable := v_mutable || ARRAY['paid_at', 'paid_by', 'payment_date', 'payment_bank_account_id'];
  ELSIF OLD.status IN ('APPROVED', 'POSTED', 'PAID', 'CANCELLED') THEN
    v_mutable := ARRAY[]::text[];
  ELSE
    RETURN NEW;
  END IF;
  IF (to_jsonb(NEW) - v_mutable) IS DISTINCT FROM (to_jsonb(OLD) - v_mutable) OR
     (cardinality(v_mutable) = 0 AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
    RAISE EXCEPTION 'payroll run % is % and cannot be changed', OLD.id, OLD.status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_payroll_runs__frozen', TABLE = 'payroll_runs',
            SCHEMA = 'payroll';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE EXECUTE ON FUNCTION payroll.guard_run() FROM PUBLIC;
CREATE TRIGGER trg_payroll_runs_frozen
  BEFORE UPDATE OR DELETE ON payroll.payroll_runs
  FOR EACH ROW EXECUTE FUNCTION payroll.guard_run();

-- ----------------------------------------------------------------------- payroll inputs
CREATE TABLE payroll.payroll_inputs (
  id                uuid          NOT NULL DEFAULT uuidv7(),
  company_id        uuid          NOT NULL,
  payroll_period_id uuid          NOT NULL,
  -- NULL: an input of the period's regular run; set: of that off-cycle run.
  payroll_run_id    uuid          NULL,
  employee_id       uuid          NOT NULL,
  component_id      uuid          NOT NULL,
  quantity          numeric(18,6) NULL,
  amount            numeric(19,4) NULL,
  note              text          NULL,
  created_at        timestamptz   NOT NULL DEFAULT now(),
  created_by        uuid          NULL,
  updated_at        timestamptz   NOT NULL DEFAULT now(),
  updated_by        uuid          NULL,
  version           integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_payroll_inputs PRIMARY KEY (id),
  CONSTRAINT uq_payroll_inputs__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_payroll_inputs__payroll_periods
    FOREIGN KEY (company_id, payroll_period_id) REFERENCES payroll.payroll_periods (company_id, id),
  CONSTRAINT fk_payroll_inputs__payroll_runs
    FOREIGN KEY (company_id, payroll_run_id) REFERENCES payroll.payroll_runs (company_id, id),
  CONSTRAINT fk_payroll_inputs__employees FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_payroll_inputs__pay_components
    FOREIGN KEY (company_id, component_id) REFERENCES payroll.pay_components (company_id, id),
  -- PAY-4: inputs are never negative; corrections use a dedicated deduction component.
  CONSTRAINT ck_payroll_inputs__value CHECK ((quantity IS NULL) <> (amount IS NULL)),
  CONSTRAINT ck_payroll_inputs__quantity CHECK (quantity IS NULL OR quantity > 0),
  CONSTRAINT ck_payroll_inputs__amount CHECK (amount IS NULL OR amount > 0),
  CONSTRAINT ck_payroll_inputs__note CHECK (note IS NULL OR length(note) <= 500),
  CONSTRAINT ck_payroll_inputs__version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_payroll_inputs__entry
  ON payroll.payroll_inputs (payroll_period_id, employee_id, component_id, coalesce(payroll_run_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX ix_payroll_inputs__company_id_payroll_run_id ON payroll.payroll_inputs (company_id, payroll_run_id);
CREATE INDEX ix_payroll_inputs__company_id_employee_id ON payroll.payroll_inputs (company_id, employee_id);
CREATE INDEX ix_payroll_inputs__company_id_component_id ON payroll.payroll_inputs (company_id, component_id);

SELECT platform.enable_company_rls('payroll.payroll_inputs');

-- ------------------------------------------------------------------------- run issues
-- PAY-2: employees the calculation could not pay (negative net, missing data); a run with issues
-- is not approved.
CREATE TABLE payroll.payroll_run_issues (
  id             uuid        NOT NULL DEFAULT uuidv7(),
  company_id     uuid        NOT NULL,
  payroll_run_id uuid        NOT NULL,
  employee_id    uuid        NULL,
  code           text        NOT NULL,
  message        text        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_payroll_run_issues PRIMARY KEY (id),
  CONSTRAINT fk_payroll_run_issues__payroll_runs
    FOREIGN KEY (company_id, payroll_run_id) REFERENCES payroll.payroll_runs (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_payroll_run_issues__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT ck_payroll_run_issues__code CHECK (code ~ '^[A-Z_]{2,40}$'),
  CONSTRAINT ck_payroll_run_issues__message CHECK (length(message) BETWEEN 1 AND 500)
);

CREATE INDEX ix_payroll_run_issues__company_id_payroll_run_id ON payroll.payroll_run_issues (company_id, payroll_run_id);
CREATE INDEX ix_payroll_run_issues__company_id_employee_id ON payroll.payroll_run_issues (company_id, employee_id);

SELECT platform.enable_company_rls('payroll.payroll_run_issues');

-- ------------------------------------------------------------------------------- payslips
CREATE TABLE payroll.payslips (
  id                           uuid          NOT NULL DEFAULT uuidv7(),
  company_id                   uuid          NOT NULL,
  payroll_run_id               uuid          NOT NULL,
  employee_id                  uuid          NOT NULL,
  -- Snapshots at calculation.
  employee_number              text          NOT NULL,
  employee_name                text          NOT NULL,
  branch_id                    uuid          NOT NULL,
  department_id                uuid          NOT NULL,
  position_title               text          NULL,
  days_paid                    integer       NOT NULL,
  days_in_period               integer       NOT NULL,
  base_amount                  numeric(19,4) NOT NULL,
  taxable_gross                numeric(19,4) NOT NULL,
  gross_amount                 numeric(19,4) NOT NULL,
  deduction_amount             numeric(19,4) NOT NULL,
  employer_contribution_amount numeric(19,4) NOT NULL,
  net_amount                   numeric(19,4) NOT NULL,
  currency_code                char(3)       NOT NULL,
  file_id                      uuid          NULL,
  created_at                   timestamptz   NOT NULL DEFAULT now(),
  created_by                   uuid          NULL,
  updated_at                   timestamptz   NOT NULL DEFAULT now(),
  updated_by                   uuid          NULL,
  version                      integer       NOT NULL DEFAULT 0,
  CONSTRAINT pk_payslips PRIMARY KEY (id),
  CONSTRAINT uq_payslips__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_payslips__payroll_run_id_employee_id UNIQUE (payroll_run_id, employee_id),
  CONSTRAINT fk_payslips__payroll_runs
    FOREIGN KEY (company_id, payroll_run_id) REFERENCES payroll.payroll_runs (company_id, id),
  CONSTRAINT fk_payslips__employees FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_payslips__branches FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_payslips__departments FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT fk_payslips__currencies FOREIGN KEY (currency_code) REFERENCES org.currencies (code),
  CONSTRAINT fk_payslips__files FOREIGN KEY (company_id, file_id) REFERENCES platform.files (company_id, id),
  CONSTRAINT ck_payslips__net CHECK (net_amount = gross_amount - deduction_amount),
  CONSTRAINT ck_payslips__net_not_negative CHECK (net_amount >= 0),
  CONSTRAINT ck_payslips__amounts
    CHECK (gross_amount >= 0 AND deduction_amount >= 0 AND employer_contribution_amount >= 0 AND base_amount >= 0
           AND taxable_gross >= 0 AND taxable_gross <= gross_amount),
  CONSTRAINT ck_payslips__days CHECK (days_paid BETWEEN 0 AND days_in_period AND days_in_period > 0),
  CONSTRAINT ck_payslips__version CHECK (version >= 0)
);

CREATE INDEX ix_payslips__company_id_employee_id ON payroll.payslips (company_id, employee_id);
CREATE INDEX ix_payslips__company_id_branch_id ON payroll.payslips (company_id, branch_id);
CREATE INDEX ix_payslips__company_id_department_id ON payroll.payslips (company_id, department_id);
CREATE INDEX ix_payslips__currency_code ON payroll.payslips (currency_code);
CREATE INDEX ix_payslips__company_id_file_id ON payroll.payslips (company_id, file_id);
-- The PDF job picks posted payslips without a file.
CREATE INDEX ix_payslips__pdf_pending ON payroll.payslips (payroll_run_id) WHERE file_id IS NULL;

SELECT platform.enable_company_rls('payroll.payslips');

CREATE TABLE payroll.payslip_lines (
  id             uuid          NOT NULL DEFAULT uuidv7(),
  company_id     uuid          NOT NULL,
  payslip_id     uuid          NOT NULL,
  component_id   uuid          NOT NULL,
  component_code text          NOT NULL,
  component_name text          NOT NULL,
  kind           text          NOT NULL,
  is_taxable     boolean       NOT NULL,
  quantity       numeric(18,6) NULL,
  rate           numeric(19,6) NULL,
  amount         numeric(19,4) NOT NULL,
  sequence       integer       NOT NULL,
  created_at     timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_payslip_lines PRIMARY KEY (id),
  CONSTRAINT uq_payslip_lines__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_payslip_lines__payslip_id_component_id UNIQUE (payslip_id, component_id),
  CONSTRAINT fk_payslip_lines__payslips
    FOREIGN KEY (company_id, payslip_id) REFERENCES payroll.payslips (company_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_payslip_lines__pay_components
    FOREIGN KEY (company_id, component_id) REFERENCES payroll.pay_components (company_id, id),
  CONSTRAINT ck_payslip_lines__kind CHECK (kind IN ('EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION')),
  CONSTRAINT ck_payslip_lines__amount CHECK (amount >= 0)
);

CREATE INDEX ix_payslip_lines__company_id_component_id ON payroll.payslip_lines (company_id, component_id);

SELECT platform.enable_company_rls('payroll.payslip_lines');

-- Payslips and their lines are replaced while a run is calculated and frozen from approval on;
-- a posted payslip only receives its PDF (file_id NULL → value).
CREATE FUNCTION payroll.guard_payslip()
  RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, payroll
AS $$
DECLARE
  v_run_id uuid;
  v_status text;
BEGIN
  IF TG_TABLE_NAME = 'payslips' THEN
    v_run_id := CASE WHEN TG_OP = 'INSERT' THEN NEW.payroll_run_id ELSE OLD.payroll_run_id END;
  ELSE
    SELECT payroll_run_id INTO v_run_id FROM payroll.payslips
     WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.payslip_id ELSE OLD.payslip_id END;
  END IF;
  SELECT status INTO v_status FROM payroll.payroll_runs WHERE id = v_run_id;
  IF v_status IS NULL OR v_status IN ('DRAFT', 'CALCULATING', 'CALCULATED', 'CANCELLED') THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF TG_TABLE_NAME = 'payslips' AND TG_OP = 'UPDATE' AND v_status IN ('POSTED', 'PAID') THEN
    IF (to_jsonb(OLD) ->> 'file_id') IS NULL
       AND (to_jsonb(NEW) - ARRAY['file_id', 'updated_at', 'updated_by', 'version'])
           = (to_jsonb(OLD) - ARRAY['file_id', 'updated_at', 'updated_by', 'version']) THEN
      RETURN NEW;
    END IF;
  END IF;
  RAISE EXCEPTION '% of payroll run % (%) cannot be changed', TG_TABLE_NAME, v_run_id, v_status
    USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '__frozen', TABLE = TG_TABLE_NAME,
          SCHEMA = 'payroll';
END;
$$;
REVOKE EXECUTE ON FUNCTION payroll.guard_payslip() FROM PUBLIC;
CREATE TRIGGER trg_payslips_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON payroll.payslips
  FOR EACH ROW EXECUTE FUNCTION payroll.guard_payslip();
CREATE TRIGGER trg_payslip_lines_frozen
  BEFORE INSERT OR UPDATE OR DELETE ON payroll.payslip_lines
  FOR EACH ROW EXECUTE FUNCTION payroll.guard_payslip();
