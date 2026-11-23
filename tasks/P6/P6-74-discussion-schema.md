---
id: P6-74
title: 讨论：entity_discussion_thread / comment 表结构（V11）、JDBC 仓储与 Modulith discussion 模块壳
phase: P6
module: wp-app/discussion
type: impl
size: M
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-44, P6-66]
---

# P6-74 讨论存储与 discussion 模块壳

## 目标
编辑器库中有讨论线程与评论两张表，可通过 JDBC 仓储按项目 / 实体增删改查；Modulith 新增封闭模块 `discussion`，`web`、`migration`、`events` 允许依赖它。

## 参考
- `docs/01-rea/discussions.md` §4；BS-19
- EPIC「讨论（P6-44）」决定第 7、8 条；Flyway **V11**（接在 P6-66 的 V10 之后）
- 旧出处：`EntityDiscussionThread`、`EntityDiscussionThreadRepository`
- `docs/02-architecture/overview.md` 第 2 节；`ModularityTest`

## 产出
- `backend/wp-app/src/main/resources/db/migration/V11__entity_discussions.sql`：
  - `entity_discussion_thread(thread_id uuid pk, project_id uuid not null, entity_iri text not null, entity_type text not null, status text not null check (status in ('OPEN','CLOSED')))`
  - `entity_discussion_comment(comment_id uuid pk, thread_id uuid not null references … on delete cascade, created_by text not null, created_at timestamptz not null, updated_at timestamptz null, body text not null, rendered_body text not null, position int not null)`
  - 索引：`(project_id, entity_iri, entity_type)`；`(project_id, status)`；**唯一** `(thread_id, position)`；`comment_id` 全局唯一（主键）
- `wprebuild.app.discussion` 包：`package-info` 声明 `@ApplicationModule`；`DiscussionThreadRepository`（或拆分）与 JDBC 实现：按实体列线程（含评论按 position）、按 threadId/commentId 读写、追加/更新/删除评论、改状态、`replaceEntity(projectId, fromIri, fromType, toIri, toType)`、项目内有评论实体聚合查询所需的只读方法。
- `docs/02-architecture/overview.md` 第 2 节：增加 `discussion`（职责「实体讨论线程与评论」；允许依赖 `bus, access, shared`）；`web`、`migration`、`events` 的允许依赖加入 `discussion`。
- `ModularityTest.EXPECTED` 与相关 `package-info` `allowedDependencies` 同步。
- 测试：空库迁移后表存在；线程与评论往返；删除线程级联删评论；`replaceEntity` 改挂。

## 实现要点
- 依赖 P6-66 **只为** Flyway V10→V11 串行。
- 本卡不实现 HTTP、渲染、事件发布。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-74` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ModularityTest,DiscussionRepositoryTest,CoreSchemaTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- REST / OpenAPI、权限动作、Markdown 渲染、SSE、迁移导入导出、合并转移调用方
