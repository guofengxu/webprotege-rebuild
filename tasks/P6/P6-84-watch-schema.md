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
编辑器库中有实体关注表；可通过 JDBC 按项目/用户/实体增删改查；Modulith 新增封闭模块 `watch`（deps 含 revision/ontology 供日后匹配、project 取项目显示名，以及 integration 登记邮件）。

## 参考
- `docs/01-rea/watches.md` §4；BS-21；EPIC「关注（P6-45）」决定第 9 条
- Flyway **V13**（接在 P6-83 的 V12 之后）
- 旧出处：`WatchRecord`、`WatchRecordRepositoryImpl`

## 产出
- `backend/wp-app/src/main/resources/db/migration/V13__entity_watches.sql`：
  - `entity_watch(project_id uuid not null references projects, user_id text not null references users, entity_iri text not null, entity_type text not null check (entity_type in ('Class','ObjectProperty','DataProperty','AnnotationProperty','NamedIndividual','Datatype')), watch_type text not null check (watch_type in ('ENTITY','BRANCH')), created_at timestamptz not null default now(), updated_at timestamptz not null default now())`（外键写法与 `V2__core_schema.sql` 一致；实体列必须 `not null`，否则 PostgreSQL 视 NULL 互不相等，唯一约束挡不住重复；`entity_type` 取值与 OWL API `EntityType.getName()` 一致）
  - **唯一** `(project_id, user_id, entity_iri, entity_type)`；索引 `(project_id, entity_iri, entity_type)`、`(project_id, user_id)`
- `wprebuild.app.watch` 包：`package-info` `@ApplicationModule`；`WatchRepository` JDBC：按用户列关注、按实体查、upsert（覆盖类型）、按键删除、按项目列出全部关注（供匹配时一次载入）。
- `docs/02-architecture/overview.md` 第 2 节：增加 `watch`（deps `bus, access, shared, project, revision, ontology, integration`）；`web` / `migration` 允许依赖 `watch`（`events` **不**依赖 `watch`：关注不发项目事件，见 BS-21）；`integration` 职责注明含邮件投递。
- `ModularityTest.EXPECTED` 与相关 `allowedDependencies` 同步。
- `WatchSubjects`（纯函数，供 P6-88 使用）：给定一个修订的变更列表与项目本体，按 BS-22 求受影响实体——对每条 `AddAxiom` / `RemoveAxiom` 取 `AxiomSubjectProviderEx.getSubject`（OWL API 工具类，`ontology/RevisionDiffRenderer` 也在用）；命名实体直接取；IRI 主语解析为传入的内存本体或本修订变更签名中同 IRI 的命名实体；匿名主语、导入、本体注解忽略。语义与 `events/EventTranslator.frameSubjects` 一致；不依赖、不修改 `events` 模块。
- 测试：迁移后表存在；upsert 后 BRANCH 覆盖 ENTITY；删除精确；不存在的用户写入被外键拒绝（`WatchRepositoryTest`）。`WatchSubjectsTest`：`SubClassOf(A, partOf some B)` 只得 A；只改 `rdfs:label` 得到该实体；删除实体时按变更签名取到类型；GCI 与匿名个体主语被忽略；本体注解与导入变更不产生主语。

## 实现要点
- 依赖 P6-83 **只为** Flyway V12→V13。
- 本卡不实现 HTTP、关注匹配（`WatchSubjects` 除外，放在本卡是为了控制 P6-88 的规模）、邮件登记。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-84` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ModularityTest,WatchRepositoryTest,WatchSubjectsTest,CoreSchemaTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- REST / OpenAPI、权限动作、outbox 消费者、Web、迁移导入导出
