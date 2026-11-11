---
id: P4-28
title: 用户管理（系统管理员）：修改应用角色、重置密码
phase: P4
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-21]
---

# P4-28 修改应用角色与重置密码

## 目标
系统管理员在用户管理页的每一行可以修改该用户的应用角色、为该用户重置密码（包括迁移过来、尚未设置密码的用户）。本卡由 P4-21 拆出（列表与创建用户见 P4-21）。

## 参考
- `docs/03-plan/mvp-scope.md`（“用户管理（管理员）| P3-25 | 管理页”）
- P3-25：`setUserAppRoles{appRoles}`：不能移除最后一个 `SYSTEM_ADMIN`（409 `LAST_SYSTEM_ADMIN`）；`resetUserPassword{newPassword}`（204），密码至少 12 位；普通用户调用返回 403
- 应用角色取值（`backend/wp-app/src/main/java/wprebuild/app/access/AppRole.java`）：`SYSTEM_ADMIN`、`PROJECT_CREATOR`
- `docs/api/openapi.json` 的 operationId：`setUserAppRoles`、`resetUserPassword`
- P4-21 的 `AdminUsersPage.vue`、`api.ts`（`useSetUserAppRoles`、`useResetUserPassword`）、`randomPassword.ts`；P4-25 的 `useCurrentUser`

## 产出
- `src/features/admin/AppRolesDialog.vue`：两个复选框（`SYSTEM_ADMIN`、`PROJECT_CREATOR`，附说明）；409 `LAST_SYSTEM_ADMIN` 显示专门提示；当前用户（`useCurrentUser`）取消自己的 `SYSTEM_ADMIN` 前额外确认。
- `src/features/admin/ResetPasswordDialog.vue`：新密码（至少 12 位，可显示 / 隐藏，“生成随机密码”用 P4-21 的 `randomPassword()`）；成功后提示，并说明“需要把新密码告知用户”；密码只存在于对话框的本地状态，关闭后清除。
- 修改 `src/features/admin/AdminUsersPage.vue`（P4-21）：操作列增加“修改角色”“重置密码”两个按钮，分别打开上面两个对话框。
- 文案：在 `src/features/admin/locales/`（P4-21 创建，顶层键 `admin`）中增加两个对话框的文案。
- 测试：
  - `src/features/admin/AppRolesDialog.spec.ts`：取消勾选 `SYSTEM_ADMIN` 后请求体为 `{appRoles: ["PROJECT_CREATOR"]}`；409 `LAST_SYSTEM_ADMIN` 显示专门提示；修改自己的 `SYSTEM_ADMIN` 前弹出确认。
  - `src/features/admin/ResetPasswordDialog.spec.ts`：11 位密码不能提交；提交后请求体为 `{newPassword}`，成功后对话框中不再有明文。

## 实现要点
- 真正的权限检查在后端（403）；对话框收到 403 时显示无权限提示。
- 本卡在 P4-21 之后进行，没有其它卡并行修改 `AdminUsersPage.vue` 和 `admin/locales/`。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-28` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/admin/AppRolesDialog.spec.ts src/features/admin/ResetPasswordDialog.spec.ts src/features/admin/AdminUsersPage.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 用户列表与创建用户（P4-21）
- 删除或停用用户、修改用户的显示名称和邮箱（后端没有对应接口）
