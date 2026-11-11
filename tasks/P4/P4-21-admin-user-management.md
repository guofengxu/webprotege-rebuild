---
id: P4-21
title: 用户管理页（系统管理员）：用户列表、创建用户
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-03]
---

# P4-21 用户管理（管理员）

## 目标
系统管理员在“用户管理”页可以分页浏览全部用户、创建新用户（设置初始密码）；普通用户无法进入该页。本卡对应 `docs/03-plan/mvp-scope.md` 中 Web 端的“管理页”，EPIC 未单列；P4-17 的验收流程用它创建第二个用户。修改角色与重置密码见 P4-28。

## 参考
- `docs/03-plan/mvp-scope.md`（“用户管理（管理员）| P3-25 | 管理页”）
- P3-25：
  - `listUsers(page, size)` → `AdminUserPage{page, size, total, users: AdminUserResponse[]}`，`AdminUserResponse{userId, displayName, email, appRoles, passwordResetRequired}`；`page` 从 0 开始（`web/admin/ListUsers.java` 要求 `page >= 0`）；
  - `createUser{userId, displayName, email, initialPassword}`（201）：`userId` 匹配 `[A-Za-z0-9._-]{2,64}`，重复返回 409；密码至少 12 位，否则 400；
  - 普通用户调用全部返回 403
- P3-04：`password_hash` 为空的用户登录得到 `PASSWORD_RESET_REQUIRED`（迁移用户的初始状态）
- `docs/api/openapi.json` 的 operationId：`listUsers`、`createUser`、`setUserAppRoles`、`resetUserPassword`（后两个只在 `api.ts` 中封装，界面见 P4-28）
- P4-25 的 `installAuthGuard`（已处理 `meta.requiresSystemAdmin`）；P4-03 的用户菜单（“用户管理”→ `/admin/users`）

## 产出
- `src/features/admin/api.ts`：`useAdminUsers(page, size)`（键 `queryKeys.adminUsers(page, size)`，`keepPreviousData`）、`useCreateUser()`、`useSetUserAppRoles()`、`useResetUserPassword()`；写操作成功后让 `queryKeys.adminUsersAll()` 失效。
- `src/features/admin/randomPassword.ts`：`randomPassword(length = 20): string`，用 `crypto.getRandomValues` 从大小写字母、数字中均匀取字符（拒绝采样，不用取模）；P4-28 的重置密码对话框复用它。
- `src/features/admin/routes.ts`：`layout` 路由 `/admin/users`，`meta.requiresSystemAdmin: true`（P4-25 的守卫据此拦截非管理员，本卡不修改 `src/app/authGuard.ts`）。
- `src/features/admin/AdminUsersPage.vue`：表格（用户 ID、显示名称、邮箱、应用角色标签、“需要设置密码”标记、操作列，P4-28 在操作列中加按钮）+ `el-pagination`（每页 50；`el-pagination` 的页码从 1 开始，请求时 `page = currentPage - 1`）+ “新建用户”按钮；收到 403 时显示无权限提示。
- `src/features/admin/CreateUserDialog.vue`：用户 ID（按后端的正则即时校验）、显示名称、邮箱（格式校验）、初始密码（至少 12 位，可显示 / 隐藏，提供“生成随机密码”按钮）；409 显示“用户 ID 已存在”；成功后关闭对话框，提示把初始密码告知用户。
- 文案：`src/features/admin/locales/`（顶层键 `admin`）。
- 测试：
  - `src/features/admin/routes.spec.ts`：用 `createAppRouter` 与 P4-25 的 `installAuthGuard`（假 API 返回普通用户 / 管理员）验证：非管理员访问 `/admin/users` 被重定向到 `/`；管理员可以进入。
  - `src/features/admin/randomPassword.spec.ts`：默认长度 20；只含字母和数字；连续生成 100 次没有重复。
  - `src/features/admin/CreateUserDialog.spec.ts`：非法用户 ID 与 11 位密码不能提交；生成的随机密码长度至少 16；提交后请求体字段正确；409 显示“用户 ID 已存在”。
  - `src/features/admin/AdminUsersPage.spec.ts`：渲染分页列表，第一页请求带 `page=0`；“需要设置密码”的用户有标记；翻到第 2 页请求带 `page=1`；403 显示无权限提示。

## 实现要点
- 前端的路由限制只是体验优化，真正的权限检查在后端（403）。
- 初始密码只存在于对话框的本地状态，提交后清除，不写入缓存或日志。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-21` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/admin/routes.spec.ts src/features/admin/randomPassword.spec.ts src/features/admin/CreateUserDialog.spec.ts src/features/admin/AdminUsersPage.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 非管理员无法进入用户管理页
- [ ] 执行记录已填写

## 不在本卡范围
- 修改应用角色、重置密码的界面（P4-28）
- 删除或停用用户、修改用户的显示名称和邮箱（后端没有对应接口）
- 批量创建用户、OIDC / 企业微信登录（P6 候选）
