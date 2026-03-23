#!/bin/bash
# Shared ChangeOps database initialisation — role creation
# Runs once on container first boot (before init.sql).
# Requires POSTGRES_APP_PASSWORD environment variable to be set.
set -e

if [ -z "${POSTGRES_APP_PASSWORD}" ]; then
  echo "ERROR: POSTGRES_APP_PASSWORD is not set. Cannot create changeops_app role." >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<-EOSQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'changeops_app') THEN
      CREATE ROLE changeops_app LOGIN PASSWORD '${POSTGRES_APP_PASSWORD}';
    END IF;
  END
  \$\$;
EOSQL
