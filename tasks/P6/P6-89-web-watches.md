---
id: P6-89
title: Web：实体右键菜单关注对话框与「我的关注」页面
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-45, P6-86]
---

# P6-89 Web 关注界面

## 目标
用户可在层级树的实体右键菜单里设置无 / 实体 / 分支关注，并在项目内的「我的关注」页面查看和取消自己的关注；写成功后本地数据立即刷新。

## 参考
- BS-21；旧 `WatchPresenter`（三选一对话框）、`WatchUiAction`（层级树右键菜单）、`WatchedEntitiesPortletPresenter`（行为参照）
- EPIC「关注（P6-45）」与「审查修订（2026-11-24）」；`@wp/api-types` 已含 P6-86 的关注 API
- 现有入口与路由写法：`features/entities/EntityContextMenu.vue`；`features/history/routes.ts` + `register.ts`（项目子路由）；`features/workspace/useWorkspaceRoute.ts`（`?entity=` 参数）

## 产出
- 新建 `features/watches/`：
  - `WatchDialog.vue`：无 / 实体 / 分支三选一，打开时读取当前关注，确定时调用 P6-86 的设置接口。
  - `EntityContextMenu` 增加「关注…」菜单项，打开 `WatchDialog`；当前用户没有 `WATCH_CHANGES`（`GET /api/projects/{id}/permissions/me`）时禁用该菜单项。
  - `WatchesPage.vue`：项目子路由 `/projects/:projectId/watches`，按 `routes.ts` + `register.ts` 的方式注册（同 `features/history`）；`ProjectShell` 导航增加入口。列表显示实体显示名、实体类型、关注类型（直接用 P6-86 列表响应中的显示名），点击实体跳到工作区并带 `?entity=`，每行可取消关注。
  - `api.ts` 与 `queryKeys`：设置或清除成功后让“当前实体的关注”和“我的关注列表”两个查询失效。
- i18n 文案（中英）；`WatchDialog`、菜单项禁用、`WatchesPage` 的组件测试覆盖主路径。

## 实现要点
- 不手写 API 类型；只用 `@wp/api-types`。
- 关注**不**发项目事件（BS-21）：不改 `applyProjectEvent` 与事件联合类型；同一用户其它标签页的刷新不在本卡范围。
- 不做关注变更历史全表扫描 UI。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-89` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm --dir frontend/wp-web test -- --run
pnpm --dir frontend/wp-web exec vue-tsc --noEmit
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 邮件预览、站内通知中心、移动端、多标签页同步
