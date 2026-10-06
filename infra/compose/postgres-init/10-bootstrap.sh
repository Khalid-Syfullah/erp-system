#!/usr/bin/env bash
# Runs once, on first initialisation of the local PostgreSQL volume
# (docker-entrypoint-initdb.d). Creates the ERP roles and sets their local passwords.
set -euo pipefail

: "${ERP_DB_MIGRATOR_PASSWORD:?ERP_DB_MIGRATOR_PASSWORD must be set}"
: "${ERP_DB_APP_PASSWORD:?ERP_DB_APP_PASSWORD must be set}"
: "${ERP_DB_REPORTING_PASSWORD:?ERP_DB_REPORTING_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --file /erp-bootstrap/00-roles.sql

# Passwords are passed as psql variables, so they never appear in the SQL text or in logs.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migrator_password="$ERP_DB_MIGRATOR_PASSWORD" \
  -v app_password="$ERP_DB_APP_PASSWORD" \
  -v reporting_password="$ERP_DB_REPORTING_PASSWORD" <<'SQL'
ALTER ROLE erp_migrator PASSWORD :'migrator_password';
ALTER ROLE erp_app      PASSWORD :'app_password';
ALTER ROLE erp_reporting PASSWORD :'reporting_password';
SQL
