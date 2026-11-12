---
id: P4-05
title: 项目工作台：项目外壳路由、左侧层级面板与右侧详情面板、URL 保存选中状态
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-03]
---

# P4-05 项目工作台

## 目标
打开一个项目后进入工作台：左侧是层级面板（按层级类型切换），右侧是选中实体的详情面板；当前项目、层级类型和选中实体都保存在 URL 中，刷新或分享链接后恢复同样的界面。

## 参考
- `tasks/P4/EPIC.md` 第 5 项
- P3-08（`getProject`：无权限 403、回收站中 409 `PROJECT_TRASHED`、不存在 404）
- P3-13（层级 `kind` 的路径写法：`class`、`object-property`、`data-property`、`annotation-property`）
- `docs/api/openapi.json` 的 operationId：`getProject`
- `.cursor/rules/30-vue-web.mdc`（Pinia 只存界面状态）

## 产出
- URL 约定（后续卡都遵守）：
  - `/projects/:projectId` 重定向到 `/projects/:projectId/class`；
  - `/projects/:projectId/:kind`，`kind` 用路由正则限定为 `class|object-property|data-property|annotation-property|individual`；
  - 查询参数 `entity`：选中实体的 IRI；`kind = individual` 时另有 `type`：左侧类树中选中的类 IRI（个体列表按它过滤，P4-08）；
  - 同一项目下的其它页面用静态段：`/projects/:projectId/history`（P4-13）、`/projects/:projectId/settings`（P4-15）。
- `src/features/workspace/hierarchyKinds.ts`：`export type WorkspaceKind`；`HIERARCHY_KINDS`（四种层级）与 `export type HierarchyKind = (typeof HIERARCHY_KINDS)[number]`；`export type EntityTypeName = EntityNode["entityType"]`（类型来自 `@wp/api-types`）；`kindToEntityType(kind)`（`class` → `CLASS`、`object-property` → `OBJECT_PROPERTY`、`data-property` → `DATA_PROPERTY`、`annotation-property` → `ANNOTATION_PROPERTY`、`individual` → `NAMED_INDIVIDUAL`）；`entityTypeToKind(entityType)`；`searchTypeOf(entityType)`（P3-19 的 `types` 取值，例如 `NAMED_INDIVIDUAL` → `named-individual`）；`export type SearchType = "class" | "object-property" | "data-property" | "annotation-property" | "named-individual" | "datatype"`（契约中 `types` 是一个字符串参数，多个取值用逗号拼接，`joinSearchTypes(types: SearchType[])` 负责拼接）。`entityTypeToKind` 对 `DATATYPE`、`IRI`、`ONTOLOGY` 返回 `null`（P4-18 把 `entityType` 补成了枚举）。
- `src/features/workspace/useWorkspaceRoute.ts`：`useWorkspaceRoute()` 返回只读的 `projectId`、`kind`、`entityIri`、`typeIri`（均为 `ComputedRef`）以及 `selectEntity(iri | null)`、`selectType(iri | null)`、`setKind(kind)`、`linkTo(entityType, iri): RouteLocationRaw | null`（`NAMED_INDIVIDUAL` 得到 `kind = individual`、只带 `entity` 不带 `type`：详情面板显示该个体，个体列表提示先选类；`DATATYPE`、`IRI`、`ONTOLOGY` 没有对应页面，返回 `null`，调用方只显示文字）；修改一律用 `router.replace`，不产生多余的历史记录；切换 `kind` 时清空 `entity` 与 `type`。URL 是唯一的事实来源，不再复制到 Pinia。
- `src/features/workspace/workspaceStore.ts`：Pinia store，只存界面布局状态（左右面板宽度、左侧面板是否折叠），持久化到 `localStorage` 的 `wp.workspace.layout`。
- `src/features/workspace/ProjectShell.vue`（路由 `/projects/:projectId`，父路由；它的子路由是各功能 `routes.ts` 导出的 `project` 路由）：用 `getProject`（键 `queryKeys.projectDetails(id)`）加载项目；403 / 404 / 409 `PROJECT_TRASHED` 显示对应的错误页和“返回项目列表”链接，不渲染子路由；正常时渲染 `<RouterView />`。项目级的长期副作用（P4-29 的事件订阅）以后挂在这里。
- `src/features/workspace/WorkspacePage.vue`（子路由 `:kind`）：`el-splitter` 左右两栏；左栏 `HierarchyPanel`，右栏 `EntityDetailPanel`；工具栏右侧有“修订历史”“项目设置”两个链接，以及注册表 `workspaceToolbar` 中的组件。
- `src/features/workspace/HierarchyPanel.vue`：顶部 `el-tabs` 切换五种 `kind`（文字走 i18n）；标签页内容按 `kind` 从注册表的 `hierarchyPanels` 取组件，未注册时显示“尚未实现”的空状态；标签页内容上方依次渲染注册表 `hierarchyToolbar` 中的组件。
- `src/features/workspace/EntityDetailPanel.vue`：未选中实体时显示空状态；选中后按 `kindToEntityType(kind)` 从注册表的 `detailEditors` 取编辑器组件，传入 `projectId`、`iri`；未注册时显示 IRI 和“尚未实现”。面板内用 `el-tabs`：第一页是“详情”，其后依次是注册表 `detailTabs` 中的标签页（例如 P4-13 的“历史”），同样传入 `projectId`、`iri`。
- `src/features/workspace/registry.ts`：工作台注册表。`import.meta.glob("/src/features/**/register.ts", { eager: true })` 收集各功能导出的 `registration: FeatureRegistration`，用纯函数 `mergeRegistrations(modules)` 合并（便于测试），通过 `useWorkspaceRegistry()` 提供：
  - `hierarchyPanels?: Partial<Record<WorkspaceKind, Component>>`（P4-06、P4-07、P4-08），`HierarchyPanel` 渲染时传入 props `projectId`、`kind`（同一个组件可以注册到多个 `kind`）
  - `detailEditors?: Partial<Record<EntityTypeName, Component>>`（P4-09、P4-10、P4-23），传入 `projectId`、`iri`，并以 `:key="iri"` 渲染
  - `detailTabs?: { key: string; labelKey: string; component: Component }[]`（P4-13 的“历史”），传入 `projectId`、`iri`
  - `workspaceToolbar?: Component[]`（P4-29 的连接状态、P4-19 的下载菜单），`WorkspacePage` 渲染时传入 `projectId`
  - `hierarchyToolbar?: Component[]`（P4-11 的新建 / 删除），`HierarchyPanel` 在每个标签页顶部渲染，传入 `projectId`、`kind`
  - `revisionActions?: Component[]`（P4-19 的“下载此修订”，由 P4-13 的差异视图渲染，组件接收 `projectId: string`、`revision: number`（修订号））
  同一个 `kind` 或实体类型被两个模块注册时抛错；数组按模块路径排序后合并，顺序稳定。本卡不注册任何内容。
