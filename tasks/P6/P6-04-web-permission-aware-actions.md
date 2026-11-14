---
id: P6-04
title: Web 端：读取“我的权限”，按权限显示新建、删除与回滚操作
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P6-01]
---

# P6-04 按权限显示实体操作（Web 端）

## 目标
工作台在渲染前就知道当前用户能做什么：只能查看的成员看不到新建、删除和回滚按钮；角色变化后，界面无须刷新页面即可跟着变化。对应 M4 差距清单第 4 项的 Web 部分。

## 参考
- P6-01：`GET /api/projects/{id}/permissions/me`，operationId `getMyProjectPermissions`，响应 `{projectId, role, systemAdmin, actions}`
- P4-02：`src/shared/api/queryKeys.ts`（全部查询键集中定义，失效一律用前缀键）、`src/shared/api/client.ts`
- P4-14：`src/features/events/applyProjectEvent.ts`。`PermissionsChanged` 与 `resync` 会让 `queryKeys.project(id)` 前缀失效
- P4-11：`src/features/entities/EntityToolbar.vue`、`EntityContextMenu.vue`；P4-13：`src/features/history/RevertRevisionButton.vue`
- `docs/03-plan/m4-acceptance.md`「差距清单」第 4 项

## 产出
- `src/shared/api/queryKeys.ts`：新增 `myPermissions(id)`，值为 `[...queryKeys.project(id), "permissions", "me"]`。它放在 `project(id)` 前缀下，所以现有的 `PermissionsChanged` / `resync` 失效规则会自动覆盖它，**不改** `applyProjectEvent.ts`。`queryKeys.spec.ts` 补一条“以 `project(id)` 为前缀”的断言。
- `src/features/permissions/useProjectPermissions.ts`：
  - `useProjectPermissions(projectId: MaybeRefOrGetter<string>)`，用 TanStack Vue Query 请求 `getMyProjectPermissions`，键为 `queryKeys.myPermissions(projectId)`；
  - 返回 `permission(action: ProjectAction): ComputedRef<"allowed" | "denied" | "pending">`。规则：请求进行中为 `pending`；成功时按 `actions` 是否包含该操作判定；接口返回 403 时一律为 `denied`；其它错误（网络、5xx）为 `allowed`，由后端的 403 兜底；
  - `ProjectAction` 类型取自 `@wp/api-types` 中该响应的 `actions` 元素类型，不手写枚举。
- `src/features/permissions/useProjectPermissions.spec.ts`：覆盖上面四种情况；调用 `queryClient.invalidateQueries({ queryKey: queryKeys.project(id) })` 后会重新请求权限。
- `src/features/entities/EntityToolbar.vue`、`EntityContextMenu.vue`：新建与删除按实体类型分别检查 `CREATE_CLASS` / `DELETE_CLASS`、`CREATE_PROPERTY` / `DELETE_PROPERTY`、`CREATE_INDIVIDUAL` / `DELETE_INDIVIDUAL`：`denied` 时不渲染该项，`pending` 时显示但禁用。
- `src/features/history/RevertRevisionButton.vue`：按 `REVERT_CHANGES` 处理，规则同上。
- 测试：`EntityToolbar.spec.ts`、`EntityContextMenu.spec.ts`、`RevertRevisionButton.spec.ts` 用 `mockFetch` 返回不同的 `actions`：`CAN_VIEW` 看不到新建、删除、回滚；`CAN_EDIT` 能看到；权限请求进行中时按钮禁用；权限请求返回 500 时按钮仍然显示。原有的 403 提示测试保留。

## 实现要点
- 后端仍是权限的唯一裁决者：保留现有的 403 处理与提示，前端隐藏控件只是提前反馈。
- 不在前端写“角色 → 操作”的映射，只读接口返回的 `actions`。
- 新文案放在 `src/features/permissions/locales/`（如有需要），不改其它功能目录的文案。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-04` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/permissions/useProjectPermissions.spec.ts src/shared/api/queryKeys.spec.ts src/features/entities/EntityToolbar.spec.ts src/features/entities/EntityContextMenu.spec.ts src/features/history/RevertRevisionButton.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- Frame 编辑器的只读模式（P6-05）、设置表单的只读模式（P6-06）
- 项目列表上“移入回收站”按钮的权限判断（项目列表没有逐个项目的权限信息）
- 下载菜单（所有能查看的角色都有 `DOWNLOAD_PROJECT`）
- 后端接口（P6-01）

## 执行记录
- 日期：2026-11-14
- 分支：feature/p6-04-web-permission-aware-actions
- 测试：2026-11-14T08:20:25+08:00 通过（3 项）
- 实际改动：Added queryKeys.myPermissions and useProjectPermissions; EntityToolbar, EntityContextMenu, and RevertRevisionButton hide denied actions and disable pending ones; non-403 errors stay allowed so the backend remains the authority.
- 偏差：无
- 审查：vue-reviewer, typescript-reviewer, a11y-architect, (fixed, empty-menu, open, disabled-item, keyboard, focus, and, handler, permission, guards)
