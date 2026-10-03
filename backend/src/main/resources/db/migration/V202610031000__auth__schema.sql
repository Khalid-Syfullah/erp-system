-- =====================================================================================
-- Auth module (DATABASE.md §5.3, SECURITY.md §3–§4): identities, credentials, sessions,
-- MFA, API tokens, RBAC and login throttling. No table here is company-scoped by RLS:
-- users are global identities; role_assignments / role_assignment_branches / api_tokens
-- carry company_id as a scope, not as ownership (documented RLS exemptions, DATABASE.md §3).
-- Secrets are never stored: passwords as Argon2id PHC strings, every token as SHA-256 hash,
-- TOTP secrets encrypted with AES-256-GCM (SECURITY.md §7).
-- =====================================================================================

SELECT platform.setup_module_schema('auth');

-- ----------------------------------------------------------------------------------- users
CREATE TABLE auth.users (
  id                  uuid        NOT NULL DEFAULT uuidv7(),
  email               citext      NOT NULL,
  display_name        text        NOT NULL,
  user_type           text        NOT NULL DEFAULT 'HUMAN',
  status              text        NOT NULL,
  password_hash       text        NULL,
  password_changed_at timestamptz NULL,
  mfa_enabled         boolean     NOT NULL DEFAULT false,
  failed_login_count  integer     NOT NULL DEFAULT 0,
  locked_until        timestamptz NULL,
  last_login_at       timestamptz NULL,
  is_system_admin     boolean     NOT NULL DEFAULT false,
  locale              text        NOT NULL DEFAULT 'en',
  timezone            text        NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          uuid        NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  updated_by          uuid        NULL,
  version             integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_users PRIMARY KEY (id),
  CONSTRAINT uq_users__email UNIQUE (email),
  CONSTRAINT ck_users__email_format CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' AND length(email) <= 254),
  CONSTRAINT ck_users__email_lower CHECK (email::text = lower(email::text)),
  CONSTRAINT ck_users__display_name CHECK (btrim(display_name) <> '' AND length(display_name) <= 200),
  CONSTRAINT ck_users__user_type CHECK (user_type IN ('HUMAN', 'SERVICE')),
  CONSTRAINT ck_users__status CHECK (status IN ('INVITED', 'ACTIVE', 'LOCKED', 'DISABLED')),
  CONSTRAINT ck_users__password_hash_format CHECK (password_hash IS NULL OR password_hash LIKE '$argon2id$%'),
  CONSTRAINT ck_users__service_no_password CHECK (user_type = 'HUMAN' OR password_hash IS NULL),
  CONSTRAINT ck_users__service_not_admin CHECK (user_type = 'HUMAN' OR NOT is_system_admin),
  CONSTRAINT ck_users__service_no_mfa CHECK (user_type = 'HUMAN' OR NOT mfa_enabled),
  CONSTRAINT ck_users__active_human_has_password
    CHECK (status <> 'ACTIVE' OR user_type = 'SERVICE' OR password_hash IS NOT NULL),
  CONSTRAINT ck_users__failed_login_count CHECK (failed_login_count >= 0),
  CONSTRAINT ck_users__locale CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),
  CONSTRAINT ck_users__version CHECK (version >= 0)
);

