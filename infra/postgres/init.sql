-- Shared ChangeOps database initialisation
-- Runs once on container first boot

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Create application role with least privilege
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'changeops_app') THEN
    CREATE ROLE changeops_app LOGIN PASSWORD 'changeops';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE changeops TO changeops_app;
GRANT USAGE ON SCHEMA public TO changeops_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO changeops_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO changeops_app;
