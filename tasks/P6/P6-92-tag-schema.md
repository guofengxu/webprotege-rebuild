---
id: P6-92
title: 标签：project_tag / entity_tag 表结构（V14）、JDBC 仓储与 Modulith tag 模块壳
phase: P6
module: wp-app/tag
type: impl
size: M
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-46, P6-84]
---

# P6-92 标签存储与 tag 模块壳

## 目标
编辑器库中有项目标签与实体显式标签表；可通过 JDBC 增删改查；Modulith 新增封闭模块 `tag`。

## 参考
- `docs/01-rea/tags.md` §4；BS-23；EPIC「标签（P6-46）」决定第 8、2 条
- Flyway **V14**（接在 P6-84 的 V13 之后）
- 旧出处：`Tag`、`EntityTags`、`TagRepositoryImpl`、`EntityTagsRepositoryImpl`

## 产出
- `backend/wp-app/src/main/resources/db/migration/V14__project_tags.sql`：
  - `project_tag(tag_id uuid pk, project_id uuid, label text not null, description text, color text, background_color text, criteria jsonb not null default '[]', …)`
  - **唯一** `(project_id, label)`；索引 `(project_id)`
  - `entity_tag(project_id, entity_iri, entity_type, tag_id, …)`；**唯一** `(project_id, entity_iri, entity_type, tag_id)`；索引 `(project_id, tag_id)`、`(project_id, entity_iri, entity_type)`
- `wprebuild.app.tag` 包：`package-info` `@ApplicationModule`；`ProjectTagRepository` / `EntityTagRepository` JDBC（按项目列标签、整表替换目录时删除剥离、按实体差分读写、按 tagId 从全部实体剥离）。
- `docs/02-architecture/overview.md` 第 2 节：增加 `tag`（deps `bus, access, shared`）；`web` / `migration` / `events` 允许依赖 `tag`。
- `ModularityTest.EXPECTED` 与相关 `allowedDependencies` 同步。
- 测试：迁移后表存在；同项目重复 label 违反唯一约束；按 tagId 剥离实体行。

## 实现要点
- 依赖 P6-84 **只为** Flyway V13→V14。
- `criteria` 只存不求值；本卡不实现 HTTP、MatchingEngine、事件。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-92` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ModularityTest,ProjectTagRepositoryTest,EntityTagRepositoryTest,CoreSchemaTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- REST / OpenAPI、权限动作、事件、Web、迁移导入导出、MatchingEngine
