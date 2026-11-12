---
id: P4-08
title: 个体列表：在类树中选类，分页列出该类的直接个体
phase: P4
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: done
depends_on: [P4-06]
---

# P4-08 个体列表

## 目标
工作台的“个体”标签页上半部分是类层级树，下半部分列出所选类的直接个体（分页）；选中个体后右侧详情面板显示该个体。

## 参考
- `tasks/P4/EPIC.md` 第 8 项
- P3-18：`ListIndividualsByType` 分页返回某类的**直接**个体（不含子类的个体，`owl:Thing` 不展开为全部个体）；`GET /api/projects/{id}/individuals?type=<iri>&page=0&size=50`
- P4-18：响应在契约中补齐为 `IndividualPage`（`individuals: EntityNode[]`、`page`、`size`、`total`）
- `docs/api/openapi.json` 的 operationId：`listIndividualsByType`
- P4-05 的 URL 约定：`kind = individual` 时 `type` 为选中的类、`entity` 为选中的个体

## 产出
- `src/features/individuals/api.ts`：`useIndividuals(projectId, typeIri, page, size)`（键 `queryKeys.individuals(...)`，`typeIri` 为空时不发请求；`placeholderData: keepPreviousData` 让翻页时不闪烁）。
- `src/features/individuals/IndividualList.vue`：props `projectId`、`typeIri`、`selectedIri`；emit `select(node: EntityNode)`；`el-table`（列：显示名称、IRI 的本地名部分）+ `el-pagination`（每页 50，可选 20 / 50 / 100；`el-pagination` 的页码从 1 开始，后端 `page` 从 0 开始，请求时 `page = currentPage - 1`）；已弃用个体使用 P4-06 的 `is-deprecated` 样式；未选类时显示“请先在上方选择一个类”；无个体时显示空状态。
- `src/features/individuals/IndividualsPanel.vue`：上下分栏；上方 `HierarchyTree`（`kind="class"`，选中类时调用 `selectType(iri)`，选中状态来自 `typeIri`），下方 `IndividualList`（选中个体时调用 `selectEntity(iri)`）；在 `src/features/individuals/register.ts` 中注册为 `hierarchyPanels.individual`。
- 文案：`src/features/individuals/locales/`（顶层键 `individuals`）。
- 测试：
  - `src/features/individuals/IndividualList.spec.ts`：请求带 `type`、`page`、`size`，第一页为 `page=0`；切换页码发出新请求且旧数据在新数据到达前保留；`total` 决定分页器页数；点击行 emit `select`。
  - `src/features/individuals/IndividualsPanel.spec.ts`：在类树中选类后 URL 出现 `type`，个体列表随之请求；选中个体后 URL 出现 `entity`，`type` 保持不变；刷新（重新挂载同一 URL）后两者都恢复。

## 实现要点
- 每页最多 100 条，不属于需要虚拟滚动的“大列表”，用 `el-table` + `el-pagination`。
- 分页参数（页码、每页条数）属于临时界面状态，保存在组件内即可，不写进 URL；切换类时页码回到第一页。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-08` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/individuals/IndividualList.spec.ts src/features/individuals/IndividualsPanel.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 个体 Frame 编辑器（P4-23）
- 新建 / 删除个体（P4-11）
- 包含子类个体或推理得到的类型（后端不提供）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-08-individual-list
- 测试：2026-11-12T11:55:09+08:00 通过（3 项）
- 实际改动：新增 individuals 功能：useIndividuals、IndividualList（el-table+分页）、IndividualsPanel（类树+个体列表）及注册；切换类时清空 entity；WorkspacePage.spec 补齐 hierarchy/individuals mock。
- 偏差：WorkspacePage.spec.ts 补 mock handlers，消除类/个体面板挂载后未处理的 paths 请求拒绝（全量 vitest 需要）。
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
