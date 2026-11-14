---
id: P6-06
title: Web 端：没有对应权限时，项目设置表单为只读
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: done
depends_on: [P6-04]
---

# P6-06 只读的项目设置表单（Web 端）

## 目标
没有管理权限的成员打开项目设置页时，各个表单显示为只读，并说明需要什么权限；他们不会先修改再在保存时收到 403。本卡由原先的 Frame / 设置只读卡拆出（Frame 部分见 P6-05）。

## 参考
- P6-04：`src/features/permissions/useProjectPermissions.ts`（`permission(action)` 返回 `allowed` / `denied` / `pending`）
- P4-15 / P4-27：`settings/general/GeneralSettingsForm.vue`、`settings/prefixes/PrefixSettingsForm.vue`、`settings/naming/NewEntitySettingsForm.vue`、`settings/naming/DisplayNameSettingsForm.vue`，以及各自的 `*.spec.ts`
- 后端对应的操作：`project/ProjectSettingKeys.editAction`：前缀为 `EDIT_PROJECT_PREFIXES`，新实体设置为 `EDIT_NEW_ENTITY_SETTINGS`，显示名称为 `EDIT_PROJECT_SETTINGS`；项目名称与描述（`updateProject`）为 `EDIT_PROJECT_SETTINGS`。读取设置只需要 `VIEW_PROJECT`

## 产出
- 四个设置表单按上面的对应关系检查各自的操作：`denied` 时表单禁用、不显示保存按钮，并显示“只读：需要管理权限才能修改”；`pending` 时表单禁用、不显示提示；`allowed` 时行为不变。
- 文案：`src/features/settings/locales/`（zh-CN 与 en-US 同步）。
- 测试：`general/GeneralSettingsForm.spec.ts`、`prefixes/PrefixSettingsForm.spec.ts`、`naming/NewEntitySettingsForm.spec.ts`、`naming/DisplayNameSettingsForm.spec.ts` 各补两例：权限 `actions` 中没有对应操作时，表单禁用、不显示保存按钮、显示只读提示；有对应操作时可以保存。

## 实现要点
- 只读判断只读取 P6-04 的 `permission()`，不新增权限请求。
- 成员标签页不在本卡范围：没有 `EDIT_SHARING_SETTINGS` 时它已经显示 403 提示（P4-24）。
- 不修改 `ProjectSettingsPage.vue` 与标签注册表（P4-15 的 `settingsTabs`）。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-06` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/settings/general/GeneralSettingsForm.spec.ts src/features/settings/prefixes/PrefixSettingsForm.spec.ts src/features/settings/naming/NewEntitySettingsForm.spec.ts src/features/settings/naming/DisplayNameSettingsForm.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- Frame 编辑器（P6-05）
- 成员标签页（P4-24、P6-07）
- 后端接口（P6-01）

## 执行记录
- 日期：2026-11-14
- 分支：feature/p6-06-web-read-only-settings
- 测试：2026-11-14T09:16:26+08:00 通过（3 项）
- 实际改动：四个设置表单按 EDIT_* 权限在 denied/pending 时禁用并隐藏保存；denied 显示 settings.readOnlyDenied；沿用 useProjectPermissions 与 403 forbiddenByServer。
- 偏差：无
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
