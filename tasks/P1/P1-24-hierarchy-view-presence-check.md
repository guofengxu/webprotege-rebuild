---
id: P1-24
title: 层级视图增量更新不再物化全项目签名（修复 P1-20 编辑延迟之一）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: []
status: done
depends_on: [P1-18]
---

# P1-24 层级视图增量更新不再物化全项目签名（修复 P1-20 编辑延迟之一）

## 目标
4 个层级视图的 `onApplied` 开销只与本次变更涉及的实体数有关，与项目规模无关。

## 参考
- `docs/perf/kernel-benchmark-2026-11-05-root-cause.md` 第 2 节（各视图的编辑延迟实测）
- BS-08；P1-17、P1-18 卡片
- `backend/wp-kernel/src/main/java/wprebuild/kernel/hierarchy/AbstractHierarchyView.java`：`onApplied`、`entitiesIn`、`affectedEntities`
- `backend/wp-kernel/src/main/java/wprebuild/kernel/ontology/ProjectOntologies.java`：`containsEntity`、`signature`；实现见 `OwlApiProjectOntologies`

## 产出
- 修改 `wprebuild.kernel.hierarchy.AbstractHierarchyView.onApplied`：删除 `Set<E> present = entitiesIn(after);`，对 `affectedEntities(effectiveChanges)` 中的每个实体改用 `after.containsEntity(entity)` 判断是否还在项目中。`rebuild` 仍用 `entitiesIn`，不改。
- 新增测试支撑 `wprebuild.kernel.testsupport.CountingProjectOntologies implements ProjectOntologies`（放在 `src/test/java`）：包装一个 `ProjectOntologies`，所有方法都原样委托；统计 `signature()` 和 `axiomsOfType(..)` 的调用次数，提供 `int signatureCalls()`、`int axiomsOfTypeCalls()`、`void reset()`。P1-25 会复用它。
- 测试 `BS08_HierarchyViewIncrementalCostTest`，对 `ClassHierarchyView`、`ObjectPropertyHierarchyView`、`DataPropertyHierarchyView`、`AnnotationPropertyHierarchyView` 各测一次：
  - 先在一个小本体上 `rebuild`；
  - 再用 `CountingProjectOntologies` 包装变更后的本体，执行一次只含单条公理的 `onApplied`（分别覆盖新增父子关系、删除最后一条引用使实体离开签名两种情况）；
  - 断言 `signatureCalls() == 0`，并且视图的 `roots`、`children` 与新建视图 `rebuild` 后的结果相同。

## 实现要点
- 改动只有几行。能证明它没有改变语义的是已有的属性测试：`BS08_ClassHierarchyIncrementalPropertyTest`（`step_by_step_updates_match_rebuild`、`one_batch_matches_rebuild`）和 `BS08_PropertyHierarchyViewsTest`（`step_by_step_updates_match_rebuild`、`one_batch_of_random_edits_matches_rebuild`）。这两个测试里，`rebuild` 通过 `signature()` 枚举实体，所以它们本身就是 `containsEntity` 与 `signature()` 一致性的判定依据，必须全部通过。
- 动手前先读 `OwlApiProjectOntologies.containsEntity`（逐个本体调用 `containsEntityInSignature`）和 `signature`（`signature(Imports.EXCLUDED)`），确认两者都不包含导入闭包。如果属性测试发现两者对某类实体（例如内置实体、只出现在声明里的实体）判断不一致，不要改 `OwlApiProjectOntologies`，而是执行 `block` 并附上反例。
- 结构根（`owl:Thing`、`owl:topObjectProperty` 等）仍由 `isStructuralRoot` 跳过，逻辑不变。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-24` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest='BS08_*'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 显示名称视图（P1-25）
- 修改 `ProjectOntologies` 接口或 `OwlApiProjectOntologies`
- 为层级查询新建索引（ADR-005：只有 P1-20 重跑后仍不达标时才考虑）
- 运行 `KernelBenchmark` 或更新 `docs/perf/` 下的报告（由 P1-20 重跑时统一测量）

## 执行记录
- 日期：2026-11-06
- 分支：feature/p1-24-hierarchy-view-presence-check
- 测试：2026-11-06T07:15:56+08:00 通过（3 项）
- 实际改动：四个层级视图的 onApplied 改为对变更涉及的实体调用 containsEntity，不再物化全项目签名。新增 CountingProjectOntologies，以及覆盖新增父子关系和删除最后一条引用的 BS08 增量开销测试。
- 偏差：无
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
