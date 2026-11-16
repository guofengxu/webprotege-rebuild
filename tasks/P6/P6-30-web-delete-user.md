---
id: P6-30
title: Web 端：用户管理页删除用户，并标出已删除的用户
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-29]
---

# P6-30 Web 端：用户管理页删除用户

## 目标
系统管理员在用户管理页可以删除用户：二次确认时必须输入该用户的 ID；已删除的用户在列表中带“已删除”标记，不能再对其执行任何操作；删除被拒绝时给出能照着处理的提示。

## 参考
- P6-29：`DELETE /api/admin/users/{userId}`（operationId `deleteUser`，204）、`AdminUserResponse.deletedAt`、409 `CANNOT_DELETE_SELF` / `LAST_SYSTEM_ADMIN` / `LAST_MANAGER`（扩展字段 `projectIds`）
- P4-21、P4-28：`src/features/admin/AdminUsersPage.vue`、`AppRolesDialog.vue`、`ResetPasswordDialog.vue`、`api.ts`、`locales/`
- P4-02：`src/shared/api/problem.ts`（`ProblemDetail` 的解析与错误码）；P4-25：`useAuth`（当前用户）

## 产出
- `src/features/admin/api.ts`：`deleteUser(userId)`，类型来自 `@wp/api-types`；成功后让管理员用户列表的查询失效（沿用现有的查询键）。
- `src/features/admin/DeleteUserDialog.vue`：说明删除的后果（立即退出、移出全部项目、不可恢复、历史中保留账户 ID）；输入框内容与该用户 ID 完全一致时，“删除”按钮才可用；提交中禁用按钮。
- `src/features/admin/AdminUsersPage.vue`：
  - 每行增加“删除”操作，打开 `DeleteUserDialog`；当前登录的管理员自己那一行不显示删除；
  - `deletedAt` 不为空的行：用户 ID 旁显示“已删除”标签，角色、重置密码、删除三个操作都不显示；
  - 409 的提示：`CANNOT_DELETE_SELF`、`LAST_SYSTEM_ADMIN` 各有一句说明；`LAST_MANAGER` 列出 `projectIds`，提示先在这些项目中指定其他管理者。
- `src/features/admin/locales/zh-CN.ts`、`en-US.ts`：新增文案（两种语言的键保持一致）。
- 测试：
  - `DeleteUserDialog.spec.ts`：输入不一致时按钮不可用，一致时可用；确认后调用 `deleteUser`；
  - `AdminUsersPage.spec.ts`：已删除的行显示标签且没有操作；自己那一行没有删除；三种 409 分别显示对应提示（`LAST_MANAGER` 显示项目 ID）；删除成功后列表重新获取。

## 实现要点
- 删除操作和其它两个操作一样，只对系统管理员可见（路由已有 `meta.requiresSystemAdmin`），不新增权限判断。
- 已删除用户的“已删除”只是展示，列表默认仍然显示它们，不做筛选器。
- 修订历史页继续显示账户 ID（P6-29 的决定），本卡不改 `features/history`。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-30` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/admin/DeleteUserDialog.spec.ts src/features/admin/AdminUsersPage.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 后端接口（P6-29）
- 恢复已删除的用户、批量删除
- 修订历史中显示用户的显示名称
