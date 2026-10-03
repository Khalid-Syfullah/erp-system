-- =====================================================================================
-- Administration: business audit trail (DATABASE.md §5.11, SECURITY.md §8).
-- Append-only, monthly partitions, company isolation by RLS with a global-access escape hatch
-- used only by system-administration code paths (DATABASE.md §3).
-- =====================================================================================

SELECT platform.setup_module_schema('admin');

-- Transaction-local flag set by CompanyScopedTransactionManager for @GlobalAccess handlers.
CREATE FUNCTION platform.global_access()
  RETURNS boolean
  LANGUAGE sql
  STABLE
  PARALLEL SAFE
AS $$
  SELECT coalesce(current_setting('app.global_access', true), '') = 'on'
$$;
REVOKE EXECUTE ON FUNCTION platform.global_access() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.global_access() TO erp_app, erp_reporting, erp_support;

CREATE TABLE admin.audit_log (
  id            uuid        NOT NULL DEFAULT uuidv7(),
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  company_id    uuid        NULL,
  actor_user_id uuid        NULL,
  actor_type    text        NOT NULL,
  api_token_id  uuid        NULL,
  action        text        NOT NULL,
  module        text        NOT NULL,
  entity_type   text        NULL,
  entity_id     uuid        NULL,
  entity_label  text        NULL,
  from_state    text        NULL,
  to_state      text        NULL,
  changes       jsonb       NULL,
  request_id    text        NULL,
  ip            inet        NULL,
  user_agent    text        NULL,
  CONSTRAINT pk_audit_log PRIMARY KEY (id, occurred_at),
  CONSTRAINT ck_audit_log__actor_type CHECK (actor_type IN ('USER', 'API_TOKEN', 'SYSTEM')),
  CONSTRAINT ck_audit_log__action CHECK (action ~ '^[A-Z][A-Z_]{1,49}$'),
  CONSTRAINT ck_audit_log__module CHECK (module ~ '^[a-z]{2,20}$'),
  CONSTRAINT ck_audit_log__entity_label CHECK (entity_label IS NULL OR length(entity_label) <= 200),
  CONSTRAINT ck_audit_log__user_agent CHECK (user_agent IS NULL OR length(user_agent) <= 512)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_log__company_id_occurred_at ON admin.audit_log (company_id, occurred_at DESC);
CREATE INDEX ix_audit_log__company_id_entity ON admin.audit_log (company_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_log__company_id_actor ON admin.audit_log (company_id, actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_log__occurred_at_brin ON admin.audit_log USING brin (occurred_at);

-- Append-only: the application may insert and read, never change or remove.
REVOKE UPDATE, DELETE, TRUNCATE ON admin.audit_log FROM erp_app;

CREATE FUNCTION admin.guard_audit_append_only()
  RETURNS trigger
  LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'admin.audit_log is append-only' USING ERRCODE = '42501';
END;
$$;
CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON admin.audit_log
  FOR EACH ROW EXECUTE FUNCTION admin.guard_audit_append_only();

ALTER TABLE admin.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin.audit_log FORCE ROW LEVEL SECURITY;
-- Company rows are visible in their company; rows without a company (logins, user and role
-- administration) and cross-company reads require global access. Inserts may always record a
-- global event, but never a company other than the active one.
CREATE POLICY company_isolation ON admin.audit_log
  USING (company_id = platform.current_company_id() OR platform.global_access())
  WITH CHECK (company_id IS NULL OR company_id = platform.current_company_id() OR platform.global_access());

-- Creates missing monthly partitions from last month to p_months_ahead months ahead (UTC
-- boundaries). SECURITY DEFINER so that the maintenance job (running as erp_app) can create
-- partitions without holding CREATE on the schema. Partitions get no direct grants: they are
-- reachable only through the parent table, where RLS applies.
CREATE FUNCTION admin.ensure_audit_partitions(p_months_ahead integer)
  RETURNS integer
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = pg_catalog, admin
AS $$
DECLARE
  v_month timestamptz;
  v_name text;
  v_created integer := 0;
BEGIN
  IF p_months_ahead < 0 OR p_months_ahead > 36 THEN
    RAISE EXCEPTION 'p_months_ahead must be between 0 and 36';
  END IF;
  FOR i IN -1..p_months_ahead LOOP
    v_month := date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' + make_interval(months => i);
    v_name := 'audit_log_' || to_char(v_month AT TIME ZONE 'UTC', 'YYYYMM');
    IF to_regclass('admin.' || v_name) IS NULL THEN
      EXECUTE format('CREATE TABLE admin.%I PARTITION OF admin.audit_log FOR VALUES FROM (%L) TO (%L)',
                     v_name, v_month, v_month + interval '1 month');
      EXECUTE format('REVOKE ALL ON admin.%I FROM erp_app, erp_reporting, erp_support', v_name);
      v_created := v_created + 1;
    END IF;
  END LOOP;
  RETURN v_created;
END;
$$;
REVOKE EXECUTE ON FUNCTION admin.ensure_audit_partitions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION admin.ensure_audit_partitions(integer) TO erp_app;

SELECT admin.ensure_audit_partitions(12);
