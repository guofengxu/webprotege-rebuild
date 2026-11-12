---
id: P4-11
title: 新建 / 删除实体：工具栏与树节点右键菜单，一次输入多个名称，删除前确认
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-07, P4-08]
---

# P4-11 新建 / 删除实体

## 目标
用户可以在层级树或个体列表上，通过工具栏按钮或右键菜单新建实体（一次输入多个名称，新实体成为当前选中实体的子类 / 子属性，个体则属于当前选中的类），也可以删除选中的实体（删除前确认）。

## 参考
- `tasks/P4/EPIC.md` 第 11 项
- P3-14：`CreateEntitiesRequest{entityType, names, lang, parents}`，`entityType` 取值 `CLASS`、`OBJECT_PROPERTY`、`DATA_PROPERTY`、`ANNOTATION_PROPERTY`、`NAMED_INDIVIDUAL`；类与个体的 `parents` 都是类 IRI（个体的父类即类型）；返回创建的 `EntityNode` 列表和修订号；`DeleteEntitiesRequest{iris}`，返回修订号；权限不足返回 403
- BS-06（新实体 IRI 的生成方式由项目的新实体设置决定，前端只提交名称）、BS-07（创建与删除）
- P4-18：两个接口的响应在契约中补齐为 `CreatedEntities{entities, revision}` 与 `RevisionResponse{revision}`
- `docs/api/openapi.json` 的 operationId：`createEntities`、`deleteEntities`、`getNewEntitySettings`（只读，用于默认语言）
- P4-06 的 `HierarchyTree` 与 `ClassHierarchyPanel`、P4-07 的 `PropertyHierarchyPanel`、P4-08 的 `IndividualList` 与 `IndividualsPanel`
- P4-05 的 `useWorkspaceRoute`、`kindToEntityType` 与注册表（`hierarchyToolbar`）；P4-02 的 `queryKeys`（前缀键 `individualsAll`、`searchAll`）

## 产出
- 操作对象的约定（`kind` 与 `entityType` 的对应关系用 P4-05 的 `kindToEntityType`）：
  | 标签页 | “新建”创建的实体 | 父实体（`parents`） | “删除”的对象 |
  |---|---|---|---|
  | `class` 与三种属性 | 该 `kind` 对应的实体类型 | 当前选中实体（`entity`）；未选中时为空，即顶层 | 当前选中实体 |
  | `individual` | `NAMED_INDIVIDUAL` | 当前选中的类（`type`）；未选类时“新建”禁用 | 当前选中个体（`entity`） |
