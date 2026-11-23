---
id: P6-84
title: 关注：entity_watch 表结构（V13）、JDBC 仓储与 Modulith watch 模块壳
phase: P6
module: wp-app/watch
type: impl
size: M
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-45, P6-83]
---

# P6-84 关注存储与 watch 模块壳

## 目标
编辑器库中有实体关注表；可通过 JDBC 按项目/用户/实体增删改查；Modulith 新增封闭模块 `watch`（deps 含 revision/ontology 供日后匹配，以及 integration 登记邮件）。

## 参考
- `docs/01-rea/watches.md` §4；BS-21；EPIC「关注（P6-45）」决定第 9 条
- Flyway **V13**（接在 P6-83 的 V12 之后）
- 旧出处：`WatchRecord`、`WatchRecordRepositoryImpl`

## 产出
- `backend/wp-app/src/main/resources/db/migration/V13__entity_watches.sql`：
  - `entity_watch(project_id uuid, user_id text, entity_iri text, entity_type text, watch_type text check (watch_type in ('ENTITY','BRANCH')), …)`
  - **唯一** `(project_id, user_id, entity_iri, entity_type)`；索引 `(project_id, entity_iri, entity_type)`、`(project_id, user_id)`
- `wprebuild.app.watch` 包：`package-info` `@ApplicationModule`；`WatchRepository` JDBC：按用户列关注、按实体查、upsert（覆盖类型）、按键删除、按项目+实体集合批量查询（供匹配）。
- `docs/02-architecture/overview.md` 第 2 节：增加 `watch`（deps `bus, access, shared, revision, ontology, integration`）；`web` / `migration` / `events` 允许依赖 `watch`；`integration` 职责注明含邮件投递。
- `ModularityTest.EXPECTED` 与相关 `allowedDependencies` 同步。
- 测试：迁移后表存在；upsert 后 BRANCH 覆盖 ENTITY；删除精确。

## 实现要点
- 依赖 P6-83 **只为** Flyway V12→V13。
- 本卡不实现 HTTP、匹配、邮件登记。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-84` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ModularityTest,WatchRepositoryTest,CoreSchemaTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- REST / OpenAPI、权限动作、outbox 消费者、Web、迁移导入导出
