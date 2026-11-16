---
id: P6-25
title: Web 端：合并实体对话框（层级工具栏入口，选择目标与处理方式，按权限显示）
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-24]
---

# P6-25 Web 端：合并实体对话框

## 目标
有 `MERGE_ENTITIES` 权限的成员在层级树中选中一个实体后，可以通过工具栏的“合并到…”把它合并到另一个同类型的实体：选择目标、处理方式（弃用或删除源实体）、填写说明，确认后提交；成功后选中目标实体，树与详情面板随实时事件刷新。

## 参考
- P6-24：`POST /api/projects/{projectId}/entities/merge`（operationId `mergeEntities`），400 `INVALID_MERGE`（`reason`）、404 `ENTITY_NOT_FOUND`
- BS-13（两种处理方式的含义，用于对话框中的说明文字）
- P4-05：`src/features/workspace/registry.ts`（`FeatureRegistration.hierarchyToolbar`，`import.meta.glob("/src/features/**/register.ts")` 自动注册）、`useWorkspaceRoute.ts`（当前选中的实体）；P4-11：`src/features/entities/register.ts` 与 `EntityToolbar.vue`（工具栏按钮的写法）
- P4-26：Frame 输入组件中的实体选择器（按实体类型搜索）
- P6-04：`src/features/permissions/useProjectPermissions.ts`

## 产出
- 新功能目录 `src/features/merge-entities/`：
  - `register.ts`：`hierarchyToolbar: [MergeEntitiesButton]`；
  - `MergeEntitiesButton.vue`：没有 `MERGE_ENTITIES` 权限、没有选中实体或选中的是内置实体（如 `owl:Thing`）时不显示；
  - `MergeEntitiesDialog.vue`：显示源实体；用 P4-26 的实体选择器选择目标（限定为同一实体类型，排除源本身）；处理方式单选，默认“弃用源实体”，两种方式各有一句说明；说明（可选）；“合并”按钮在选好目标之前不可用，提交中禁用；
  - `api.ts`：`mergeEntities(...)`，类型来自 `@wp/api-types`；
  - `locales/zh-CN.ts`、`locales/en-US.ts`。
- 成功后：用 `useWorkspaceRoute` 把选中项切换到目标实体；不手动刷新树（实时事件 P4-14 / P4-29 会让相关查询失效）。失败时：`INVALID_MERGE` 按 `reason` 显示对应提示，`ENTITY_NOT_FOUND` 提示“实体已不存在，请刷新”。
- 测试：`MergeEntitiesButton.spec.ts`（权限、未选中、内置实体三种隐藏情况）、`MergeEntitiesDialog.spec.ts`（目标未选时按钮不可用、请求体正确、成功后切换选中项、两种错误提示）。

## 实现要点
- 新建独立的功能目录，不修改 `features/entities`（避免与其它卡冲突）；入口通过注册表加入工具栏。
- 对话框中明确写出“合并会产生一个修订，可以在修订历史中回滚”，删除方式额外提示“源实体将从本体中消失”。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-25` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/merge-entities src/features/workspace/registry.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 一次选中多个源实体（树组件目前是单选）；右键菜单入口
- 合并预览
