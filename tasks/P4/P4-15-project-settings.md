---
id: P4-15
title: 项目设置页：标签页注册表与未保存提醒、基本信息、IRI 前缀
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-05, P4-18]
---

# P4-15 项目设置

## 目标
有相应权限的用户可以在项目设置页修改项目名称与描述、IRI 前缀。设置页用标签页注册表组织，后续的新实体与显示语言设置（P4-27）、成员管理（P4-24）各自注册标签页，不修改本卡的页面文件；离开有未保存修改的标签页时会提醒。

## 参考
- `tasks/P4/EPIC.md` 第 15 项（新实体与显示语言拆到 P4-27，成员管理拆到 P4-24）
- P3-08：`updateProject`（`PATCH`，`{name, description}`，需要 `EDIT_PROJECT_SETTINGS`；未提供 `description` 时保留原值）
- P3-22：设置键 `prefixes`：`{ "<前缀名>": "<IRI>" }`，修改需要 `EDIT_PROJECT_PREFIXES`；读取需要 `VIEW_PROJECT`
- P4-18：设置的 GET / PUT 在契约中补齐为 `PrefixSettings` 等命名 schema
- `docs/api/openapi.json` 的 operationId：`getProject`、`updateProject`、`getPrefixSettings`、`putPrefixSettings`
- P4-05 的 URL 约定（`/projects/:projectId/settings`）；P4-01 / P4-05 “按目录收集、注册表扩展”的做法

## 产出
- `src/features/settings/api.ts`：`useProjectSetting(projectId, key)` 与 `useSaveProjectSetting(projectId, key)`（`key` 为 `"prefixes" | "new-entity" | "display-names"`，按 `key` 选择对应的 `get*Settings` / `put*Settings` 调用，返回类型从 `@wp/api-types` 推导；键 `queryKeys.settings(projectId, key)`，保存成功后用返回值写回缓存）；`useUpdateProject(projectId)`（成功后让 `projectDetails` 与 `projects()` 失效）。P4-27 复用这两个函数。
- `src/features/settings/settingsTabs.ts`：设置页标签注册表。`type SettingsTab = { key: string; labelKey: string; order: number; component: Component }`；`import.meta.glob("/src/features/settings/**/*.tab.ts", { eager: true })` 收集各文件导出的 `settingsTab`，纯函数 `mergeSettingsTabs(modules)` 按 `order` 排序、`key` 重复时抛错。标签页组件接收 props `projectId`，用 `emit("update:dirty", boolean)` 报告是否有未保存修改。
- `src/features/settings/ProjectSettingsPage.vue`（`src/features/settings/routes.ts` 以 `project` 路由 `settings` 导出，在 `ProjectShell` 之下）：左侧竖向 `el-tabs`，标签来自 `settingsTabs`；当前标签页保存在查询参数 `tab` 中（未知值回落到第一个）；切换标签页或离开页面时，若当前标签页报告了未保存修改，用 `ElMessageBox.confirm` 提醒，取消则留在原处。
- `src/features/settings/general/GeneralSettingsForm.vue` + `general/general.tab.ts`（`key: "general"`、`order: 10`）：名称（必填）、描述；保存调用 `updateProject`。
- `src/features/settings/prefixes/PrefixSettingsForm.vue` + `prefixes/prefixes.tab.ts`（`key: "prefixes"`、`order: 20`）：可编辑表格（前缀名、IRI、删除），新增行；校验：前缀名非空且不重复、IRI 为绝对 IRI；保存时整体 `PUT`。
- 各表单在保存返回 403 时整体变为只读并显示无权限提示（后端没有“我的权限”接口，以 403 为准）；保存成功用 `ElMessage` 提示。
- 文案：`src/features/settings/locales/`（顶层键 `settings`）。
- 测试：
  - `src/features/settings/settingsTabs.spec.ts`：按 `order` 排序；两个模块注册同一个 `key` 时抛错。
  - `src/features/settings/ProjectSettingsPage.spec.ts`：`?tab=prefixes` 直接打开前缀页；未知的 `tab` 回落到“基本信息”；当前标签页报告未保存修改时切换标签页弹出确认，取消后 `tab` 不变。
  - `src/features/settings/general/GeneralSettingsForm.spec.ts`：名称为空不能保存；保存的请求体为 `{name, description}`，成功后 `projectDetails` 与 `projects()` 失效。
  - `src/features/settings/prefixes/PrefixSettingsForm.spec.ts`：重复的前缀名与非法 IRI 不能保存；保存的请求体是 `{前缀名: IRI}` 对象；`putPrefixSettings` 返回 403 时表单变为只读。

## 实现要点
- 设置的 JSON 形状由后端定义（与迁移包一致），前端类型来自 P4-18 生成的 schema，不手写；表单内部可以有自己的编辑模型，但提交前必须转换回契约类型。
- 标签页注册表让 P4-24、P4-27 只新增自己目录下的 `*.tab.ts`，不修改 `ProjectSettingsPage.vue`，两张卡可以并行。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-15` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/settings/settingsTabs.spec.ts src/features/settings/ProjectSettingsPage.spec.ts src/features/settings/general/GeneralSettingsForm.spec.ts src/features/settings/prefixes/PrefixSettingsForm.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 离开有未保存修改的标签页时有提醒
- [x] 执行记录已填写

## 不在本卡范围
- 新实体设置、显示语言（P4-27）
- 成员管理（P4-24）
- 移入回收站（P4-04 已提供）
- 视图布局、透视图等旧系统的界面设置（不在 MVP）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-15-project-settings
- 测试：2026-11-12T16:20:37+08:00 通过（3 项）
- 实际改动：Added project settings page with tab registry, general (name/description) and IRI prefix forms, dirty-leave confirm, and settings API composables for P4-27 reuse.
- 偏差：Added a one-line comment in app/i18n.ts so Vite re-expands the locale glob to include the new settings locale modules in local test runs.
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
