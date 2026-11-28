# Instance-store service operations (`wp-instances-app`)

Ops notes for the independent instance-store service (ADR-013 / P6-34 decision 6 / P6-61). API auth is documented in [`docs/api/instances.md`](../api/instances.md); projection read access in [`docs/api/projection.md`](../api/projection.md).

## Local vs non-development

| | Local Compose | Non-development |
|---|---|---|
| Postgres | **Same instance** as the editor catalog; second database `wp_instances` | **Separate Postgres instance** (and preferably separate host / cluster) |
| Backup | Shared volume `wp-pg-data` is fine for laptop use | **Independent backup** of the instance-store instance; do not share restore windows with the editor catalog |
| Secrets | Compose defaults are for development only | Inject pepper, API keys, and DB passwords from a secret manager; never bake them into images or git |

Non-development should treat editor and instance-store data planes as failure domains that can be restored independently.

## Local Compose layout

Root `compose.yaml` starts:

1. `postgres` — editor DB `wp` plus init script `docker/postgres/init-instances-db.sql` (`CREATE DATABASE wp_instances`, role `wp_projection_reader`).
2. `backend` / `frontend` — unchanged editor stack.
3. `instances` — builds `docker/instances/Dockerfile`, binds `127.0.0.1:8082:8082`.

Build jars on the host before `docker compose build`:

```bash
./mvnw -pl :wp-app,:wp-instances-app -am -DskipTests package
docker compose -f compose.yaml up --build
```

Init scripts run **only** when the Postgres volume is empty. If `wp-pg-data` already exists without `wp_instances`, either recreate the volume or create the database manually:

```bash
docker compose exec postgres psql -U wp -d wp -c 'CREATE DATABASE wp_instances'
```

### Projection reader grants (once per fresh volume)

`wp-app` Flyway creates schema `projection`. After the backend is healthy the first time:

```bash
docker compose exec -T postgres psql -U wp -d wp < docker/postgres/grant-projection-reader.sql
```

Until grants exist, readiness can still be UP (`SELECT 1`), but rule loads that query `projection.*` will fail.

### Local environment (defaults are development-only)

| Env | Purpose |
|---|---|
| `WP_INSTANCES_DATASOURCE_*` | Instance DB (`jdbc:postgresql://postgres:5432/wp_instances`) |
| `WP_PROJECTION_DATASOURCE_*` | Editor DB as `wp_projection_reader` (read-only after grants) |
| `WP_INSTANCES_PROJECT_ID` | Single-tenant project UUID |
| `WP_INSTANCES_API_KEY_PEPPER` | HMAC pepper for API key hashes |
| `WP_INSTANCES_BOOTSTRAP_API_KEY` | **Dev only** one-shot plaintext key insert when no active key exists |

Clear `WP_INSTANCES_BOOTSTRAP_API_KEY` after the first successful start outside pure local experiments. Production must not leave bootstrap plaintext in process config (see `docs/api/instances.md`).

## Health and readiness

Anonymous Actuator probes (port **8082**):

| Probe | URL | Meaning |
|---|---|---|
| Liveness | `GET /actuator/health/liveness` | Process only — databases must not gate restarts |
| Readiness | `GET /actuator/health/readiness` | Instance DB **and** projection DB both accept `SELECT 1` |

Example:

```bash
curl -sS http://127.0.0.1:8082/actuator/health/liveness
curl -sS http://127.0.0.1:8082/actuator/health/readiness
```

Orchestrators should restart on failed liveness and stop sending traffic on failed readiness.

## Projection read-only account wiring

1. Create login `wp_projection_reader` (Compose init does this locally; elsewhere use the DBA script in `docs/api/projection.md`).
2. Point `wp.projection.datasource.*` / `WP_PROJECTION_DATASOURCE_*` at the **editor** catalog host/database with that login.
3. Grant `USAGE` on schema `projection` and `SELECT` on its tables (and default privileges for future tables).
4. Do **not** grant write privileges; the instance service must not mutate editor or projection tables.

## Backup

- **Local:** `docker compose` volume `wp-pg-data` holds both `wp` and `wp_instances`. Snapshotting the volume is enough for laptop recovery.
- **Non-development:** back up the instance-store Postgres **separately** from the editor (own schedule, retention, and restore drills). Restoring the editor must not silently roll back instance rows, and vice versa.
- Prefer logical dumps (`pg_dump`) or the platform’s continuous backup; keep API key pepper and DB credentials in the secret store, not in the dump notes.

## Monitoring

Watch at least:

- Readiness / liveness probe success rate.
- Application ERROR logs from `wp-instances-app` (see below).
- Instance DB connectivity and disk / WAL for the instance-store instance.
- Editor projection lag via admin status (`GET /api/admin/projections/{projectId}` → `lag`) when diagnosing write-gate failures.

### Projection lag and `contract_version` mismatch

When writes fail because rules cannot load:

1. Check instance-service **ERROR** logs for `projection contract_version mismatch` (expected vs actual). A mismatch means editor and instance-store constants diverged; fix by deploying matching versions (see P6-55 / `ProjectionContract.EXPECTED_VERSION`).
2. If the contract matches but rules look stale, inspect projection **lag** (`project_state.rev_no` behind editor head) and editor outbox / projection rebuild paths. The instance service polls `rev_no`; it does not consume Kafka.

## Ingress rate limiting

The service authenticates with `X-API-Key` and has no built-in per-key quota in this phase. **Put a reverse proxy (or API gateway) in front of `/api/`** and rate-limit by API key header and/or client IP before traffic reaches the JVM. Keep Actuator health paths reachable for the orchestrator (not publicly exposed).

## Bootstrap warning

`WP_INSTANCES_BOOTSTRAP_API_KEY` inserts a hashed key only when no active row exists. It is a **development / first-boot** convenience. In shared or production environments:

1. Prefer inserting keys with the SQL procedure in `docs/api/instances.md`.
2. Never commit real pepper or bootstrap plaintext.
3. Unset bootstrap after first use so plaintext is not left in the environment.
