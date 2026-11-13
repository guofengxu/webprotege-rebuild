---
id: P6-01
title: 后端：查询“我在本项目中的角色与可用操作”
phase: P6
module: wp-app, packages/api-types
type: impl
size: S
work_type: app-security
ecc_extra: []
status: done
depends_on: [P4-17]
---

# P6-01 我在本项目中的权限（后端）

## 目标
登录用户可以查询自己在某个项目中的角色和被允许的项目级操作，Web 端据此在渲染前隐藏无权使用的控件。对应 M4 差距清单第 4 项。

## 参考
- `docs/03-plan/m4-acceptance.md`「差距清单」第 4 项
- BS-10；`backend/wp-app/src/main/java/wprebuild/app/access/AccessService.java`：`can(UserId, ProjectId, Action)` 中，`SYSTEM_ADMIN` 按 `CAN_MANAGE` 判定，没有分配角色则拒绝；`ProjectRole.actions()`、`Action.projectScoped()`
- ADR-007（项目级查询经过命令总线）；`project/GetProjectMembers.java`、`project/GetProjectMembersHandler.java`（同类查询的写法）
- P3-23 / P4-18：`OpenApiContractTest`、`OpenApiResponseSchemasTest`；`docs/api/README.md`（更新快照的命令）

## 产出
- `access/ProjectAccess.java`：`public record ProjectAccess(ProjectRole role, boolean systemAdmin, Set<Action> actions)`。`role` 可以为 `null`（系统管理员没有被分配角色时）；`actions` 只包含项目级操作，存放不可变副本。
- `access/AccessService.java`：新增 `public ProjectAccess projectAccess(UserId userId, ProjectId projectId)`。`actions` 必须用与 `can(userId, projectId, action)` 相同的判定计算，不另写一套规则。
- `project/GetMyProjectPermissions.java`：`record GetMyProjectPermissions(ProjectId projectId) implements Command<ProjectAccess>`。
- `project/GetMyProjectPermissionsHandler.java`：`project()` 返回该项目；`requiredAction()` 为 `VIEW_PROJECT`，所以不能查看项目的用户得到 403，项目在回收站时由总线返回 409 `PROJECT_TRASHED`；`handle()` 调用 `AccessService.projectAccess(context.user(), projectId)`。
- `web/ProjectController.java`：`GET /api/projects/{id}/permissions/me`，operationId `getMyProjectPermissions`。响应体为 `web/MyProjectPermissionsResponse.java`：`{projectId: string, role: "CAN_VIEW" | "CAN_COMMENT" | "CAN_EDIT" | "CAN_MANAGE" | null, systemAdmin: boolean, actions: Action[]}`。`actions` 按 `Action` 的声明顺序排列；`role` 与 `actions` 的元素在 OpenAPI 中都是枚举。
- `docs/api/openapi.json`：用 `./mvnw -pl :wp-app -Dwp.openapi.update=true test -Dtest=OpenApiContractTest` 更新快照；`OpenApiResponseSchemasTest` 的成功响应清单增加 `getMyProjectPermissions` 的 200 响应。
- `frontend/packages/api-types/src/schema.d.ts`：用 `pnpm -C frontend gen` 重新生成。
- 测试 `web/MyProjectPermissionsTest.java`（Testcontainers，建数据的方式沿用 `SharingTest`）：
  - 四种角色各有一名成员：`role` 为该角色，`systemAdmin=false`，`actions` 与 `ProjectRole.actions()` 相同；
  - 对每个成员和每个项目级 `Action`：`actions.contains(a) == accessService.can(user, project, a)`，防止两套规则走偏；
  - 没有被分配角色的系统管理员：`role=null`、`systemAdmin=true`、`actions` 等于 `CAN_MANAGE` 的操作；
  - 非成员得到 403，未登录得到 401，项目在回收站时得到 409 `PROJECT_TRASHED`，项目 ID 不是 UUID 时得到 400；
  - 修改成员角色后再次查询，结果立即反映新角色（不缓存）。

## 实现要点
- 应用级操作（`CREATE_EMPTY_PROJECT` 等）不进入本接口：它们与项目无关，用 `GET /api/auth/me` 的 `appRoles` 就能判断。`projectScoped=false` 的 `Action` 不出现在 `actions` 中。
- 角色到操作的映射只存在于 `ProjectRole`。Web 端只读本接口的 `actions`（P6-04），不要在前端或其它模块复制这份映射。
- 不加缓存。权限变化后，Web 端会因 `PermissionsChanged` 让 `["project", id]` 前缀失效而重新请求（P4-14）。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-01` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MyProjectPermissionsTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
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
- Web 端按权限显示控件（P6-04、P6-05、P6-06）
- 应用级权限接口、批量查询多个项目的权限
- 修改 BS-10 中角色与操作的定义

## 执行记录
- 日期：2026-11-13
- 分支：feature/p6-01-my-project-permissions
- 测试：2026-11-13T20:37:18+08:00 通过（6 项）
- 实际改动：新增 GET /api/projects/{id}/permissions/me：经 CommandBus 要求 VIEW_PROJECT，AccessService.projectAccess 按与 can() 相同的规则返回角色与项目级 actions；更新 OpenAPI 与 api-types。
- 偏差：审查 MEDIUM 已处理：projectAccess 一次加载角色后按 can() 同规则计算；OpenAPI actions 枚举收窄为项目级。docs/api/openapi.json 属契约快照更新。
- 审查：security-reviewer, java-reviewer