- `src/features/workspace/routes.ts`：以 `layout` 路由导出 `/projects/:projectId`（`ProjectShell`，含重定向与 `:kind` 子路由）；修改 `src/app/router.ts`，把各功能导出的 `project` 路由挂为 `ProjectShell` 的子路由。
- 文案：`src/features/workspace/locales/`（顶层键 `workspace`，含五种 `kind` 的名称）。
- 测试：
  - `src/features/workspace/useWorkspaceRoute.spec.ts`：从 URL 解析出 `kind` / `entity` / `type`（包含 `#` 与 `/` 的 IRI 往返不丢失）；`selectEntity` 用 `replace` 更新查询参数；`setKind` 清空选中；非法 `kind` 不匹配路由。
  - `src/features/workspace/ProjectShell.spec.ts`：`getProject` 返回 409 `PROJECT_TRASHED` 时显示回收站提示且不渲染子路由；403 显示无权限提示。
  - `src/features/workspace/WorkspacePage.spec.ts`：带 `?entity=` 的 URL 直接打开时，详情面板拿到同一个 IRI（模拟刷新恢复）；切换标签页后 URL 的 `kind` 改变。
  - `src/features/workspace/registry.spec.ts`：两个模块注册同一个 `kind` 或同一个实体类型时 `mergeRegistrations` 抛错；`detailTabs`、`workspaceToolbar`、`hierarchyToolbar` 的合并顺序与模块路径顺序一致。
  - `src/features/workspace/HierarchyPanel.spec.ts`：用桩注册表验证注册的面板组件收到 `projectId` 与当前 `kind`；`hierarchyToolbar` 中的组件渲染在标签页内容上方并收到同样的 props。

## 实现要点
- IRI 只放在查询参数里，交给 Vue Router 编码；不要拼进路径段。
- `el-splitter` 在 Element Plus 2.14 中可用；如果实际版本中没有，用两栏 flex 布局加可拖动分隔条，不引入新依赖，并在执行记录中写明。
- 注册表（以及 P4-01 的按目录收集路由、文案）的目的，是让并行的功能卡只新增自己目录下的文件，不去改同一个共享文件；Agent 不能自己解决合并冲突。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-05` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/workspace/useWorkspaceRoute.spec.ts src/features/workspace/ProjectShell.spec.ts src/features/workspace/WorkspacePage.spec.ts src/features/workspace/registry.spec.ts src/features/workspace/HierarchyPanel.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 刷新页面后项目、层级类型、选中实体都能恢复
- [x] 执行记录已填写

## 不在本卡范围
- 层级树（P4-06、P4-07）、个体列表（P4-08）、各种 Frame 编辑器（P4-09、P4-10、P4-23）
- 修订历史页与项目设置页（P4-13、P4-15），本卡只放链接
- 事件订阅（P4-14、P4-29）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-05-project-workspace
- 测试：2026-11-12T09:08:29+08:00 通过（3 项）
- 实际改动：Added ProjectShell/WorkspacePage with hierarchy and detail panels, URL-backed kind/entity/type selection, layout Pinia store, feature registry, and locale keys. Router nests feature project routes under ProjectShell; import.meta.glob uses exhaustive so new locale/route modules are discovered.
- 偏差：Set import.meta.glob exhaustive:true on i18n, router, and registry so newly added modules are matched under Vite/Vitest. el-splitter is available in Element Plus 2.14.8 (no flex fallback).
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
