---
id: P1-17
title: 类层级视图（BS-08）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-09]
---

# P1-17 类层级视图（BS-08）

## 目标
内存中维护类层级，支持增量更新，并能报告每次写入引起的父子关系变化。

## 参考
- BS-08
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/hierarchy/ClassHierarchyProviderImpl.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/hierarchy/HierarchyChangeComputer.java`
- 旧测试：`../webprotege/webprotege-server-core/src/test/java/edu/stanford/bmir/protege/web/server/hierarchy/ClassHierarchyProviderImpl_TestCase.java`

## 产出
- `wprebuild.kernel.hierarchy.HierarchyEdge<E>`：`record HierarchyEdge<E>(E parent, E child)`；`HierarchyDelta<E>`：`record HierarchyDelta<E>(Set<HierarchyEdge<E>> added, Set<HierarchyEdge<E>> removed)`。
- `wprebuild.kernel.hierarchy.HierarchyView<E extends OWLEntity> extends DerivedView`：`Set<E> roots()`；`Set<E> children(E)`；`Set<E> parents(E)`；`boolean hasChildren(E)`；`List<List<E>> pathsToRoot(E)`；`HierarchyDelta<E> lastDelta()`（最近一次 `onApplied` 引起的变化；`rebuild` 后为空）。
- `wprebuild.kernel.hierarchy.ClassHierarchyView implements HierarchyView<OWLClass>`：语义按 BS-08；增量更新只重新计算受变更影响的类。
- 测试 `BS08_ClassHierarchyViewTest`：移植旧测试；`EquivalentClasses` 与交集的情况；没有父类的类挂在 `owl:Thing` 下；多父类时 `pathsToRoot` 返回多条路径；`lastDelta` 正确。
- 属性测试 `BS08_ClassHierarchyIncrementalPropertyTest`：随机变更序列（增删 `SubClassOf`、`EquivalentClasses`、声明）后，增量结果与 `rebuild` 结果相同。

## 实现要点
- `pathsToRoot` 遇到环时（本体里可能出现 A ⊑ B ⊑ A）不能死循环，测试中要覆盖这种情况。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-17` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest='BS08_Class*'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 属性层级（P1-18）
- 推理机推导出的层级
