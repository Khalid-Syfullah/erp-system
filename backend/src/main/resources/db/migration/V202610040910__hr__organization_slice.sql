-- =====================================================================================
-- HR, organizational slice (Phase 4, ADR-033): positions (designations / job titles), the
-- core employee record, effective-dated employment assignments (branch, department,
-- position, manager) and department heads. Logical model: DATABASE.md §5.9.
-- Sensitive personal data, the user link, bank accounts, documents and leave follow in Phase 9.
-- =====================================================================================

SELECT platform.setup_module_schema('hr');

-- ------------------------------------------------------------------------------ positions
CREATE TABLE hr.positions (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  company_id    uuid        NOT NULL,
  code          text        NOT NULL,
  title         text        NOT NULL,
  department_id uuid        NULL,
  grade         text        NULL,
  is_active     boolean     NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid        NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    uuid        NULL,
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_positions PRIMARY KEY (id),
  CONSTRAINT uq_positions__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_positions__company_id_code UNIQUE (company_id, code),
  CONSTRAINT fk_positions__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_positions__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT ck_positions__code_format CHECK (code ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_positions__title_not_blank CHECK (btrim(title) <> '' AND length(title) <= 100),
  CONSTRAINT ck_positions__grade CHECK (grade IS NULL OR (btrim(grade) <> '' AND length(grade) <= 20)),
  CONSTRAINT ck_positions__version CHECK (version >= 0)
);

CREATE INDEX ix_positions__company_id_department_id ON hr.positions (company_id, department_id);

SELECT platform.enable_company_rls('hr.positions');

-- ------------------------------------------------------------------------------ employees
CREATE TABLE hr.employees (
  id                 uuid        NOT NULL DEFAULT uuidv7(),
  company_id         uuid        NOT NULL,
  employee_number    text        NOT NULL,
  first_name         text        NOT NULL,
  last_name          text        NOT NULL,
  preferred_name     text        NULL,
  work_email         citext      NULL,
  hire_date          date        NOT NULL,
  termination_date   date        NULL,
  termination_reason text        NULL,
  status             text        NOT NULL DEFAULT 'ONBOARDING',
  created_at         timestamptz NOT NULL DEFAULT now(),
  created_by         uuid        NULL,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         uuid        NULL,
  version            integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_employees PRIMARY KEY (id),
  CONSTRAINT uq_employees__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_employees__company_id_employee_number UNIQUE (company_id, employee_number),
  CONSTRAINT fk_employees__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_employees__employee_number_format CHECK (employee_number ~ '^[A-Z0-9_-]{1,30}$'),
  CONSTRAINT ck_employees__first_name_not_blank CHECK (btrim(first_name) <> '' AND length(first_name) <= 100),
  CONSTRAINT ck_employees__last_name_not_blank CHECK (btrim(last_name) <> '' AND length(last_name) <= 100),
  CONSTRAINT ck_employees__preferred_name
    CHECK (preferred_name IS NULL OR (btrim(preferred_name) <> '' AND length(preferred_name) <= 100)),
  CONSTRAINT ck_employees__work_email_format
    CHECK (work_email IS NULL OR (work_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
                                  AND length(work_email) <= 254 AND work_email::text = lower(work_email::text))),
  CONSTRAINT ck_employees__status CHECK (status IN ('ONBOARDING', 'ACTIVE', 'ON_LEAVE', 'TERMINATED')),
  CONSTRAINT ck_employees__termination_after_hire CHECK (termination_date IS NULL OR termination_date >= hire_date),
  CONSTRAINT ck_employees__terminated_has_date CHECK ((status = 'TERMINATED') = (termination_date IS NOT NULL)),
  CONSTRAINT ck_employees__termination_reason
    CHECK (termination_reason IS NULL OR (termination_date IS NOT NULL AND length(termination_reason) <= 500)),
  CONSTRAINT ck_employees__version CHECK (version >= 0)
);

-- One work email per company (a second record with the same address is a data-entry error).
CREATE UNIQUE INDEX uq_employees__company_id_work_email ON hr.employees (company_id, work_email)
  WHERE work_email IS NOT NULL;
CREATE INDEX ix_employees__company_id_status ON hr.employees (company_id, status);

SELECT platform.enable_company_rls('hr.employees');

-- ------------------------------------------------------------------ employment assignments
CREATE TABLE hr.employment_assignments (
  id                  uuid         NOT NULL DEFAULT uuidv7(),
  company_id          uuid         NOT NULL,
  employee_id         uuid         NOT NULL,
  branch_id           uuid         NOT NULL,
  department_id       uuid         NOT NULL,
  position_id         uuid         NULL,
  manager_employee_id uuid         NULL,
  employment_type     text         NOT NULL DEFAULT 'FULL_TIME',
  fte                 numeric(5,4) NOT NULL DEFAULT 1,
  effective_from      date         NOT NULL,
  effective_to        date         NULL,
  created_at          timestamptz  NOT NULL DEFAULT now(),
  created_by          uuid         NULL,
  updated_at          timestamptz  NOT NULL DEFAULT now(),
  updated_by          uuid         NULL,
  version             integer      NOT NULL DEFAULT 0,
  CONSTRAINT pk_employment_assignments PRIMARY KEY (id),
  CONSTRAINT uq_employment_assignments__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_employment_assignments__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_employment_assignments__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT fk_employment_assignments__branches
    FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id),
  CONSTRAINT fk_employment_assignments__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT fk_employment_assignments__positions
    FOREIGN KEY (company_id, position_id) REFERENCES hr.positions (company_id, id),
  CONSTRAINT fk_employment_assignments__employees_manager
    FOREIGN KEY (company_id, manager_employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT ck_employment_assignments__not_own_manager
    CHECK (manager_employee_id IS NULL OR manager_employee_id <> employee_id),
  CONSTRAINT ck_employment_assignments__employment_type
    CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN', 'TEMPORARY')),
  CONSTRAINT ck_employment_assignments__fte CHECK (fte > 0 AND fte <= 1),
  CONSTRAINT ck_employment_assignments__effective_range CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT ck_employment_assignments__version CHECK (version >= 0),
  -- HR-1: assignments of one employee never overlap.
  CONSTRAINT ex_employment_assignments__no_overlap
    EXCLUDE USING gist (employee_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);

CREATE INDEX ix_employment_assignments__company_id_employee_id ON hr.employment_assignments (company_id, employee_id);
CREATE INDEX ix_employment_assignments__company_id_branch_id ON hr.employment_assignments (company_id, branch_id);
CREATE INDEX ix_employment_assignments__company_id_department_id
  ON hr.employment_assignments (company_id, department_id);
CREATE INDEX ix_employment_assignments__company_id_position_id ON hr.employment_assignments (company_id, position_id);
CREATE INDEX ix_employment_assignments__company_id_manager_employee_id
  ON hr.employment_assignments (company_id, manager_employee_id);

SELECT platform.enable_company_rls('hr.employment_assignments');

-- ------------------------------------------------------------------------ department heads
CREATE TABLE hr.department_heads (
  id             uuid        NOT NULL DEFAULT uuidv7(),
  company_id     uuid        NOT NULL,
  department_id  uuid        NOT NULL,
  employee_id    uuid        NOT NULL,
  effective_from date        NOT NULL,
  effective_to   date        NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid        NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     uuid        NULL,
  version        integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_department_heads PRIMARY KEY (id),
  CONSTRAINT uq_department_heads__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_department_heads__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_department_heads__departments
    FOREIGN KEY (company_id, department_id) REFERENCES org.departments (company_id, id),
  CONSTRAINT fk_department_heads__employees
    FOREIGN KEY (company_id, employee_id) REFERENCES hr.employees (company_id, id),
  CONSTRAINT ck_department_heads__effective_range CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT ck_department_heads__version CHECK (version >= 0),
  -- One head per department at any date.
  CONSTRAINT ex_department_heads__no_overlap
    EXCLUDE USING gist (department_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);

CREATE INDEX ix_department_heads__company_id_department_id ON hr.department_heads (company_id, department_id);
CREATE INDEX ix_department_heads__company_id_employee_id ON hr.department_heads (company_id, employee_id);

SELECT platform.enable_company_rls('hr.department_heads');
