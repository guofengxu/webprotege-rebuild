# Instance-store HTTP API (`wp-instances-app`)

Independent Spring Boot service for the ontology instance store (ADR-013 / P6-57+). Default port **8082**. This document covers authentication introduced in P6-58; write/read routes land in later cards.

## Authentication

- Request header: `X-API-Key: <plaintext>`
- Keys live only in the **instance-store** database (`wp_instances`), table `api_keys`.
- The service stores **HMAC-SHA256** digests (lowercase hex) keyed by `wp.instances.api-key-pepper`. Plaintext is never persisted.
- Missing, wrong, or revoked keys → **401** on `/api/**`.
- Actuator **liveness** (`/actuator/health/liveness`) remains anonymous for orchestrators. Readiness is also anonymous so probes work; it still requires both databases.

There is **no** user login, session cookie, or editor Bearer token on this service.

### Not the editor API key

| | Editor (`wp-app` / `access`) | Instance store (`wp-instances-app`) |
|---|---|---|
| Header | `X-API-Key` | `X-API-Key` (same name, different keys) |
| Database | Editor catalog (`api_keys` next to `users`) | Instance DB (`api_keys` via app Flyway `db/instances-app`) |
| Hash | SHA-256 of plaintext (no pepper) | HMAC-SHA256 with `wp.instances.api-key-pepper` |
| Issuance | User/admin REST in the web app | Bootstrap property or ops SQL only (no management REST in this phase) |

Editor keys **cannot** call the instance service, and instance keys **cannot** call the editor.

## Single tenant

Configure exactly one ontology project:

```yaml
wp:
  instances:
    project-id: 11111111-2222-3333-4444-555555555555
```

Path `{projectId}` on `/api/v1/projects/{projectId}/...` must equal that UUID; otherwise **403** (after authentication).

Placeholder for auth checks:

```http
GET /api/v1/projects/{projectId}/ping
X-API-Key: <plaintext>
```

- Matching project + valid key → **200**
- Valid key + other project id → **403**
- No/invalid key → **401**

## Creating the first key (bootstrap)

Set both pepper and a one-time bootstrap plaintext (env vars preferred):

```bash
export WP_INSTANCES_API_KEY_PEPPER='…long random…'
export WP_INSTANCES_BOOTSTRAP_API_KEY='…long random plaintext…'
```

On startup, if `api_keys` has **no** row with `revoked_at is null`, the service inserts one named `bootstrap` with the hashed plaintext. If an active key already exists, bootstrap is skipped.

After the first successful start in a real environment, **clear** `WP_INSTANCES_BOOTSTRAP_API_KEY` / `wp.instances.bootstrap-api-key` so the plaintext is not left in process config.

## Ops: rotate or revoke via SQL

Connect to the instance-store database (not the editor catalog).

### Hash algorithm

`key_hash` = lowercase hex of `HMAC-SHA256(key = UTF-8(pepper), message = UTF-8(plaintext))`.

Compute outside the database (OpenSSL example; replace pepper and plaintext):

```bash
printf '%s' 'PLAINTEXT' | openssl dgst -sha256 -hmac 'PEPPER' | awk '{print $2}'
```

### Insert a new key

```sql
insert into api_keys (id, name, key_hash, created_at, revoked_at)
values (
  gen_random_uuid(),
  'ops-rotation',
  '<64-char lowercase hex from above>',
  now(),
  null
);
```

### Revoke a key

```sql
update api_keys
set revoked_at = now()
where id = '<key-uuid>'
  and revoked_at is null;
```

Revoked keys fail authentication immediately (no session cache).

## Configuration reference

| Property | Env | Required | Notes |
|---|---|---|---|
| `wp.instances.project-id` | `WP_INSTANCES_PROJECT_ID` | yes | Single allowed project UUID |
| `wp.instances.api-key-pepper` | `WP_INSTANCES_API_KEY_PEPPER` | yes | HMAC key; changing it invalidates all hashes |
| `wp.instances.bootstrap-api-key` | `WP_INSTANCES_BOOTSTRAP_API_KEY` | no | Inserts only when no active key |
| `wp.instances.datasource.*` | `WP_INSTANCES_DATASOURCE_*` | yes | Instance DB |
| `wp.projection.datasource.*` | `WP_PROJECTION_DATASOURCE_*` | yes | Read-only projection DB |

## Logging

Application logs may mention key **ids** and names. They must not echo full API key plaintext or the pepper.
