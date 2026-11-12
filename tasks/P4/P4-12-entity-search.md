---
id: P4-12
title: 顶部搜索框：结果按实体类型分组，回车跳转并在树中定位
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-06]
---

# P4-12 搜索

## 目标
在项目内时，顶部栏有一个搜索框：输入后下拉显示按实体类型分组的结果，可以用键盘选择，回车后跳转到该实体并在对应的层级树中展开、定位。

## 参考
- `tasks/P4/EPIC.md` 第 12 项
- P3-19：`GET /api/projects/{id}/search?q=...&types=...&limit=20`，结果为按相关度排序的 `EntityNode` 列表；中文按 CJK 二元组匹配（“托盘”能搜到“周转托盘”），英文支持前缀（`pal` → `Pallet`）
- P3-24 执行记录的差距：默认搜索只索引无语言标签的 `rdfs:label`，其它语言的标签要先加进项目的显示名称设置（P4-27）才能搜到——搜索无结果时的提示中说明这一点
- P4-18：响应在契约中补齐为 `EntityNode[]`
- `docs/api/openapi.json` 的 operationId：`searchEntities`
- P4-03 的 `AppTopBar` 搜索插槽；P4-05 的 `entityTypeToKind` / `linkTo`；P4-06 的 `HierarchyTree` 定位

## 产出
- `src/features/search/useEntitySearch.ts`：`useEntitySearch(projectId, query)`：输入防抖 250ms，裁剪后为空不请求；`limit: 50`；键 `queryKeys.search(projectId, q, types, 50)`；返回按 `entityType` 分组后的结果（分组顺序：类、对象属性、数据属性、注解属性、个体、数据类型；组内保持后端的相关度顺序）。
- `src/features/search/GlobalSearch.vue`：只在 `/projects/:projectId/**` 下显示；输入框（`aria-label`、`Ctrl+K` / `⌘K` 聚焦）+ 下拉面板（`role="listbox"`，分组标题 + 结果行：显示名称、本地名、已弃用样式）；上下键移动高亮，回车选中高亮项（没有高亮时取第一项），`Esc` 关闭；选中后 `router.push(linkTo(entityType, iri))`，`HierarchyTree` 在 `selectedIri` 变化时自动定位（P4-06 已实现）。`linkTo` 返回 `null` 的结果（数据类型等）只显示、不可跳转。
- 修改 `src/app/layout/AppLayout.vue`：给 `AppTopBar` 的 `search` 插槽传入 `GlobalSearch`。
- 文案：`src/features/search/locales/`（顶层键 `search`，包括无结果时关于显示名称设置的提示）。
- 测试：
  - `src/features/search/useEntitySearch.spec.ts`：快速连续输入只发一次请求；结果按实体类型分组且组内顺序不变；空白输入不请求。
  - `src/features/search/GlobalSearch.spec.ts`：键盘下移两次后回车，跳转到第二个结果对应的 `/projects/:id/<kind>?entity=<iri>`；个体结果跳转到 `kind = individual`；`Esc` 关闭下拉；无结果时显示提示文案。

## 实现要点
- 搜索的是当前项目，`projectId` 来自路由；不在项目内时不渲染搜索框。
- 不在前端对结果做二次过滤或重新排序，相关度以后端为准。
- 跳转使用 `push`（用户可以用浏览器后退回到之前的实体），这与工作台内点击树节点使用 `replace` 不同，是有意为之。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-12` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/search/useEntitySearch.spec.ts src/features/search/GlobalSearch.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 跨项目搜索、高级搜索语法
- Frame 编辑器内的实体选择器（P4-26 的 `EntityPicker`）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-12-entity-search
- 测试：2026-11-12T14:49:11+08:00 通过（3 项）
- 实际改动：在顶栏接入 GlobalSearch：防抖搜索、按实体类型分组、键盘选择后 push 跳转并定位树；无结果提示显示名称设置。
- 偏差：无
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
