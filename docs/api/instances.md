# Instance-store HTTP API (`wp-instances-app`)

Independent Spring Boot service for the ontology instance store (ADR-013 / P6-57+). Default port **8082**. Authentication is P6-58; batch write is P6-59; read routes land in later cards.

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

## Batch write

```http
POST /api/v1/projects/{projectId}/batch
X-API-Key: <plaintext>
Content-Type: application/json
```

`{projectId}` must equal `wp.instances.project-id` (else **403** after auth). The body supplies the business clock; the server never uses `Instant.now()` as `at`.

```json
{
  "at": "2026-03-01T10:00:00Z",
  "source": "wcs",
  "ops": [
    {
      "op": "upsert",
      "iri": "http://example.org/p1",
      "classIri": "http://example.org/Pallet",
      "attributesJson": "{}"
    },
    {
      "op": "link",
      "subjectIri": "http://example.org/p1",
      "propertyIri": "http://example.org/storedAt",
      "objectIri": "http://example.org/loc1",
      "subjectClassIri": "http://example.org/Pallet",
      "objectClassIri": "http://example.org/StorageLocation"
    },
    {
      "op": "unlink",
      "subjectIri": "http://example.org/p1",
      "propertyIri": "http://example.org/storedAt",
      "objectIri": "http://example.org/loc1"
    },
    {
      "op": "retire",
      "iri": "http://example.org/p1"
    }
  ]
}
```

| Field | Notes |
|---|---|
| `at` | Required ISO-8601 instant (caller-supplied). Must not be more than `wp.instances.max-future-skew` (default **PT5M**) ahead of server UTC time → **400**. Rewriting history (`at` before the current version’s `valid_from`) → **400**; nothing from the batch is kept. |
| `source` | Optional when the API key has a non-blank `name` (used as `source`). Otherwise required and non-blank. Applied to every upsert in the batch. |
| `ops` | Array of `upsert` / `link` / `unlink` / `retire`. Max length `wp.instances.max-batch-ops` (default **1000**) → **413**. |
| upsert `attributesJson` | JSON object/array text; `ontology_rev` is stamped from projection rules, not from the client. |
| link | Needs `subjectClassIri` and `objectClassIri` for closed-world validation. |

Behaviour:

- The whole batch is one JDBC transaction. Any rule `Violation` → **422** with `{ "violations": [ … ] }` and **no** durable writes.
- Success → **200** with `{ "applied", "at", "ontologyRev", "source" }`.
- Projection `contract_version` mismatch or missing projection → **503** (ERROR log from `CachedRuleSource`).
- Request `Content-Length` above `wp.instances.max-request-bytes` (default **1048576** / 1 MiB) → **413**.
- Missing/invalid API key → **401**. Wrong project → **403**.

Idempotency keys are out of scope here (P6-64). Application-level rate limiting is out of scope (document reverse-proxy limits in P6-61).

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
| `wp.instances.max-batch-ops` | `WP_INSTANCES_MAX_BATCH_OPS` | no | Default `1000`; excess → 413 |
| `wp.instances.max-request-bytes` | `WP_INSTANCES_MAX_REQUEST_BYTES` | no | Default `1048576`; excess Content-Length → 413 |
| `wp.instances.max-future-skew` | `WP_INSTANCES_MAX_FUTURE_SKEW` | no | Default `PT5M`; farther future `at` → 400 |
| `wp.instances.datasource.*` | `WP_INSTANCES_DATASOURCE_*` | yes | Instance DB |
| `wp.projection.datasource.*` | `WP_PROJECTION_DATASOURCE_*` | yes | Read-only projection DB |

## Logging

Application logs may mention key **ids** and names. They must not echo full API key plaintext or the pepper.
