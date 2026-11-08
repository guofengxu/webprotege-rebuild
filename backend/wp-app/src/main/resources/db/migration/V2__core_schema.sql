-- MVP core tables (ADR-004, ADR-010). Column names and constraints match P3-01.

create table users (
  user_id        text primary key,            -- 即用户名，与旧系统一致
  display_name   text not null,
  email          text,
  password_hash  text,                         -- 为空表示必须先重置密码（迁移用户）
  app_roles      text[] not null default '{}', -- PROJECT_CREATOR / SYSTEM_ADMIN
  created_at     timestamptz not null default now()
);
create table api_keys (
  key_id      uuid primary key,
  user_id     text not null references users,
  name        text not null,
  key_sha256  bytea not null unique,
  created_at  timestamptz not null default now(),
  last_used_at timestamptz
);
create table projects (
  project_id   uuid primary key,
  display_name text not null,
  description  text not null default '',
  owner        text not null references users,
  in_trash     boolean not null default false,
  created_at   timestamptz not null, created_by text not null,
  modified_at  timestamptz not null, modified_by text not null
);
create table role_assignments (
  project_id uuid not null references projects,
  user_id    text not null references users,
  role       text not null,                      -- CAN_VIEW / CAN_COMMENT / CAN_EDIT / CAN_MANAGE
  primary key (project_id, user_id)
);
create table project_settings (
  project_id uuid not null references projects,
  key        text not null,                      -- prefixes / new-entity / display-names
  value      jsonb not null,
  primary key (project_id, key)
);
create table revisions (
  project_id   uuid not null references projects,
  rev_no       bigint not null check (rev_no > 0),
  user_id      text not null,
  ts           timestamptz not null,
  description  text not null,
  change_count integer not null,
  codec        text not null,
  payload      bytea not null,
  primary key (project_id, rev_no)
);
create table revision_subjects (
  project_id  uuid not null,
  rev_no      bigint not null,
  subject_iri text not null,
  primary key (project_id, subject_iri, rev_no),
  foreign key (project_id, rev_no) references revisions
);
create table snapshots (
  project_id uuid not null references projects,
  rev_no     bigint not null,
  codec      text not null,
  payload    bytea not null,
  created_at timestamptz not null default now(),
  primary key (project_id, rev_no)
);
create table outbox (
  id           bigint generated always as identity primary key,
  project_id   uuid not null,
  rev_no       bigint not null,
  type         text not null,
  payload      jsonb not null,
  created_at   timestamptz not null default now(),
  published_at timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;
