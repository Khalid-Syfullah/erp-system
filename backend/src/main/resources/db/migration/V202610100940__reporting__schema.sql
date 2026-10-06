-- =====================================================================================
-- Phase 10: Reporting module (ARCHITECTURE.md §4.1, DATABASE.md §5.11, ADR-040).
-- The module owns no transactional data: a catalogue of report definitions (seeded by
-- R__seed_reporting_report_definitions.sql from the code catalogue), saved report parameters and
-- asynchronous export jobs. Reports read the modules' v_rpt_* views as erp_reporting.
-- =====================================================================================

SELECT platform.setup_module_schema('reporting');

CREATE TABLE reporting.report_definitions (
  code             text   NOT NULL,
  name             text   NOT NULL,
  owner_module     text   NOT NULL,
  -- Auth permission codes, by value; all are required (ReportCatalogIntegrationTest checks them).
  permission_codes text[] NOT NULL,
  CONSTRAINT pk_report_definitions PRIMARY KEY (code),
  CONSTRAINT ck_report_definitions__code CHECK (code ~ '^[a-z][a-z0-9-]{1,62}$'),
  CONSTRAINT ck_report_definitions__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_report_definitions__owner_module
    CHECK (owner_module IN ('inventory', 'procurement', 'sales', 'accounting', 'hr', 'payroll')),
  CONSTRAINT ck_report_definitions__permission_codes CHECK (cardinality(permission_codes) BETWEEN 1 AND 3)
);
-- Reference data maintained only by migrations.
REVOKE INSERT, UPDATE, DELETE ON reporting.report_definitions FROM erp_app;

CREATE TABLE reporting.saved_reports (
  id          uuid        NOT NULL DEFAULT uuidv7(),
  company_id  uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  report_code text        NOT NULL,
  name        text        NOT NULL,
  parameters  jsonb       NOT NULL DEFAULT '{}',
  is_shared   boolean     NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid        NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid        NULL,
  version     integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_saved_reports PRIMARY KEY (id),
  CONSTRAINT uq_saved_reports__company_id_user_id_name UNIQUE (company_id, user_id, name),
  CONSTRAINT fk_saved_reports__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_saved_reports__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT fk_saved_reports__report_definitions FOREIGN KEY (report_code) REFERENCES reporting.report_definitions (code),
  CONSTRAINT ck_saved_reports__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_saved_reports__parameters CHECK (jsonb_typeof(parameters) = 'object' AND length(parameters::text) <= 4000),
  CONSTRAINT ck_saved_reports__version CHECK (version >= 0)
);
CREATE INDEX ix_saved_reports__company_id_user_id ON reporting.saved_reports (company_id, user_id);
CREATE INDEX ix_saved_reports__company_id_shared ON reporting.saved_reports (company_id) WHERE is_shared;
CREATE INDEX ix_saved_reports__report_code ON reporting.saved_reports (report_code);
SELECT platform.enable_company_rls('reporting.saved_reports');

CREATE TABLE reporting.export_jobs (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  company_id    uuid        NOT NULL,
  user_id       uuid        NOT NULL,
  report_code   text        NOT NULL,
  parameters    jsonb       NOT NULL DEFAULT '{}',
  format        text        NOT NULL,
  -- The requester's branch scope when the export was requested (NULL = all branches): the worker
  -- applies it, since it runs without the request.
  branch_scope  uuid[]      NULL,
  status        text        NOT NULL DEFAULT 'QUEUED',
  file_id       uuid        NULL,
  row_count     bigint      NULL,
  error_code    text        NULL,
  requested_at  timestamptz NOT NULL DEFAULT now(),
  started_at    timestamptz NULL,
  completed_at  timestamptz NULL,
  expires_at    timestamptz NULL,
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_export_jobs PRIMARY KEY (id),
  CONSTRAINT uq_export_jobs__company_id_id UNIQUE (company_id, id),
  CONSTRAINT fk_export_jobs__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT fk_export_jobs__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT fk_export_jobs__report_definitions FOREIGN KEY (report_code) REFERENCES reporting.report_definitions (code),
  CONSTRAINT fk_export_jobs__files FOREIGN KEY (company_id, file_id) REFERENCES platform.files (company_id, id),
  CONSTRAINT ck_export_jobs__format CHECK (format IN ('CSV', 'XLSX', 'PDF')),
  CONSTRAINT ck_export_jobs__status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
  CONSTRAINT ck_export_jobs__parameters CHECK (jsonb_typeof(parameters) = 'object' AND length(parameters::text) <= 4000),
  CONSTRAINT ck_export_jobs__succeeded
    CHECK ((status = 'SUCCEEDED') <= (file_id IS NOT NULL AND row_count IS NOT NULL AND expires_at IS NOT NULL)),
  CONSTRAINT ck_export_jobs__failed CHECK ((status = 'FAILED') = (error_code IS NOT NULL)),
  CONSTRAINT ck_export_jobs__row_count CHECK (row_count IS NULL OR row_count >= 0),
  CONSTRAINT ck_export_jobs__version CHECK (version >= 0)
);
CREATE INDEX ix_export_jobs__company_id_user_id_requested_at
  ON reporting.export_jobs (company_id, user_id, requested_at DESC);
CREATE INDEX ix_export_jobs__pending ON reporting.export_jobs (company_id, requested_at)
  WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_export_jobs__expiring ON reporting.export_jobs (company_id, expires_at) WHERE status = 'SUCCEEDED';
CREATE INDEX ix_export_jobs__company_id_file_id ON reporting.export_jobs (company_id, file_id);
CREATE INDEX ix_export_jobs__report_code ON reporting.export_jobs (report_code);
SELECT platform.enable_company_rls('reporting.export_jobs');

-- The reporting role reads views only; it never writes and never sees the module's own tables.
GRANT SELECT ON reporting.report_definitions TO erp_reporting;
