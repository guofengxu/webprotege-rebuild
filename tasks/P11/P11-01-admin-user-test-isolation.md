---
id: P11-01
title: 后端测试：AdminUserManagementTest 与其它测试类的数据隔离，修复 main 上 Java CI 的失败
phase: P11
module: wp-app
type: impl
size: S
work_type: test-data
ecc_extra: []
status: todo
depends_on: []
---

# P11-01 后端测试：修复 main 上 Java CI 的失败

## 目标
`main` 上的 `./mvnw -q verify` 与 CI 的 `java` job 重新全部通过：`AdminUserManagementTest` 不再受其它测试类提交到共享数据库的数据影响。

## 参考
- CI 记录：`main` 上 CI 的 `java` job 最后一次通过是 PR #109（`eef7b0c`，2026-11-14），从 PR #110（`1ef4281`）起持续失败，固定失败在 `wprebuild.app.web.AdminUserManagementTest`：
  - `admin_can_list_create_reset_password_and_assign_roles`：`expected: 1L but was: 19L`（`GET /api/admin/users` 的 `total`）；
  - `cannot_remove_the_last_system_admin`：期望 409（`LAST_SYSTEM_ADMIN`），实际 200；
  - 2026-11-14 的几次还有 `migrated_user_can_sign_in_after_an_admin_sets_a_password`：`missing user migrated`。
- 原因分析（2026-11-28）：各测试类共用同一个 Testcontainers PostgreSQL（P0-04）。`AdminUserManagementTest` 带 `@Transactional`，自己的数据会回滚，但断言依赖全局状态：`users` 表中只有本用例创建的账户（`total`、分页顺序），系统管理员只有本用例创建的一个（移除最后一个返回 409）。至少 6 个不回滚的测试类会提交带 `SYSTEM_ADMIN` 的账户，例如 `AdminDeleteUserTest`、`DeletedUserSessionTest`、`MemberCandidatesTest`、`MyProjectPermissionsTest`、`ExternalIdentityServiceTest`；它们先运行时断言就失败。
- `users` 表：`backend/wp-app/src/main/resources/db/migration/V2__core_schema.sql`（`app_roles text[]`）。
- 相关卡：P3-25（用户管理接口）、P6-29（删除用户）；`tasks/P6/EPIC.md` 中“`main` 上 CI 的 `java` job 从 2026-11-14 前后起持续失败”的记录；`tasks/P11/EPIC.md`。

## 产出
- `backend/wp-app/src/test/java/wprebuild/app/web/AdminUserManagementTest.java`：新增 `@BeforeEach` 方法，在用例事务内执行 `truncate table users cascade`，让每条用例从空的账户表开始；类上已有的 `@Transactional` 使它在用例结束时回滚，不影响其它测试类。现有用例与断言不变。
- 不修改生产代码（`src/main/`），也不修改其它测试类。

## 实现要点
- 先用验收命令在改动前复现失败：`AdminDeleteUserTest` 按类名顺序先于 `AdminUserManagementTest` 运行，并提交带 `SYSTEM_ADMIN` 的账户。复现结果写进执行记录；如果没有复现，照常修改，并在执行记录中写明。
- `@BeforeEach` 在 Spring 测试事务内执行，清表语句随事务回滚。`cascade` 会同时清空引用 `users` 的表，这些表也一起回滚。
- 如果清表因锁等待导致超时，或导致其它用例失败，改为不依赖库中已有数据的写法：在 `@BeforeEach` 中用 `update users set app_roles = array_remove(app_roles, 'SYSTEM_ADMIN')` 去掉其它账户的系统管理员角色；`total` 与分页断言改为以用例开始时的账户数为基线，按 `userId` 查找本用例创建的账户。在执行记录的偏差中写明原因。
- 不跳过、不删除用例，不放宽 409 / `LAST_SYSTEM_ADMIN` 的断言。
- 全量检查中如果 `ProjectionIncrementalTest` 偶发失败（2026-11-22 出现过一次），重跑一次；稳定失败时按 `AGENTS.md` 第 6 节停下。

## ECC 技能
工作项类型：`test-data` —— 测试语料与测试支持代码。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P11-01` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AdminDeleteUserTest,AdminUserManagementTest,MyProjectPermissionsTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写（含改动前是否复现失败）

## 不在本卡范围
- 修改 `.github/workflows/ci.yml`（开发标准文件，由人另开分支修改）。
- 本地 `WebhookDeliveryTest` 的超时（CI 上通过，P6-61 复核时记录）；`ProjectionIncrementalTest` 的偶发失败。
- 让所有测试类改为事务回滚，或给每个测试类单独建库。
