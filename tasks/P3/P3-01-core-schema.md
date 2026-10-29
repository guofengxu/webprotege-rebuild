---
id: P3-01
title: 核心数据库表结构（Flyway V2）
phase: P3
module: wp-app/revision, access, project
type: impl
size: S
work_type: db
ecc_extra: []
status: todo
depends_on: [P0-07]
---

# P3-01 核心数据库表结构（Flyway V2）

## 目标
数据库具备 MVP 所需的全部表。

## 参考
- ADR-004
- ADR-010

## 产出
- `wp-app/src/main/resources/db/migration/V2__core_schema.sql`，内容如下（列名与约束照抄，可以补充注释）：

  ```sql
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
  ```
- 测试 `CoreSchemaTest`（`@DatabaseTest`）：查询 `information_schema`，断言每张表和主键都存在；向 `revisions` 插入 `rev_no = 0` 的行被拒绝。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-01` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CoreSchemaTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 任何仓储类