-- ------------------------------------------------------- single-use invite / reset tokens
CREATE TABLE auth.user_tokens (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  user_id    uuid        NOT NULL,
  purpose    text        NOT NULL,
  token_hash bytea       NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_user_tokens PRIMARY KEY (id),
  CONSTRAINT uq_user_tokens__token_hash UNIQUE (token_hash),
  CONSTRAINT fk_user_tokens__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_user_tokens__purpose CHECK (purpose IN ('INVITE', 'PASSWORD_RESET', 'EMAIL_VERIFY')),
  CONSTRAINT ck_user_tokens__token_hash CHECK (octet_length(token_hash) = 32),
  CONSTRAINT ck_user_tokens__expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_user_tokens__user_id_purpose ON auth.user_tokens (user_id, purpose);
CREATE INDEX ix_user_tokens__expires_at ON auth.user_tokens (expires_at);

-- ------------------------------------------------------------------------------ sessions
-- Server-side browser sessions (ADR-028). The cookie carries a 256-bit random token; only its
-- SHA-256 hash is stored, so a database read does not yield usable session credentials.
CREATE TABLE auth.sessions (
  id                      uuid        NOT NULL DEFAULT uuidv7(),
  token_hash              bytea       NOT NULL,
  user_id                 uuid        NOT NULL,
  created_at              timestamptz NOT NULL DEFAULT now(),
  last_seen_at            timestamptz NOT NULL DEFAULT now(),
  authenticated_at        timestamptz NOT NULL,
  reauthenticated_at      timestamptz NULL,
  absolute_expires_at     timestamptz NOT NULL,
  mfa_verified            boolean     NOT NULL DEFAULT false,
  mfa_enrollment_required boolean     NOT NULL DEFAULT false,
  ip                      inet        NULL,
  user_agent              text        NULL,
  CONSTRAINT pk_sessions PRIMARY KEY (id),
  CONSTRAINT uq_sessions__token_hash UNIQUE (token_hash),
  CONSTRAINT fk_sessions__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_sessions__token_hash CHECK (octet_length(token_hash) = 32),
  CONSTRAINT ck_sessions__absolute_expiry CHECK (absolute_expires_at > created_at),
  CONSTRAINT ck_sessions__user_agent CHECK (user_agent IS NULL OR length(user_agent) <= 512)
);
CREATE INDEX ix_sessions__user_id_created_at ON auth.sessions (user_id, created_at);
CREATE INDEX ix_sessions__absolute_expires_at ON auth.sessions (absolute_expires_at);

-- Pending second factor after a correct password (SECURITY.md §3.5): 5 minutes, 5 attempts.
CREATE TABLE auth.login_challenges (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  token_hash bytea       NOT NULL,
  user_id    uuid        NOT NULL,
  attempts   integer     NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  ip         inet        NULL,
  CONSTRAINT pk_login_challenges PRIMARY KEY (id),
  CONSTRAINT uq_login_challenges__token_hash UNIQUE (token_hash),
  CONSTRAINT fk_login_challenges__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_login_challenges__token_hash CHECK (octet_length(token_hash) = 32),
  CONSTRAINT ck_login_challenges__attempts CHECK (attempts BETWEEN 0 AND 5),
  CONSTRAINT ck_login_challenges__expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_login_challenges__expires_at ON auth.login_challenges (expires_at);

-- ---------------------------------------------------------------------------------- MFA
CREATE TABLE auth.mfa_totp (
  user_id            uuid        NOT NULL,
  secret_encrypted   bytea       NOT NULL,
  secret_key_version smallint    NOT NULL,
  confirmed_at       timestamptz NULL,
  last_used_step     bigint      NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_mfa_totp PRIMARY KEY (user_id),
  CONSTRAINT fk_mfa_totp__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_mfa_totp__key_version CHECK (secret_key_version > 0)
);

CREATE TABLE auth.recovery_codes (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  user_id    uuid        NOT NULL,
  code_hash  bytea       NOT NULL,
  used_at    timestamptz NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_recovery_codes PRIMARY KEY (id),
  CONSTRAINT uq_recovery_codes__user_id_code_hash UNIQUE (user_id, code_hash),
  CONSTRAINT fk_recovery_codes__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_recovery_codes__code_hash CHECK (octet_length(code_hash) = 32)
);

-- ---------------------------------------------------------------------------- API tokens
CREATE TABLE auth.api_tokens (
  id                    uuid        NOT NULL DEFAULT uuidv7(),
  user_id               uuid        NOT NULL,
  name                  text        NOT NULL,
  token_prefix          text        NOT NULL,
  token_hash            bytea       NOT NULL,
  company_id            uuid        NULL,
  allowed_permissions   text[]      NULL,
  rate_limit_per_minute integer     NULL,
  expires_at            timestamptz NOT NULL,
  last_used_at          timestamptz NULL,
  revoked_at            timestamptz NULL,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid        NULL,
  CONSTRAINT pk_api_tokens PRIMARY KEY (id),
  CONSTRAINT uq_api_tokens__token_hash UNIQUE (token_hash),
  CONSTRAINT uq_api_tokens__token_prefix UNIQUE (token_prefix),
  CONSTRAINT fk_api_tokens__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT fk_api_tokens__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_api_tokens__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_api_tokens__token_prefix CHECK (token_prefix ~ '^[A-Za-z0-9]{8}$'),
  CONSTRAINT ck_api_tokens__token_hash CHECK (octet_length(token_hash) = 32),
  CONSTRAINT ck_api_tokens__rate_limit CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute > 0),
  CONSTRAINT ck_api_tokens__expiry CHECK (expires_at > created_at AND expires_at <= created_at + interval '366 days')
);
CREATE INDEX ix_api_tokens__user_id ON auth.api_tokens (user_id);
CREATE INDEX ix_api_tokens__company_id ON auth.api_tokens (company_id);

-- ------------------------------------------------------------------------------- RBAC
CREATE TABLE auth.permissions (
  code          text        NOT NULL,
  module        text        NOT NULL,
  description   text        NOT NULL,
  is_sensitive  boolean     NOT NULL DEFAULT false,
  deprecated_at timestamptz NULL,
  CONSTRAINT pk_permissions PRIMARY KEY (code),
  CONSTRAINT ck_permissions__code_format CHECK (code ~ '^[a-z_]+\.[a-z_]+\.[a-z_]+$'),
  CONSTRAINT ck_permissions__module CHECK (code LIKE module || '.%')
);

CREATE TABLE auth.roles (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  code         text        NOT NULL,
  name         text        NOT NULL,
  description  text        NULL,
  is_system    boolean     NOT NULL DEFAULT false,
  requires_mfa boolean     NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid        NULL,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   uuid        NULL,
  version      integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_roles PRIMARY KEY (id),
  CONSTRAINT uq_roles__code UNIQUE (code),
  CONSTRAINT ck_roles__code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
  CONSTRAINT ck_roles__name CHECK (btrim(name) <> '' AND length(name) <= 100),
  CONSTRAINT ck_roles__description CHECK (description IS NULL OR length(description) <= 500),
  CONSTRAINT ck_roles__version CHECK (version >= 0)
);

CREATE TABLE auth.role_permissions (
  role_id         uuid NOT NULL,
  permission_code text NOT NULL,
  CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_code),
  CONSTRAINT fk_role_permissions__roles FOREIGN KEY (role_id) REFERENCES auth.roles (id) ON DELETE CASCADE,
  CONSTRAINT fk_role_permissions__permissions FOREIGN KEY (permission_code) REFERENCES auth.permissions (code)
);
CREATE INDEX ix_role_permissions__permission_code ON auth.role_permissions (permission_code);

