-- Per-consumer cursor for the outbox relay (P6-08). last_id is the highest
-- outbox.id the consumer has accepted; the next poll reads id > last_id.

create table outbox_consumers (
    consumer   text primary key,
    last_id    bigint not null,
    updated_at timestamptz not null default now()
);
