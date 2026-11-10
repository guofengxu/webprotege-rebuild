-- Mobile access tokens (ADR-010). The plaintext is never stored.
-- rotated_refresh_tokens keeps every refresh hash replaced by rotation, so a replay can find that device.

create table access_tokens (
    token_id           uuid primary key,
    user_id            text not null references users,
    device_name        text not null,
    access_sha256      bytea not null unique,
    refresh_sha256     bytea not null unique,
    access_expires_at  timestamptz not null,
    refresh_expires_at timestamptz not null,
    created_at         timestamptz not null default now(),
    last_used_at       timestamptz,
    revoked_at         timestamptz
);

create index access_tokens_user_id on access_tokens (user_id);

create table rotated_refresh_tokens (
    refresh_sha256 bytea primary key,
    token_id       uuid not null references access_tokens (token_id)
);

create index rotated_refresh_tokens_token_id on rotated_refresh_tokens (token_id);
