-- External identity bindings for OIDC / WeCom login (P6-37).
create table user_identities (
  issuer         text not null,
  subject        text not null,
  user_id        text not null references users,
  created_at     timestamptz not null default now(),
  last_login_at  timestamptz,
  primary key (issuer, subject)
);
create index user_identities_user_id on user_identities (user_id);
