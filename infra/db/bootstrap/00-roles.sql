-- =====================================================================================
-- ERP database bootstrap: roles and database-level privileges (DATABASE.md §2.7).
--
-- Run ONCE per database by a privileged administrator (superuser, or the managed-DB
-- master user with CREATEROLE), connected to the target database. Idempotent.
--
-- Passwords are NOT set here. Set them per environment from the secret manager, e.g.
--   ALTER ROLE erp_migrator PASSWORD '<secret>';
--   ALTER ROLE erp_app      PASSWORD '<secret>';
-- Local development: infra/compose/postgres-init/10-bootstrap.sh
-- Integration tests:  com.erp.support.TestDatabase
-- =====================================================================================

DO $$
BEGIN
  -- Owns every schema and object. Never logs in.
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_owner') THEN
    CREATE ROLE erp_owner NOLOGIN;
  END IF;
  -- Runs Flyway migrations; acts as erp_owner via SET ROLE (spring.flyway.init-sqls).
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_migrator') THEN
    CREATE ROLE erp_migrator LOGIN;
  END IF;
  -- Application runtime role: DML only, subject to row-level security.
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_app') THEN
    CREATE ROLE erp_app LOGIN;
  END IF;
  -- Read-only reporting role (Phase 10).
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_reporting') THEN
    CREATE ROLE erp_reporting LOGIN;
  END IF;
  -- Break-glass read-only support role (SECURITY.md §12).
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_support') THEN
    CREATE ROLE erp_support LOGIN;
  END IF;
END
$$;

-- Hardening: none of the runtime roles may bypass RLS or administer the cluster.
ALTER ROLE erp_owner     NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE erp_migrator  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE erp_app       NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE erp_reporting NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE erp_support   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- The migrator may assume erp_owner so that every object it creates is owned by erp_owner.
GRANT erp_owner TO erp_migrator;

-- erp_support logs every statement it runs (DATABASE.md §2.7). Setting log_statement per role
-- needs superuser; on managed databases configure it through the provider's parameter tooling.
DO $$
BEGIN
  IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    ALTER ROLE erp_support SET log_statement = 'all';
  ELSE
    RAISE NOTICE 'Not a superuser: configure log_statement=all for erp_support via the database provider.';
  END IF;
END
$$;

DO $$
BEGIN
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO erp_owner, erp_migrator, erp_app, erp_reporting, erp_support',
                 current_database());
  -- CREATE on the database lets erp_owner create schemas and trusted extensions.
  EXECUTE format('GRANT CREATE ON DATABASE %I TO erp_owner', current_database());
END
$$;