CREATE TABLE auth.role_assignments (
  id         uuid        NOT NULL DEFAULT uuidv7(),
  user_id    uuid        NOT NULL,
  role_id    uuid        NOT NULL,
  company_id uuid        NOT NULL,
  valid_from date        NULL,
  valid_to   date        NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid        NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid        NULL,
  version    integer     NOT NULL DEFAULT 0,
  CONSTRAINT pk_role_assignments PRIMARY KEY (id),
  CONSTRAINT uq_role_assignments__id_company_id UNIQUE (id, company_id),
  CONSTRAINT uq_role_assignments__user_id_role_id_company_id UNIQUE (user_id, role_id, company_id),
  CONSTRAINT fk_role_assignments__users FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT fk_role_assignments__roles FOREIGN KEY (role_id) REFERENCES auth.roles (id),
  CONSTRAINT fk_role_assignments__companies FOREIGN KEY (company_id) REFERENCES org.companies (id),
  CONSTRAINT ck_role_assignments__validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from),
  CONSTRAINT ck_role_assignments__version CHECK (version >= 0)
);
CREATE INDEX ix_role_assignments__user_id_company_id ON auth.role_assignments (user_id, company_id);
CREATE INDEX ix_role_assignments__company_id ON auth.role_assignments (company_id);
CREATE INDEX ix_role_assignments__role_id ON auth.role_assignments (role_id);

-- No rows for an assignment = all branches of its company (SECURITY.md §4.1).
CREATE TABLE auth.role_assignment_branches (
  role_assignment_id uuid NOT NULL,
  company_id         uuid NOT NULL,
  branch_id          uuid NOT NULL,
  CONSTRAINT pk_role_assignment_branches PRIMARY KEY (role_assignment_id, branch_id),
  CONSTRAINT fk_role_assignment_branches__role_assignments
    FOREIGN KEY (role_assignment_id, company_id) REFERENCES auth.role_assignments (id, company_id) ON DELETE CASCADE,
  CONSTRAINT fk_role_assignment_branches__branches
    FOREIGN KEY (company_id, branch_id) REFERENCES org.branches (company_id, id)
);
CREATE INDEX ix_role_assignment_branches__company_id_branch_id ON auth.role_assignment_branches (company_id, branch_id);

