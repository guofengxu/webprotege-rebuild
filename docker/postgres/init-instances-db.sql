-- Local Compose (P6-61): second database on the same Postgres instance as the editor catalog.
-- Runs only on first volume init (empty wp-pg-data). See docs/ops/instances.md.

CREATE DATABASE wp_instances;

-- Projection read-only login used by wp-instances-app.
-- Schema grants for projection.* must run after wp-app Flyway creates the schema
-- (docker/postgres/grant-projection-reader.sql).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'wp_projection_reader') THEN
        CREATE ROLE wp_projection_reader LOGIN PASSWORD 'wp_projection_reader';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE wp TO wp_projection_reader;
