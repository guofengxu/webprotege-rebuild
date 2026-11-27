-- Instance-store API keys (P6-58). Independent of editor access.api_keys.
create table api_keys (
    id uuid primary key,
    name text not null,
    key_hash text not null unique,
    created_at timestamptz not null,
    revoked_at timestamptz
);
