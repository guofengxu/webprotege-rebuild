---
id: P6-26
title: 内核：上传本体与项目的合并计划（新版本差异 / 只追加两种方式，BS-14）及合并生成器
phase: P6
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-21]
---

# P6-26 内核：上传合并的计划与生成器（BS-14）

## 目标
内核可以对比“上传的一组本体”与“项目当前的本体”，按 BS-14 的两种方式算出合并计划（每个项目本体要新增、删除哪些公理与本体注解，哪些上传本体没有配对），并提供一个生成器把计划作为一个修订提交。应用层在 P6-50 用它做预览与应用；本卡同时给 `ChangePipeline` 加上“期望的修订号”前置条件，让预览之后的并发编辑在生成锁内被发现。

## 参考
- BS-14（`docs/01-rea/behavior-specs.md`）
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/merge/ModifiedProjectOntologiesCalculator.java`、`OntologyDiffCalculator.java`、`OntologyPatcher.java`；`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/merge_add/MergeOntologyCalculator.java`、`OntologyMergeAddPatcher.java`
- 内核：`ontology/ProjectOntologies`（`keys()`、`info(key)` → `OntologyInfo(key, ontologyIri, versionIri)`、`axioms(key)`、`annotations(key)`、`containsAxiom`）、`change` 包中的 `AddAxiom`、`RemoveAxiom`、`AddOntologyAnnotation`、`RemoveOntologyAnnotation`、`generator/InitialImportGenerator`（如何从 `OWLOntology` 取公理）

## 产出
- `generator/UploadMergeMode.java`：`NEW_VERSION`、`ADDITIONS`。
- `generator/UploadMergePlan.java`：`record UploadMergePlan(UploadMergeMode mode, List<OntologyPatch> patches, List<String> unmatchedUploads)`；`record OntologyPatch(OntologyKey key, List<OWLAxiom> addedAxioms, List<OWLAxiom> removedAxioms, List<OWLAnnotation> addedAnnotations, List<OWLAnnotation> removedAnnotations)`。列表按确定的顺序排列（OWL API 的自然顺序），没有任何变化的配对不出现在 `patches` 中。
- `generator/UploadMergePlanner.java`（纯函数）：
  - `static UploadMergePlan newVersion(ProjectOntologies project, List<OWLOntology> uploaded)`：按 BS-14 方式一配对（本体 IRI 相同；双方都恰好一个本体时直接配对），计算公理与本体注解的完整差异，导入声明不参与；
  - `static UploadMergePlan additions(ProjectOntologies project, List<OWLOntology> uploaded, OntologyKey target)`：把全部上传本体的公理与本体注解中目标本体还没有的部分作为新增，不产生删除；`target` 不存在时抛出 `IllegalArgumentException`。
- `generator/UploadMergeGenerator.java`：`implements ChangeListGenerator<UploadMergePlan>`；构造参数 `(UploadMergeMode mode, List<OWLOntology> uploaded, Optional<OntologyKey> target, String description)`；在 `generate` 中基于 `ChangeContext` 里的**当前**本体重新调用 `UploadMergePlanner`，按计划产生变更（每个本体先删除、后新增），结果为该计划；`describe`：有说明时用说明，否则 `NEW_VERSION` 为 `Merged uploaded version`，`ADDITIONS` 为 `Merged uploaded ontologies into <目标本体 IRI 或 key>`。
- `runtime/ChangePipeline.java`：新增重载 `<R> ChangeApplicationResult<R> apply(UserId user, ChangeListGenerator<R> generator, RevisionNumber expectedHead)`：取得生成锁之后、调用生成器之前比较当前 head，不相等时抛出 `runtime/StaleHeadException`（携带期望值与实际值），不产生任何修订；原有的两参数版本行为不变。
- 测试 `runtime/ChangePipelineExpectedHeadTest.java`：head 相等时正常应用；不相等时抛出 `StaleHeadException` 且没有新修订；两参数版本不受影响。
- 测试 `generator/BS14_UploadMergeTest.java`：
  - 方式一：同 IRI 配对后的新增与删除；本体注解的新增与删除；导入声明的差异被忽略；双方各一个匿名本体时直接配对；项目两个本体、上传三个本体时只配对 IRI 相同的，其余列入 `unmatchedUploads`；完全相同时 `patches` 为空；
  - 方式二：只有新增，没有删除；已存在的公理不出现在新增中；目标本体不存在时抛异常；
  - 经 `ChangePipeline` 应用后，项目本体与上传版本一致（方式一），或包含上传版本的全部公理（方式二）；回滚（BS-04）后恢复原样；
  - 计划顺序稳定：同样的输入两次计算结果相同。

## 实现要点
- 生成器在生成锁内重新计算计划，而不是使用预览时的计划；预览之后项目是否被改过，由 `expectedHead` 重载在同一把生成锁内判断（P6-50 据此返回 409）。不能在调用 `apply` 之前单独检查：两步之间提交的编辑会被“新版本差异”悄悄删除。
- 公理比较使用 OWL API 的相等性（公理上的注解不同就是不同的公理），与旧系统一致。
- 只依赖 OWL API 5 与内核已有类型；不读文件，上传文件的解析由应用层完成。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-26` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BS14_UploadMergeTest,ChangePipelineExpectedHeadTest,BS03_InitialImportGeneratorTest,BS04_RevertRevisionGeneratorTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 上传暂存与本体列表（P6-27）、预览与应用接口（P6-50）；Web 端（P6-28）
- 追加到新本体（BS-14：不移植）
- 导入声明的合并
