# App Flyway location (`classpath:db/instances-app`)

Owned by `wp-instances-app`. Startup order (P6-57):

1. `InstanceSchema.migrate` → `classpath:db/instances` (kernel tables from `wp-instances`; history table `flyway_schema_history`)
2. Flyway here → application tables (history table `flyway_schema_history_app`; API keys from P6-58, idempotency from P6-64, …)

Versioned scripts:

- `V1__api_keys.sql` (P6-58) — instance-store API keys
- `V2__…` — idempotency (P6-64), …
