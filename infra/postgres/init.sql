-- Shared ChangeOps database initialisation
-- NOTICE: Role creation with password is handled by init.sh (reads POSTGRES_APP_PASSWORD env var).
-- This file handles only privilege grants that do not require credentials.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

GRANT CONNECT ON DATABASE changeops TO changeops_app;
GRANT USAGE ON SCHEMA public TO changeops_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO changeops_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO changeops_app;
