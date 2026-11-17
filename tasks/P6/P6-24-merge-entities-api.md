---
id: P6-24
title: 后端：合并实体的命令与接口（新增动作 MERGE_ENTITIES，归入 CAN_EDIT）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: []
status: done
depends_on: [P6-23, P6-49]
---

# P6-24 后端：合并实体的命令与接口

## 目标
`POST /api/projects/{projectId}/entities/merge` 把一组源实体合并到目标实体，生成一个修订；有 `CAN_EDIT` 的成员可以使用；层级、Frame、显示名称、搜索、事件、投影表都像其它编辑一样随之更新。

## 参考
- BS-13；BS-10 的第二轮补充（`MERGE_ENTITIES` 归入 CAN_EDIT）
- P6-23：`wprebuild.kernel.generator.MergeEntitiesGenerator`、`MergedEntityTreatment`、`MergeEntitiesException.Reason`
- P3-14：创建与删除实体的命令、处理器与接口（`web/ProjectController.java` 中的实体接口）；P3-20：回滚
- `access/Action.java`、`access/ProjectRole.java`；`backend/wp-app/src/test/java/wprebuild/app/access/BS10_AccessServiceTest.java`
- P6-01：“我在本项目中的权限”接口会自动包含新动作

## 产出
- `access/Action.java`：增加项目级动作 `MERGE_ENTITIES`；`access/ProjectRole.java`：`CAN_EDIT`（及其上的 `CAN_MANAGE`）包含它；`BS10_AccessServiceTest` 增加对应断言。
- `ontology/MergeEntities.java`（命令：`projectId`、`Set<IRI> sources`、`IRI target`、`MergedEntityTreatment treatment`、`String description`；实体类型由 P6-23 的生成器从本体中解析）与 `ontology/MergeEntitiesHandler.java`：`requiredAction()` 为 `MERGE_ENTITIES`；在项目的写入流水线中执行 `MergeEntitiesGenerator`；说明为空时，用目标实体的显示名称生成说明（`Merged <n> entities into <显示名>`）；返回 `MergeEntitiesResult(EntityRef target, Long revision)`（没有有效变更时 `revision` 为 `null`）。
- `web/ProjectController.java`：`POST /api/projects/{projectId}/entities/merge`，operationId `mergeEntities`；请求体 `{ sources: string[], target: string, treatment, description? }`；`treatment` 默认 `DEPRECATE_MERGED_ENTITY`；`sources` 最多 100 个。错误映射：`UNKNOWN_ENTITY` → 404 `ENTITY_NOT_FOUND`；其余 `Reason` → 400 `INVALID_MERGE`，`ProblemDetail` 扩展字段 `reason`。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 增加该响应；`pnpm -C frontend gen` 重新生成类型。
- 测试 `web/MergeEntitiesTest.java`（Testcontainers，导入 `testdata/ontologies/warehouse-sample.ttl`）：
  - 把类 B（有两个子类、有 `rdfs:label`）以弃用方式合并到类 A：B 的子类出现在 A 之下；A 的 Frame 中有 B 原来的标签（`skos:altLabel`）；B 带 `owl:deprecated`；修订历史中有这次修订及说明；
  - 删除方式：层级中不再有 B，搜索不再能按 IRI 找到 B；
  - 项目事件流收到 `EntityHierarchyChanged` 与 `EntityFrameChanged`；
  - 回滚这次修订后层级恢复原样；
  - `CAN_VIEW` 成员 403；`CAN_EDIT` 成员成功；“我的权限”接口对 `CAN_EDIT` 成员返回 `MERGE_ENTITIES`；
  - 源实体不存在 404；目标在源中、类型不同（源是对象属性、目标是类）、源为 `owl:Thing` 各得到 400 与对应的 `reason`（`TARGET_IN_SOURCES`、`TYPE_MISMATCH`、`BUILT_IN_ENTITY`）；回收站中的项目 409。

## 实现要点
- 合并会产生大量公理变更，但只是一个修订，事件、搜索、投影表都由现有的提交后机制处理，本卡不需要改 `events`、`search`、`projection`。
- 请求不带实体类型，处理器只把 IRI 交给生成器；类型解析与全部校验都在生成器中（P6-23 规定了检查顺序），处理器不重复校验。
- 依赖 P6-49 只是为了串行修改 OpenAPI 生成物（见 EPIC「共享文件与迁移号（第二轮）」）。本卡不新增 Flyway 迁移。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-24` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MergeEntitiesTest,BS10_AccessServiceTest,EntityCreateDeleteTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- Web 端（P6-25）
- MCP 写工具（P6-48 规划）
- 合并预览（先看会改哪些公理再确认）

## 执行记录
- 日期：2026-11-17
- 分支：feature/p6-24-merge-entities-api
- 测试：2026-11-17T18:54:08+08:00 通过（6 项）
- 实际改动：Added MERGE_ENTITIES under CAN_EDIT, MergeEntities command/handler wired to MergeEntitiesGenerator, POST /entities/merge with ProblemDetail mapping, OpenAPI/api-types regenerated, and MergeEntitiesTest covering deprecate/delete/permissions/errors.
- 偏差：无
- 审查：java-reviewer, code-reviewer
