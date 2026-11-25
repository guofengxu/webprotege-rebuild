---
id: P6-29
title: 后端：系统管理员删除用户（停用并清除个人信息，账户 ID 保留在历史中）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: done
depends_on: [P6-50]
---

# P6-29 后端：系统管理员删除用户

## 目标
系统管理员可以删除一个用户：该用户立即无法再访问系统（会话、API Key、移动端令牌全部失效），从所有项目的成员中移除，个人信息被清除；修订历史、项目所有者等引用里的账户 ID 保持不变，并且这个 ID 不能再被新用户使用。

## 参考
- `tasks/P6/EPIC.md`「第二轮规划（P6-19）」中“删除用户”的决定：**软删除**。旧系统没有删除用户的功能，历史修订的展示方式由本卡确定为“照常显示账户 ID”，不改写历史，也不把作者改成占位符
- BS-10：应用级动作 `DELETE_ANY_ACCOUNT`（`access/Action.java` 中已有，只有系统管理员拥有）
- 表结构：`V2__core_schema.sql` 中 `users.user_id` 是用户名、主键，被 `api_keys`、`projects.owner`、`role_assignments` 外键引用；`revisions.user_id` 没有外键
- `access/UserService.java`（`LAST_SYSTEM_ADMIN`、`search`、`findById`）、`access/AccessService.java`（`LAST_MANAGER`）、`access/ApiKeyService.java`、`access/AccessTokenService.java`
- `web/admin/AdminUserController.java` 及同包的命令与处理器（`ListUsers`、`SetUserAppRoles` 等）
- `web/AuthController.java`、`web/AccessTokenController.java`、`web/security/ApiKeyAuthenticationFilter.java`、`web/security/BearerTokenAuthenticationFilter.java`（都通过 `UserService.findById` 取用户）；`V2__core_schema.sql` 中 `password_hash` 为空表示“迁移用户、必须重置密码”，`UserService.verifyPassword` 会先返回 `ResetRequired`
- P6-03：`RoleAssignmentsChanged` 会让被移出项目的用户的事件订阅关闭

## 产出
- `db/migration/V7__user_deletion.sql`：`alter table users add column deleted_at timestamptz`。
- `access/UserAccount.java`、`access/UserProfile.java`：增加 `Instant deletedAt`（未删除时为 `null`），同步修改构造处。
- `access/UserService.java`：
  - `void delete(UserId userId, UserId actingAdmin)`，一个事务内完成：不能删除自己（409 `CANNOT_DELETE_SELF`）；删除后会没有系统管理员时拒绝（409 `LAST_SYSTEM_ADMIN`，沿用现有常量）；该用户是任一项目唯一的 `CAN_MANAGE` 时拒绝（409 `LAST_MANAGER`，`ProblemDetail` 扩展字段 `projectIds` 列出这些项目）；用户不存在返回 404；**已删除的用户再次删除视为成功**（幂等）。通过检查后：`deleted_at = now()`、`email = null`、`password_hash = null`、`app_roles = '{}'`（`display_name` 保留，供管理员辨认）；删除该用户的全部 API Key；吊销全部移动端令牌；删除全部 `role_assignments`，事务提交后对每个受影响的项目发布 `RoleAssignmentsChanged`。
  - `verifyPassword` 对已删除的用户按“用户名或密码错误”处理（不暴露账户已删除）；`search`（成员候选查找，P6-02）排除已删除的用户；`setAppRoles`、`setPassword` 对已删除的用户返回 409 `USER_DELETED`；`create` 遇到已删除用户的 ID 时沿用现有的“用户已存在”冲突。