-- ------------------------------------------------------------------- login protection
-- Append-only for the application; purged only through auth.purge_security_records().
CREATE TABLE auth.login_attempts (
  id             uuid        NOT NULL DEFAULT uuidv7(),
  user_id        uuid        NULL,
  email_hash     bytea       NOT NULL,
  ip             inet        NULL,
  succeeded      boolean     NOT NULL,
  failure_reason text        NULL,
  attempted_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_login_attempts PRIMARY KEY (id),
  CONSTRAINT ck_login_attempts__email_hash CHECK (octet_length(email_hash) = 32),
  CONSTRAINT ck_login_attempts__failure_reason CHECK (
    (succeeded AND failure_reason IS NULL)
    OR (NOT succeeded AND failure_reason IN (
      'INVALID_CREDENTIALS', 'UNKNOWN_USER', 'ACCOUNT_NOT_ACTIVE', 'ACCOUNT_LOCKED', 'LOCKOUT', 'INVALID_MFA_CODE',
      'RATE_LIMITED')))
);
CREATE INDEX ix_login_attempts__email_hash_attempted_at ON auth.login_attempts (email_hash, attempted_at DESC);
CREATE INDEX ix_login_attempts__ip_attempted_at ON auth.login_attempts (ip, attempted_at DESC);
CREATE INDEX ix_login_attempts__user_id_attempted_at ON auth.login_attempts (user_id, attempted_at DESC)
  WHERE user_id IS NOT NULL;
CREATE INDEX ix_login_attempts__attempted_at ON auth.login_attempts (attempted_at);
REVOKE UPDATE, DELETE, TRUNCATE ON auth.login_attempts FROM erp_app;

-- Throttle counters for other public actions (password reset requests, token redemption).
CREATE TABLE auth.throttle_events (
  id           uuid        NOT NULL DEFAULT uuidv7(),
  action       text        NOT NULL,
  subject_hash bytea       NOT NULL,
  occurred_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_throttle_events PRIMARY KEY (id),
  CONSTRAINT ck_throttle_events__action CHECK (action ~ '^[A-Z][A-Z_]{1,49}$'),
  CONSTRAINT ck_throttle_events__subject_hash CHECK (octet_length(subject_hash) = 32)
);
CREATE INDEX ix_throttle_events__action_subject_hash_occurred_at
  ON auth.throttle_events (action, subject_hash, occurred_at DESC);
CREATE INDEX ix_throttle_events__occurred_at ON auth.throttle_events (occurred_at);

-- ----------------------------------------------------------------------- housekeeping
-- Deletes expired security records. SECURITY DEFINER because erp_app must not hold DELETE on
-- the append-only login_attempts table; the retention arguments are bounded.
CREATE FUNCTION auth.purge_security_records(p_login_attempt_retention interval)
  RETURNS integer
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = pg_catalog, auth
AS $$
DECLARE
  v_deleted integer := 0;
  v_count integer;
BEGIN
  IF p_login_attempt_retention < interval '1 day' THEN
    RAISE EXCEPTION 'login attempt retention must be at least one day';
  END IF;
  DELETE FROM auth.sessions
   WHERE absolute_expires_at < now() OR last_seen_at < now() - interval '7 days';
  GET DIAGNOSTICS v_count = ROW_COUNT; v_deleted := v_deleted + v_count;
  DELETE FROM auth.login_challenges WHERE expires_at < now() - interval '1 day';
  GET DIAGNOSTICS v_count = ROW_COUNT; v_deleted := v_deleted + v_count;
  DELETE FROM auth.user_tokens WHERE expires_at < now() - interval '7 days';
  GET DIAGNOSTICS v_count = ROW_COUNT; v_deleted := v_deleted + v_count;
  DELETE FROM auth.throttle_events WHERE occurred_at < now() - interval '2 days';
  GET DIAGNOSTICS v_count = ROW_COUNT; v_deleted := v_deleted + v_count;
  DELETE FROM auth.login_attempts WHERE attempted_at < now() - p_login_attempt_retention;
  GET DIAGNOSTICS v_count = ROW_COUNT; v_deleted := v_deleted + v_count;
  RETURN v_deleted;
END;
$$;
REVOKE EXECUTE ON FUNCTION auth.purge_security_records(interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.purge_security_records(interval) TO erp_app;
