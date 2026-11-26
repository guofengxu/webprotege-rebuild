# Outbox → Kafka publisher

When `wp.kafka.enabled=true`, each outbox row is published to a Kafka topic so external systems (instance store, data platform, notifications) can follow ontology revisions without reading the editor database (P6-35 / ADR-013). Linked from [`README.md`](README.md).

## Enable

```yaml
wp:
  kafka:
    enabled: true
    topic: wp.revisions          # default
    start-from: LATEST           # LATEST | EARLIEST (default LATEST)
    send-timeout: 10s            # max block per accept batch (default 10s)
spring:
  kafka:
    bootstrap-servers: broker:9092
```

The application does **not** create the topic. Operators provision partition count and retention beforehand.

### `start-from`

Used only when the `kafka` row is first inserted into `outbox_consumers`:

| Value | First cursor |
| --- | --- |
| `LATEST` | Skip rows that already exist at registration |
| `EARLIEST` | Deliver from the first outbox row |

## Message format

- **Key**: `projectId` (UUID string). Same project → same partition → commit order preserved.
- **Value** (JSON):

```json
{
  "outboxId": 42,
  "type": "REVISION_COMMITTED",
  "projectId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "revNo": 7,
  "payload": {
    "projectId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "revNo": 7,
    "userId": "alice",
    "ts": "2026-01-15T12:00:00Z",
    "changeCount": 3
  },
  "createdAt": "2026-01-15T12:00:00.012345Z"
}
```

`payload` is the outbox JSON embedded as an object. Axiom-level diffs are not included; callers that need change details use the revision diff API.

| Header | Value |
| --- | --- |
| `wp-outbox-id` | Outbox row id (decimal string) |
| `wp-event-type` | Outbox `type` (for example `REVISION_COMMITTED`) |

## Ordering

- **Same project**: ordered (shared key / partition).
- **Across projects**: no global order guarantee.

## At-least-once delivery

Failures during publish roll back the outbox cursor for the `kafka` consumer; the next attempt resends the whole batch. Duplicate messages are expected. Downstream must deduplicate with `outboxId` (body or `wp-outbox-id`).

The publisher is not a transactional Kafka producer: the editor DB transaction and Kafka cannot be one atomic unit.

## Broker unavailable

`accept` waits up to `wp.kafka.send-timeout`, then throws. The outbox relay marks the `kafka` consumer for exponential backoff and leaves `last_id` unchanged. The relay is single-threaded over consumers, so each backoff cycle other consumers can be delayed by about one `send-timeout` while Kafka is stuck. Keep the timeout short in production.
