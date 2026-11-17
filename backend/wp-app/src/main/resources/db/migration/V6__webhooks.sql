-- Project webhooks and delivery queue (BS-15 / P6-20). Delivery claim and retry land in P6-21.

create table project_webhooks (
    webhook_id   uuid primary key,
    project_id   uuid not null references projects,
    payload_url  text not null,
    event_types  text[] not null,
    secret       text,
    enabled      boolean not null default true,
    created_by   text not null references users,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index project_webhooks_project on project_webhooks (project_id);

create table webhook_deliveries (
    delivery_id      bigint generated always as identity primary key,
    webhook_id       uuid not null references project_webhooks on delete cascade,
    outbox_id        bigint not null,
    event_type       text not null,
    payload          jsonb not null,
    status           text not null
        check (status in ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts         integer not null default 0,
    next_attempt_at  timestamptz not null default now(),
    claim_token      uuid,
    last_status_code integer,
    last_error       text,
    created_at       timestamptz not null default now(),
    finished_at      timestamptz,
    unique (webhook_id, outbox_id)
);
create index webhook_deliveries_pending_next
    on webhook_deliveries (next_attempt_at)
    where status = 'PENDING';
create index webhook_deliveries_pending_webhook
    on webhook_deliveries (webhook_id, delivery_id)
    where status = 'PENDING';