- `access/ApiKeyService.java`：`int revokeAll(UserId userId)`；`access/AccessTokenService.java`：`int revokeAll(UserId userId)`（对应仓储各加一个按用户批量操作的方法）。
- 认证路径中“已删除”优先于其它状态：`verifyPassword` 先判断 `deletedAt`，再判断 `password_hash` 是否为空（否则已删除的用户会得到可区分的 `PASSWORD_RESET_REQUIRED`）；`POST /api/auth/tokens`（`AccessTokenController`）与刷新令牌同样先判断；`ApiKeyAuthenticationFilter`、`BearerTokenAuthenticationFilter` 在取到用户之后拒绝 `deletedAt` 不为空的用户（纵深防御，API Key 与令牌已被吊销）；管理员列表中已删除用户的 `passwordResetRequired` 为 `false`。
- `access/UserDeleted.java`（`record UserDeleted(UserId userId)`）：`UserService.delete` 在事务提交后发布，P6-52 据此让该用户的会话立即失效。
- `web/admin/DeleteUser.java`、`web/admin/DeleteUserHandler.java`：`requiredAction()` 为 `DELETE_ANY_ACCOUNT`；处理器调用 `UserService.delete`。
- `web/admin/AdminUserController.java`：`DELETE /api/admin/users/{userId}`，operationId `deleteUser`，成功返回 204；`AdminUserResponse` 增加 `deletedAt`（可为空）。
- `web/AuthController.java#me`：已删除的用户返回 401。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 覆盖新接口与 `deletedAt`；`pnpm -C frontend gen` 重新生成 `frontend/packages/api-types/src/schema.d.ts`。
- 测试 `web/AdminDeleteUserTest.java`（Testcontainers）：
  - 用户 A 已用会话登录、持有一个 API Key 和一个移动端令牌，是两个项目的 `CAN_EDIT` 成员；管理员删除 A 后：A 的 API Key 与 Bearer 令牌得到 401（已登录会话的失效由 P6-52 覆盖）；用 A 的账号密码登录或换取移动端令牌都失败，响应与密码错误相同（不是 `PASSWORD_RESET_REQUIRED`）；发布了一次 `UserDeleted`；两个项目的成员列表中没有 A；A 订阅中的 SSE 连接被关闭（P6-03）；
  - A 提交过的修订在修订历史中仍显示 `userId = A`；以 A 为所有者的项目仍可访问；
  - 管理员用户列表中 A 带 `deletedAt`；成员候选查找不返回 A；用 A 的 ID 创建新用户得到 409；对 A 设置角色或重置密码得到 409 `USER_DELETED`；再次删除 A 返回 204；
  - 删除自己 409 `CANNOT_DELETE_SELF`；删除唯一的系统管理员 409 `LAST_SYSTEM_ADMIN`；删除某项目唯一的管理者 409 `LAST_MANAGER`，`projectIds` 包含该项目，且什么都没有改；
  - 非系统管理员 403，未登录 401，用户不存在 404。

## 实现要点
- 删除是不可逆的，但只做软删除：`users` 行保留，外键引用（`projects.owner`、修订历史中的 `user_id`）都不需要改，历史可审计。把这一取舍写进 `UserService.delete` 的 Javadoc。
- 先检查、后修改：任何一个 409 都必须在修改之前返回，测试断言“什么都没有改”。
- `RoleAssignmentsChanged` 在事务提交后发布（与 `AccessService` 现有做法一致），避免订阅关闭发生在回滚的删除上。
- 已登录会话的失效（`SessionRegistry` 与按请求检查）在 P6-52；本卡只发布 `UserDeleted`，不改 `SecurityConfig`。
- 日志只记录被删除用户的 ID 与操作者 ID，不记录邮箱。
- 依赖 P6-50 只是为了串行修改 `docs/api/openapi.json`、`schema.d.ts`、`OpenApiResponseSchemasTest` 与 Flyway 版本号（见 EPIC「共享文件与迁移号（第二轮）」），本卡不使用 P6-50 的代码。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-29` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AdminDeleteUserTest,AdminUserManagementTest,AuthFlowTest,MemberCandidatesTest,EventsTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
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
- 已登录会话的立即失效（P6-52）
- Web 端的删除按钮与已删除用户的展示（P6-30）
- 硬删除、把历史修订改写为其他作者、恢复已删除的用户
- 多实例之间的会话失效（P6-40）
- 修订历史中显示用户的显示名称（目前历史只显示账户 ID，本卡不改）

## 执行记录
- 日期：2026-11-25
- 分支：feature/p6-29-admin-delete-user
- 测试：2026-11-25T09:52:01+08:00 通过（6 项）
- 实际改动：Added V7 deleted_at, UserService.delete with LAST_MANAGER project locks, DELETE /api/admin/users/{userId}, auth paths reject deleted users, OpenAPI/api-types updated; AdminDeleteUserTest covers happy path and guards.
- 偏差：无
- 审查：security-reviewer, (no, CRITICAL/HIGH);, java-reviewer, (HIGH, LAST_MANAGER, race, fixed, with, per-project, locks;, MEDIUM, countWithRole/soleManager, deleted, filters, applied)
