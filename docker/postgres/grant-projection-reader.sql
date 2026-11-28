-- Apply after wp-app has created schema projection (first healthy backend start).
-- Local Compose defaults; replace password outside development.
-- Usage:
--   docker compose exec -T postgres psql -U wp -d wp -f - < docker/postgres/grant-projection-reader.sql

GRANT USAGE ON SCHEMA projection TO wp_projection_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA projection TO wp_projection_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA projection
    GRANT SELECT ON TABLES TO wp_projection_reader;
