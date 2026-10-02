-- =====================================================================================
-- Platform bootstrap (DATABASE.md §1–§3).
-- Runs as erp_owner (Flyway init SQL: SET ROLE erp_owner). Roles come from
-- infra/db/bootstrap/00-roles.sql, which must have been applied to this database.
-- =====================================================================================

-- Required extensions. All are "trusted", so a role with CREATE on the database may create them.
-- They are pinned to schema public (on every role's default search_path); otherwise they would
-- land in the first schema of Flyway's search_path (platform).
CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS ltree WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- -------------------------------------------------------------------------------------
-- platform.setup_module_schema(schema): creates a module schema with the standard grants.
-- Every module's first migration calls it. erp_app gets DML on future tables through
-- default privileges; append-only tables must REVOKE UPDATE, DELETE explicitly.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION platform.setup_module_schema(p_schema name)
  RETURNS void
  LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = p_schema) THEN
    EXECUTE format('CREATE SCHEMA %I', p_schema);
  END IF;
  EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', p_schema);
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO erp_app, erp_reporting, erp_support', p_schema);
  EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE erp_owner IN SCHEMA %I '
                 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO erp_app', p_schema);
  EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE erp_owner IN SCHEMA %I '
                 'GRANT USAGE, SELECT ON SEQUENCES TO erp_app', p_schema);
  EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE erp_owner IN SCHEMA %I '
                 'REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC', p_schema);
END;
$$;

-- -------------------------------------------------------------------------------------
-- Request context, set per transaction by CompanyScopedTransactionManager with
-- set_config(..., true). Unset or empty means "no company": RLS then matches nothing.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION platform.current_company_id()
  RETURNS uuid
  LANGUAGE sql
  STABLE
  PARALLEL SAFE
AS $$
  SELECT nullif(current_setting('app.company_id', true), '')::uuid
$$;

CREATE FUNCTION platform.current_user_id()
  RETURNS uuid
  LANGUAGE sql
  STABLE
  PARALLEL SAFE
AS $$
  SELECT nullif(current_setting('app.user_id', true), '')::uuid
$$;

-- -------------------------------------------------------------------------------------
-- platform.enable_company_rls(table): standard company-isolation policy (DATABASE.md §3).
-- FORCE makes the policy apply to the table owner too.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION platform.enable_company_rls(p_table regclass)
  RETURNS void
  LANGUAGE plpgsql
AS $$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
  EXECUTE format('CREATE POLICY company_isolation ON %s '
                 'USING (company_id = platform.current_company_id()) '
                 'WITH CHECK (company_id = platform.current_company_id())', p_table);
END;
$$;

-- Administrative helpers are for migrations only.
REVOKE EXECUTE ON FUNCTION platform.setup_module_schema(name) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform.enable_company_rls(regclass) FROM PUBLIC;

-- Context functions are evaluated inside RLS policies as the querying role.
REVOKE EXECUTE ON FUNCTION platform.current_company_id() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform.current_user_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.current_company_id() TO erp_app, erp_reporting, erp_support;
GRANT EXECUTE ON FUNCTION platform.current_user_id() TO erp_app, erp_reporting, erp_support;

-- The platform schema itself (created by Flyway as erp_owner) gets the standard grants.
-- The Flyway history table was created before these default privileges existed, so the
-- runtime roles have no privileges on it; the explicit REVOKE documents that intent.
SELECT platform.setup_module_schema('platform');
REVOKE ALL ON TABLE platform.flyway_schema_history FROM erp_app, erp_reporting, erp_support;
