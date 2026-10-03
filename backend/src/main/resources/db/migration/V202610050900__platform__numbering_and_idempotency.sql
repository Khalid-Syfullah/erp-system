-- =====================================================================================
-- Platform kernel pieces carried over to Phase 5 (ADR-025, ADR-033):
--   * gapless document numbering per company, document type and fiscal year (ADR-012)
--   * configurable number formats per company (PRODUCT_SPEC.md G-6)
--   * Idempotency-Key storage (API.md §10)
-- =====================================================================================

-- ----------------------------------------------------------------- document numbering
CREATE TABLE platform.document_sequences (
  company_id    uuid        NOT NULL,
  document_type text        NOT NULL,
  scope_key     text        NOT NULL,
  prefix        text        NOT NULL,
  next_value    bigint      NOT NULL DEFAULT 1,
  padding       smallint    NOT NULL DEFAULT 6,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_document_sequences PRIMARY KEY (company_id, document_type, scope_key),
  CONSTRAINT fk_document_sequences__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_document_sequences__document_type CHECK (document_type ~ '^[A-Z][A-Z0-9_:]{1,49}$'),
  CONSTRAINT ck_document_sequences__scope_key CHECK (scope_key ~ '^[A-Z0-9_-]{1,20}$'),
  CONSTRAINT ck_document_sequences__next_value CHECK (next_value >= 1),
  CONSTRAINT ck_document_sequences__padding CHECK (padding BETWEEN 1 AND 12)
);

SELECT platform.enable_company_rls('platform.document_sequences');

-- One row per company: { "<DOCUMENT_TYPE>": {"prefix": "SM-{FY}-", "padding": 6}, ... }.
-- Types without an entry use the defaults defined in code (DocumentNumberService).
CREATE TABLE platform.numbering_settings (
  company_id uuid        NOT NULL,
  formats    jsonb       NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_numbering_settings PRIMARY KEY (company_id),
  CONSTRAINT fk_numbering_settings__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_numbering_settings__formats CHECK (jsonb_typeof(formats) = 'object'),
  CONSTRAINT ck_numbering_settings__version CHECK (version >= 0)
);

SELECT platform.enable_company_rls('platform.numbering_settings');

-- --------------------------------------------------------------------- idempotency keys
-- A key belongs to one principal (the authenticated user). Rows are only ever committed as
-- COMPLETED: the IN_PROGRESS row of a running request is uncommitted, so a concurrent duplicate
-- blocks on it and then sees the outcome (API.md §10).
CREATE TABLE platform.idempotency_keys (
  user_id          uuid        NOT NULL,
  idem_key         text        NOT NULL,
  request_hash     bytea       NOT NULL,
  status           text        NOT NULL,
  response_status  smallint    NULL,
  response_headers jsonb       NULL,
  -- json, not jsonb: a replay returns the stored body byte for byte (jsonb would reorder keys).
  response_body    json        NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz NOT NULL,
  CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, idem_key),
  CONSTRAINT ck_idempotency_keys__idem_key CHECK (length(idem_key) BETWEEN 8 AND 128 AND idem_key ~ '^[A-Za-z0-9_.:-]+$'),
  CONSTRAINT ck_idempotency_keys__request_hash CHECK (octet_length(request_hash) = 32),
  CONSTRAINT ck_idempotency_keys__status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  CONSTRAINT ck_idempotency_keys__completed_response
    CHECK (status = 'IN_PROGRESS' OR response_status BETWEEN 100 AND 599),
  CONSTRAINT ck_idempotency_keys__expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_keys__expires_at ON platform.idempotency_keys (expires_at);
