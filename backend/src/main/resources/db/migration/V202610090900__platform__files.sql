-- =====================================================================================
-- File metadata (ARCHITECTURE.md §8.6, ADR-039): employee documents and generated payslip PDFs.
-- The content lives in S3-compatible object storage under company/{companyId}/{module}/{entity}/{uuid};
-- this table records who owns the object, its type, size and SHA-256. The key is never derived from
-- a user's file name.
-- =====================================================================================

CREATE TABLE platform.files (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  company_id   uuid        NOT NULL,
  owner_module text        NOT NULL,
  entity_type  text        NOT NULL,
  entity_id    uuid        NOT NULL,
  file_name    text        NOT NULL,
  content_type text        NOT NULL,
  size_bytes   bigint      NOT NULL,
  sha256       char(64)    NOT NULL,
  storage_key  text        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid        NULL,
  CONSTRAINT pk_files PRIMARY KEY (id),
  CONSTRAINT uq_files__company_id_id UNIQUE (company_id, id),
  CONSTRAINT uq_files__storage_key UNIQUE (storage_key),
  CONSTRAINT fk_files__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_files__owner_module CHECK (owner_module ~ '^[a-z]{2,20}$'),
  CONSTRAINT ck_files__entity_type CHECK (entity_type ~ '^[a-z_]{2,40}$'),
  CONSTRAINT ck_files__file_name CHECK (btrim(file_name) <> '' AND length(file_name) <= 255),
  CONSTRAINT ck_files__content_type CHECK (length(content_type) BETWEEN 3 AND 100),
  CONSTRAINT ck_files__size CHECK (size_bytes > 0),
  CONSTRAINT ck_files__sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_files__storage_key CHECK (storage_key LIKE 'company/' || company_id || '/%')
);

CREATE INDEX ix_files__company_id_entity ON platform.files (company_id, owner_module, entity_type, entity_id);

SELECT platform.enable_company_rls('platform.files');
