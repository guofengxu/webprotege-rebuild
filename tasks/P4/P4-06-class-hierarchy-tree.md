---
id: P4-06
title: 类层级树：el-tree-v2 虚拟滚动、按需加载子节点、通过 paths 定位选中实体
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-05, P4-18]
---

# P4-06 类层级树

## 目标
工作台左侧显示类层级树：上万个节点也能流畅滚动；展开时才加载子节点；节点显示显示名称，已弃用的实体有区别样式；URL 中带着选中实体打开时，树会自动展开到该实体并滚动到可见位置。

## 参考
- `tasks/P4/EPIC.md` 第 6 项
- BS-08（层级）、BS-09（显示名称）：`docs/01-rea/behavior-specs.md`
- P3-13：根、直接子节点、到根路径三个查询；子节点按显示名称不区分大小写排序；不存在的 IRI 返回空列表而不是 404；`EntityNode` 字段 `iri`、`entityType`、`displayName`、`hasChildren`、`deprecated`
- P4-18：这三个接口的响应在契约中补齐为 `EntityNode[]` / `EntityNode[][]`
- `docs/api/openapi.json` 的 operationId：`getHierarchyRoots`、`getHierarchyChildren`、`getHierarchyPaths`
- P4-05：`useWorkspaceRoute`、`HierarchyKind`、注册表（`hierarchyPanels`）

## 产出
- `src/features/hierarchy/api.ts`：`fetchRoots(client, projectId, kind)`、`fetchChildren(client, projectId, kind, parentIri)`、`fetchPaths(client, projectId, kind, entityIri)`；对应的查询键取自 `queryKeys.hierarchyRoots/hierarchyChildren/hierarchyPaths`。
- `src/features/hierarchy/useHierarchyTree.ts`：`useHierarchyTree(projectId: Ref<string>, kind: Ref<HierarchyKind>)`，返回：
  - `treeData: ShallowRef<TreeNodeData[]>`：交给 `el-tree-v2` 的数据；
  - `expandedKeys: ShallowRef<string[]>`：当前展开的节点 key，绑定到 `el-tree-v2`（`default-expanded-keys` 与 `setExpandedKeys`），`expand`、`locate` 和缓存刷新都维护它；
  - `expand(nodeKey)`：通过 `queryClient.fetchQuery` 加载子节点并替换占位节点；
  - `locate(entityIri): Promise<string | null>`：调用 `getHierarchyPaths`，取第一条路径，依次加载并展开路径上的每个父节点，返回目标节点的 key；
  - 订阅 `queryClient.getQueryCache()` 中以 `queryKeys.hierarchy(projectId, kind)` 为前缀的查询：某个父节点的子节点查询被重新获取后，就地替换该父节点下的子节点（保留仍然存在的子节点的展开状态）。这样 P4-14 只需要让查询失效，树就会刷新。
- 节点模型 `TreeNodeData`：`key`（从根到该节点的 IRI 路径，用 `\u0000` 连接，因为同一个类可以出现在多个父类下）、`iri`、`label`（`displayName`）、`deprecated`、`node`（原始 `EntityNode`，`select` 事件直接用它）、`children`；`hasChildren` 为真但尚未加载的节点放一个 `placeholder: true` 的子节点，让 `el-tree-v2` 显示展开箭头。
- `src/features/hierarchy/HierarchyTree.vue`：props `projectId`、`kind`、`selectedIri`；emit `select(node: EntityNode)`。
  - `el-tree-v2`，`height` 跟随容器（`ResizeObserver`），`item-size` 固定，`expand-on-click-node: false`、`highlight-current: true`；
  - 自定义节点插槽：显示名称；已弃用节点加 `is-deprecated` 样式（删除线 + 次要文字颜色，令牌来自 P4-03）；`title` 属性显示完整 IRI；占位节点显示加载动画；
  - `node-expand` 时调用 `expand`；点击节点 emit `select`；
  - `selectedIri` 变化且当前树中没有对应节点时调用 `locate`，然后 `setCurrentKey` 与 `scrollToNode`；
  - 空项目显示空状态；加载失败显示 `problemMessage` 和“重试”。
- `src/features/hierarchy/ClassHierarchyPanel.vue`：`HierarchyTree`（`kind="class"`），选中时调用 `useWorkspaceRoute().selectEntity(iri)`；在 `src/features/hierarchy/register.ts` 中注册为 `hierarchyPanels.class`。
- 文案：`src/features/hierarchy/locales/`（顶层键 `hierarchy`）。
- 测试：
  - `src/features/hierarchy/useHierarchyTree.spec.ts`：根节点渲染为占位子节点；`expand` 只请求一次；同一个类出现在两个父类下时 key 不同；`locate` 按路径依次展开；子节点查询失效并重新获取后，新增的子节点出现、删除的子节点消失。
  - `src/features/hierarchy/HierarchyTree.spec.ts`：已弃用节点带 `is-deprecated`；点击节点 emit `select`；带 `selectedIri` 挂载时调用 `getHierarchyPaths` 并高亮目标节点。
  - `src/features/hierarchy/HierarchyTree.large.spec.ts`：一个父节点下 10,000 个子节点；测试显式传入 `height=600`，并把 `ResizeObserver` 替换为桩（jsdom 没有布局，也没有 `ResizeObserver`）；展开后渲染到 DOM 的节点数在 1 到 100 之间（证明确实渲染了节点，且虚拟滚动生效）。不做耗时断言，避免在 CI 上不稳定。

## 实现要点
- `el-tree-v2` 没有 `lazy` / `load` 属性，所以用“占位子节点 + `node-expand` 事件 + 替换数据”的方式实现懒加载；替换时给 `treeData` 赋新数组（`shallowRef`），不要深度响应上万个节点。
- 树数据以 Vue Query 缓存为准：每个父节点的子节点是一条查询，`useHierarchyTree` 只是把这些查询拼成树，不另存一份服务端数据。
- `getHierarchyPaths` 可能返回多条路径（多重继承）；MVP 取第一条，不做路径选择界面。
- 显示名称由后端按项目的显示语言设置计算（BS-09），前端不做翻译或回退。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-06` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/hierarchy/useHierarchyTree.spec.ts src/features/hierarchy/HierarchyTree.spec.ts src/features/hierarchy/HierarchyTree.large.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 1 万个子节点展开后 DOM 节点数不超过 100
- [ ] 执行记录已填写

## 不在本卡范围
- 三种属性层级（P4-07）、个体列表（P4-08）
- 右键菜单与新建 / 删除（P4-11）
- 根据事件刷新树（P4-14，本卡只保证“查询失效 → 树更新”）
- 拖拽调整父类
