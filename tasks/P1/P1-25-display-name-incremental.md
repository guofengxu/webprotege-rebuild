---
id: P1-25
title: 显示名称视图增量更新（修复 P1-20 编辑延迟之二）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-19, P1-24]
---

# P1-25 显示名称视图增量更新（修复 P1-20 编辑延迟之二）

## 目标
`DisplayNameView.onApplied` 只重算本次变更涉及的 IRI。显示名称、`allNames` 和 `lastChanged` 的结果与现在的全量重建完全相同，开销与项目规模无关。

## 参考
- `docs/perf/kernel-benchmark-2026-11-05-root-cause.md` 第 2 节
- BS-09；P1-19 卡片
- `backend/wp-kernel/src/main/java/wprebuild/kernel/shortform/DisplayNameView.java`：`onApplied`、`install`、`collect`、`remember`、`inSettingOrder`、`index`、`diff`、`nameOf`、`resolve`
- `backend/wp-kernel/src/main/java/wprebuild/kernel/ontology/ProjectOntologies.java`：`annotationAssertions(IRI)`、`containsEntity`、`dataFactory`
- `backend/wp-kernel/src/main/java/wprebuild/kernel/change/OntologyChange.java`：`signature()`
- P1-24 新增的 `wprebuild.kernel.testsupport.CountingProjectOntologies`

## 产出
- 修改 `wprebuild.kernel.shortform.DisplayNameView`：
  - `rebuild` 仍然全量构建（`collect` + `index`），`lastChanged` 置空。
  - `onApplied(effectiveChanges, after)` 改为增量更新，步骤如下：
    1. 受影响 IRI 集合 = 每条 `AddAxiom` / `RemoveAxiom` 中、主语为 IRI 的 `OWLAnnotationAssertionAxiom` 的主语，加上每条变更 `change.signature()` 中各实体的 IRI。导入变更和 `DeclareOntology` 的 signature 为空；本体注解里的实体也算进去，多算没有坏处，不能漏算。
    2. 修改之前，对每个受影响 IRI 的 6 种 `EntityType`，用 `after.dataFactory().getOWLEntity(type, iri)` 构造实体，记下这些实体当前的 `nameOf` 结果。只记录变更前在 `names` 中、或变更后 `after.containsEntity(entity)` 为真的实体。
    3. 用 `after.annotationAssertions(iri)` 重新计算该 IRI 的 `Map<DisplayNameSetting, String>`，规则与 `remember` / `inSettingOrder` 相同：语言标签转小写后比较；同一设置有多个字面量时取字典序最小的；非 IRI 主语、非字面量的值跳过。结果为空就从 `annotations` 中移除这个 IRI。
    4. 对第 2 步的每个实体：`after.containsEntity(entity)` 为真时写入 `names[entity] = resolve(entity, annotations)`，否则从 `names` 中移除。
    5. `lastChanged` = 第 2 步记录的实体中，变更后 `nameOf` 结果与变更前不同的那些，存为不可变 Set。
  - `annotations` 和 `names` 内部改为可变的 `HashMap`。`allNames` 返回的值仍然是不可变 Map，`lastChanged()` 返回不可变 Set。
  - `onApplied` 不得调用 `ProjectOntologies.signature()` 或 `axiomsOfType(..)`。
  - 更新类注释，写明增量规则。
- 属性测试 `BS09_DisplayNameIncrementalPropertyTest`（jqwik，写法参照 `BS08_ClassHierarchyIncrementalPropertyTest`）：
  - 随机变更序列包括：增删 `rdfs:label` / `skos:prefLabel` 的 `AnnotationAssertion`（带或不带语言标签，多语言，同一设置多个字面量，主语可以是未声明的 IRI）；增删 `Declaration` 和 `SubClassOf`，让实体进出签名；同一 IRI 的类与个体双关（punning）；增删本体注解。
  - 视图设置用 `[(rdfs:label, zh), (rdfs:label, en), (skos:prefLabel, "")]`。
  - `step_by_step_updates_match_rebuild`：逐条 `onApplied` 后，对出现过的每个 IRI 的 6 种实体，`displayName` 和 `allNames` 都与一个新建并 `rebuild` 的视图相同；每一步的 `lastChanged` 等于另一种算法的结果：取变更前后两次签名的并集，用两个 `rebuild` 视图逐个实体比较 `displayName`，把不同的收集起来。
  - `one_batch_matches_rebuild`：整批一次 `onApplied`，同样的断言。
- 测试 `BS09_DisplayNameIncrementalCostTest`：在 `rebuild` 之后，用 `CountingProjectOntologies` 包装变更后的本体，执行一次修改单个标签的 `onApplied`，断言 `signatureCalls() == 0` 且 `axiomsOfTypeCalls() == 0`，并且 `lastChanged` 只包含这一个实体。

## 实现要点
- 现有的 `BS09_DisplayNameViewTest` 必须原样通过。它定义的语义（优先顺序、本地名回退、完整 IRI 回退、内置实体名、`lastChanged` 不含未改动的实体）一条都不变。
- 第 2～5 步与现在 `diff` 的定义等价，理由是：IRI 不受影响的实体，变更前后它的注解值和是否在签名中都没变，所以名称也不会变。属性测试用来验证这个推理，如果出现反例，说明受影响集合漏算了，要补全集合，不要退回全量重建。
- 内置实体（`owl:Thing` 等）仍然由 `BUILT_IN` 决定名称，不受注解影响。
- 视图只在写锁内被修改（与 `AbstractHierarchyView` 相同，它也用可变集合），不需要另外加锁，也不要为了“安全”每次都复制整个 Map。
- 预计改动 120～200 行（不含测试）。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-25` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest='BS09_*'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 层级视图（P1-24）
- 修改 `DisplayNameSetting`、BS-09 的语义或默认设置顺序
- 修改 `ProjectOntologies` 接口或 `OwlApiProjectOntologies`
- 运行 `KernelBenchmark` 或更新 `docs/perf/` 下的报告（由 P1-20 重跑时统一测量）
