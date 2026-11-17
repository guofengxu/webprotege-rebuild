# Project webhooks

Managers configure callbacks under `/api/projects/{projectId}/webhooks` (OpenAPI). After each committed revision, subscribers of `PROJECT_CHANGED` receive an HTTP `POST` (BS-15 / P6-21). Linked from [`README.md`](README.md).

## Delivery payload

```json
{
  "projectId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "userId": "alice",
  "revisionNumber": 42,
  "timestamp": 1735689600000
}
```

`timestamp` is Unix epoch milliseconds.

## Request headers

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` |
| `User-Agent` | `webprotege-rebuild-webhook` |
| `X-WP-Event` | `PROJECT_CHANGED` |
| `X-WP-Delivery` | Delivery id (stable across retries) |
| `X-WP-Signature-256` | `sha256=<hex>` when the webhook has a secret |

Redirect responses are not followed.

## Verifying the signature

When a secret is configured, the body is signed with HMAC-SHA256 over the raw request bytes:

```text
expected = "sha256=" + hex(HMAC_SHA256(secret, rawBody))
```

Compare to `X-WP-Signature-256` in constant time.

## At-least-once delivery

Receivers must deduplicate with `X-WP-Delivery`. The same id is reused on retries after non-2xx responses, timeouts, or process crashes (lease reclaim).

## Retries and give-up

Failed attempts use exponential backoff: `min(retry-base × 2^(attempts-1), retry-max)` (defaults 1s … 1h). After `wp.webhooks.max-attempts` (default 8) the delivery is `FAILED` and later revisions for that webhook continue. Disabling a webhook cancels pending rows (`CANCELLED`); re-enabling does not replay them.

## Outbound address policy

Only `http`/`https` URLs without userinfo are accepted. By default loopback, private, and link-local targets are rejected (`wp.webhooks.allow-private-targets`). Pre-send checks are best-effort against DNS rebinding; strict environments should constrain egress with a proxy or network policy.
