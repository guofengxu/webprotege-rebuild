# App Flyway location (`classpath:db/instances-app`)

Owned by `wp-instances-app`. Startup order (P6-57):

1. `InstanceSchema.migrate` → `classpath:db/instances` (kernel tables from `wp-instances`; history table `flyway_schema_history`)
2. Flyway here → application tables (history table `flyway_schema_history_app`; API keys from P6-58, idempotency from P6-64, …)

This directory is intentionally empty on P6-57; versioned SQL scripts start with P6-58.