- `src/features/entities/api.ts`：`useCreateEntities(projectId)`、`useDeleteEntities(projectId)`；成功后让受影响的查询失效：创建 → 该层级的全部查询（`queryKeys.hierarchy(projectId, kind)`；父节点原来没有子节点时，它的 `hasChildren` 来自上一级的子节点查询，只让父节点的子节点失效不够）或 `queryKeys.individualsAll(projectId)`，以及 `queryKeys.searchAll(projectId)`；删除 → 同样的层级或个体查询、`searchAll`，以及被删实体的 Frame（`queryKeys.frame`）。
- `src/features/entities/CreateEntitiesDialog.vue`：props `projectId`、`entityType`、`parent?: { iri: string; label: string }`（右键菜单传节点的显示名称，工具栏只有 URL 中的 IRI，传 IRI 的本地名）；多行文本框，一行一个名称（去掉首尾空白与空行、去重，最多 100 个）；可选语言标签（默认取项目新实体设置中的 `defaultLang`，键 `queryKeys.settings(projectId, "new-entity")`，读取失败时留空）；显示“将创建在：<label>下”；提交后关闭，并把第一个新实体设为选中（`selectEntity`）。
- `src/features/entities/DeleteEntitiesConfirm.ts`：`confirmDelete(items: { iri: string; label: string }[], t): Promise<boolean>`，用 `ElMessageBox.confirm` 列出要删除的名称（超过 10 个只列前 10 个并显示总数），按钮使用危险样式；确认并删除成功后调用 `selectEntity(null)`。
- `src/features/entities/EntityContextMenu.vue`：props `projectId`、`kind`、`target: { node: EntityNode; event: MouseEvent; source: "tree" | "individual-row" } | null`；在鼠标位置弹出（`el-dropdown` 的虚拟触发）；菜单项按上表：层级树节点上是“新建子类 / 子属性”与“删除”，`individual` 标签页的类树节点上是“新建个体”（父实体为该类），个体行上是“删除”；`Esc` 关闭，支持键盘上下选择。
- `src/features/entities/EntityToolbar.vue`：props `projectId`、`kind`；按钮“新建”“删除”，按上表决定对象与禁用状态；通过 `src/features/entities/register.ts` 注册到 P4-05 的 `hierarchyToolbar`，不修改 `HierarchyPanel.vue`。
- 修改 `HierarchyTree.vue`（emit `contextmenu(node, event)`）和 `IndividualList.vue`（emit `row-contextmenu(node, event)`），只增加事件，不改已有行为。
- 修改 `ClassHierarchyPanel.vue`（P4-06）、`PropertyHierarchyPanel.vue`（P4-07）、`IndividualsPanel.vue`（P4-08）：监听上面两个事件并渲染 `EntityContextMenu`，只增加这一项。
- 文案：`src/features/entities/locales/`（顶层键 `entities`）。
- 测试：
  - `src/features/entities/CreateEntitiesDialog.spec.ts`：三行（含一行空行、一行重复）只提交两个名称；`entityType` 与 `parents` 按当前上下文填写；成功后选中第一个新实体，父节点的子节点查询失效；403 显示 `errors.PERMISSION_DENIED`。
  - `src/features/entities/DeleteEntitiesConfirm.spec.ts`：取消时不调用 `deleteEntities`；确认后请求体为 `{iris: [...]}`，相关层级查询失效，选中状态清空。
  - `src/features/entities/EntityContextMenu.spec.ts`：在类节点上右键出现“新建子类”；在 `individual` 标签页的类节点上右键出现“新建个体”；在个体行上右键出现“删除”；键盘 `Esc` 关闭菜单。
  - `src/features/entities/EntityToolbar.spec.ts`：`individual` 标签页未选类时“新建”禁用，选类后创建请求的 `entityType` 为 `NAMED_INDIVIDUAL`、`parents` 为该类；未选中实体时“删除”禁用。

## 实现要点
- 新实体的 IRI 由后端按新实体设置生成（UUID / 用户输入名称 / OBO，BS-06），前端不拼 IRI。
- 名称在前端只做裁剪与去重，不做其它校验；后端返回 400 时显示 `detail`。
- 后端没有“我在本项目中的权限”接口：工具栏和右键菜单对所有成员都显示，以后端的 403 为准（显示 `errors.PERMISSION_DENIED`），不在前端猜测权限。
- 操作后立即让本地查询失效以获得即时反馈；P4-14 的事件会再触发一次失效，这是预期的重复，不需要去重。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-11` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/entities/CreateEntitiesDialog.spec.ts src/features/entities/DeleteEntitiesConfirm.spec.ts src/features/entities/EntityContextMenu.spec.ts src/features/entities/EntityToolbar.spec.ts src/features/hierarchy/HierarchyTree.spec.ts src/features/individuals/IndividualList.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 删除前一定有确认
- [x] 执行记录已填写

## 不在本卡范围
- 拖拽移动实体、合并实体、批量导入（不在 MVP）
- 新实体设置本身（P4-15）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-11-entity-create-delete
- 测试：2026-11-12T13:59:13+08:00 通过（3 项）
- 实际改动：新增实体新建/删除工具栏与右键菜单：多行名称创建、删除确认、查询失效与选中同步；接入类/属性树与个体列表。
- 偏差：无
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
