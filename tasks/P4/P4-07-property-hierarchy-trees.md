---
id: P4-07
title: 对象 / 数据 / 注解属性层级树（复用类层级树组件）
phase: P4
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: done
depends_on: [P4-06]
---

# P4-07 属性层级树

## 目标
工作台左侧的“对象属性”“数据属性”“注解属性”三个标签页各显示对应的属性层级，行为与类层级树一致（懒加载、定位、已弃用样式）。

## 参考
- `tasks/P4/EPIC.md` 第 7 项
- BS-08（属性层级）、P3-13（`kind` 取值 `object-property`、`data-property`、`annotation-property`）
- `docs/api/openapi.json` 的 operationId：`getHierarchyRoots`、`getHierarchyChildren`、`getHierarchyPaths`
- P4-06 的 `HierarchyTree.vue`、`useHierarchyTree`

## 产出
- `src/features/hierarchy/PropertyHierarchyPanel.vue`：props `projectId`、`kind`（由 P4-05 的 `HierarchyPanel` 传入，三种属性层级之一），内部就是 `HierarchyTree`；选中时调用 `useWorkspaceRoute().selectEntity(iri)`。
- 在 `src/features/hierarchy/register.ts`（P4-06 创建）中注册 `hierarchyPanels` 的 `object-property`、`data-property`、`annotation-property` 三项，都指向 `PropertyHierarchyPanel`（`HierarchyPanel` 传入的 `kind` 区分三者）。
- 如果 P4-06 的 `HierarchyTree` 中有只适用于类的写法（例如写死了 `class`），在本卡改为由 `kind` 决定，并补充对应断言。
- 文案：需要的新文案放在 `src/features/hierarchy/locales/`；三种层级的名称 P4-05 的 `workspace` 文案中已有，不重复。
- 测试：
  - `src/features/hierarchy/PropertyHierarchyPanel.spec.ts`：三种 `kind` 各挂载一次，分别请求 `/hierarchies/object-property/roots` 等正确的路径；切换标签页后不复用上一种层级的缓存数据；选中属性后 URL 的 `entity` 更新，`kind` 保持不变。

## 实现要点
- 不复制 `HierarchyTree` 的代码；三种属性只是 `kind` 不同。
- 查询键中已经包含 `kind`，三棵树的缓存天然隔离。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-07` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/hierarchy/PropertyHierarchyPanel.spec.ts src/features/hierarchy/HierarchyTree.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 属性 Frame 编辑器（P4-10）
- 新建 / 删除属性（P4-11）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-07-property-hierarchy-trees
- 测试：2026-11-12T10:51:27+08:00 通过（3 项）
- 实际改动：新增 PropertyHierarchyPanel，注册三种属性层级到 hierarchyPanels；复用 HierarchyTree，按 kind 请求/缓存隔离；补充 PropertyHierarchyPanel 与 HierarchyTree 的 kind 路径断言。
- 偏差：无
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
