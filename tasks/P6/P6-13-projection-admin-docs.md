---
id: P6-13
title: 投影表的状态与重建接口（管理员），以及外部 SQL 访问说明
phase: P6
module: wp-app, packages/api-types
type: impl
size: M
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P6-12, P6-02]
---

# P6-13 投影表的运维接口与访问说明

## 目标
系统管理员可以查看某个项目的投影是否跟上（投影修订号、项目头修订号、落后多少），在怀疑数据不一致时手动触发全量重建。BI 与 AI Agent 的使用者拿到一份文档，就能用只读账号连上数据库、查询投影表。

## 参考
- P6-09 / P6-10 / P6-12：`projection` 模块、`projection.project_state`、`ProjectionWriter`（项目级咨询锁，修订号只进不退）、`ProjectionRebuilder`（先取锁再计算）、`wp.projection.enabled`（默认 `false`）；P6-11 的测量报告
- P3-25：`web/admin/AdminUserController.java`、`web/admin/ListUsers*.java`（管理员接口的写法，经过命令总线）；`access/AppRole.java`（只有 `SYSTEM_ADMIN` 拥有 `EDIT_APPLICATION_SETTINGS`）
- `bus/CommandBus.java`：命令带项目 ID 时先检查项目状态（不存在 → 404；在回收站 → 409，除非处理器覆盖 `allowsTrashedProject()`）；应用级操作按 `AccessService.require(user, action)` 校验
- ADR-013 第 2 节；`docs/02-architecture/overview.md` 第 2 节
- P3-23 / P4-18：`OpenApiContractTest`、`OpenApiResponseSchemasTest`

## 产出
- `projection/GetProjectionStatus.java`、`projection/GetProjectionStatusHandler.java`：返回 `projection/ProjectionStatus.java`，即 `record ProjectionStatus(ProjectId projectId, Long revNo, long head, Long lag, Instant rebuiltAt, Instant updatedAt)`。还没有投影时 `revNo`、`lag`、`rebuiltAt`、`updatedAt` 为 `null`。`head` 用 `RevisionLog.head(projectId)` 读取，不加载项目的内存模型。覆盖 `allowsTrashedProject()` 返回 `true`：回收站中的项目也能查看投影状态。
- `projection/RebuildProjection.java`、`projection/RebuildProjectionHandler.java`：同步调用 `ProjectionRebuilder.rebuild`，返回重建后的 `ProjectionStatus`。不覆盖 `allowsTrashedProject()`：回收站中的项目返回 409 `PROJECT_TRASHED`。
- 两个处理器的 `project()` 都返回该项目（项目不存在时由总线返回 404），`requiredAction()` 都是应用级的 `EDIT_APPLICATION_SETTINGS`（只有系统管理员拥有）。
- `projection/package-info.java`：`allowedDependencies` 增加 `"access"`（`"bus"` 已在 P6-12 中加入）；`web/package-info.java`：`allowedDependencies` 增加 `"projection"`；同步修改 `docs/02-architecture/overview.md` 第 2 节表格。
- `web/admin/ProjectionAdminController.java`：
  - `GET /api/admin/projections/{projectId}`，operationId `getProjectionStatus`；
  - `POST /api/admin/projections/{projectId}/rebuild`，operationId `rebuildProjection`；
  - 响应体为 `web/admin/ProjectionStatusResponse.java`。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 增加两条 200 响应；`pnpm -C frontend gen` 重新生成 `frontend/packages/api-types/src/schema.d.ts`。
- `docs/api/projection.md`：
  - 每张表、每一列的含义与取值，与 `V5__projection_schema.sql` 一致；
  - 一致性语义：投影是最终一致的，以 `project_state.rev_no` 为准；只读；随时可能被整表重建，不要在外部系统中依赖行的物理顺序，也不要长期持有事务；
  - 示例 SQL：某个类的全部后代（递归 CTE）、按标签前缀查找实体、某属性的定义域与值域、本项目中的弃用实体；
  - 供数据库管理员执行的只读账号脚本：`create role wp_projection_reader login …`、`grant usage on schema projection`、`grant select on all tables in schema projection`、`alter default privileges in schema projection grant select on tables`。应用本身**不**在 Flyway 迁移中创建角色；
  - 重建步骤：调用 `rebuildProjection`；或者删除该项目的 `project_state` 行，让下一个修订触发全量重建；
  - 开启方式：`wp.projection.enabled=true`（默认关闭）。开启后第一次运行时，每个有修订的项目都会做一次全量重建，每批最多一个项目（P6-12）。
- 测试 `web/ProjectionAdminTest.java`（Testcontainers，`wp.projection.enabled=true`）：
  - 系统管理员查询状态：编辑之后在 10 秒内 `lag` 变为 0；
  - 手动删除部分投影行之后调用重建，行数恢复，`rebuiltAt` 更新；
  - 回收站中的项目：状态可以查询，重建返回 409；
  - 非管理员（包括该项目的 `CAN_MANAGE` 成员）得到 403，未登录得到 401，项目不存在时得到 404；
  - 重建与增量更新并发时（在测试中同时触发一次编辑与一次重建），最终结果等于 `ProjectionRows.compute(runtime)`。

## 实现要点
- 重建是同步的。50 万公理规模的耗时以 P6-11 的测量为准；若超过常见的 HTTP 超时（30 秒），在执行记录中说明，不要在本卡里改成异步任务。
- 并发安全依赖 P6-09 / P6-10：先取项目级咨询锁再计算，写入时修订号只进不退。所以手动重建与增量更新串行执行，结果确定，并发测试不会时好时坏。
- 文档中的账号脚本只写占位密码，不写任何真实凭据。
- 依赖 P6-02 只是为了串行修改生成物 `docs/api/openapi.json`、`frontend/packages/api-types/src/schema.d.ts` 和 `OpenApiResponseSchemasTest`（P6-01 → P6-02 → 本卡）。本卡不使用 P6-02 的代码。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-13` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProjectionAdminTest,ProjectionIncrementalTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Web 端的投影管理页面
- 异步重建任务、定时全量校验
- 在 Flyway 中创建数据库角色、修改 `compose.yaml`
