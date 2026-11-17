---
id: P6-23
title: 内核：合并实体生成器（BS-13），替换引用、标签降级为 skos:altLabel、弃用或删除源实体
phase: P6
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: done
depends_on: [P1-16]
---

# P6-23 内核：合并实体生成器（BS-13）

## 目标
内核多一个变更生成器：把一组源实体合并到目标实体，产出的变更与旧系统一致（BS-13），并补上新系统的输入校验。它和创建、删除、回滚一样通过 `ChangePipeline` 提交，应用层在 P6-24 接入。

## 参考
- BS-13（`docs/01-rea/behavior-specs.md`）
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/entity/MergeEntitiesChangeListGenerator.java`；测试 `../webprotege/webprotege-server-core/src/test/java/edu/stanford/bmir/protege/web/server/entity/MergeEntitiesChangeListGenerator_TestCase.java`（移植它的断言）
- 内核：`runtime/ChangeListGenerator`（`generate(ChangeContext)`、`describe`、`renameResult`）、`runtime/ChangeContext(UserId, ProjectOntologies)`、`ontology/ProjectOntologies`（`keys()`、`referencingAxioms`、`annotationAssertions(IRI)`、`axiomsOfType`、`containsEntity`）、`iri/IriReplacer`、`generator/DeleteEntitiesGenerator`、`generator/CreateEntitiesGenerator`（默认本体的选取方式）

## 产出
- `generator/MergedEntityTreatment.java`：`DELETE_MERGED_ENTITY`、`DEPRECATE_MERGED_ENTITY`。
- `generator/MergeEntitiesGenerator.java`：`implements ChangeListGenerator<OWLEntity>`；构造参数 `(Set<IRI> sources, IRI target, MergedEntityTreatment treatment, String description)`；实体类型从本体中解析：目标 IRI 必须恰好声明为一种实体类型 T，每个源 IRI 都必须声明为 T（同时还声明为其它类型的双关 IRI 也可以，IRI 改写按 IRI 进行）；`generate` 按 BS-13 的三步产生变更：
  1. 替换引用：对每个本体，找出源 IRI 出现在签名中、作为注解断言主语、或作为注解值（IRI）的全部公理，删除原公理、加入把源 IRI 改写为目标 IRI 之后的公理（同一本体中改写后与已有公理相同的，只保留一份）；
  2. 标签降级：只删除“改写后才出现在目标上”的标签断言——目标在该本体中原本就有完全相同的断言时保留它，只补上 `skos:altLabel`（新系统修正：旧系统此时会误删目标原有的标签）；
  3. 弃用或删除。弃用时除 BS-13 的两项外，还在默认本体中加回源实体的类型声明 `Declaration(T(源 IRI))`（新系统新增：否则源 IRI 不再出现在签名中，层级、搜索与 Frame 都找不到这个被弃用的实体）。
  结果为目标实体；`renameResult` 返回目标实体本身。
- `generator/MergeEntitiesException.java`（非受检异常，携带 `Reason`）：在产生任何变更之前抛出，按以下顺序检查，命中第一个即停止：`EMPTY_SOURCES` → `TARGET_IN_SOURCES` → `BUILT_IN_ENTITY`（源或目标是 OWL 内置实体，如 `owl:Thing`、`owl:topObjectProperty`，按 IRI 判断，不要求声明）→ `UNKNOWN_ENTITY`（源或目标在任何本体中都没有声明）→ `TYPE_MISMATCH`（目标声明为多种类型，或某个源没有声明为目标的类型）。
- `describe`：有说明时用说明；否则为 `Merged <n> entities into <目标 IRI 的短形式>`（内核不依赖显示名称视图，应用层可以传入带显示名的说明）。
- 测试 `generator/BS13_MergeEntitiesGeneratorTest.java`：
  - 移植旧测试的断言：源被目标替换；源的 `rdfs:label` 变为目标的 `skos:altLabel`；源的 `skos:prefLabel` 变为目标的 `skos:altLabel`；`DELETE_MERGED_ENTITY` 时没有 `owl:deprecated`；`DEPRECATE_MERGED_ENTITY` 时有；
  - 新增：语言标签、注解上的注解与公理上的注解都被保留；源出现在多个本体时每个本体各自改写；注解值为源 IRI 的断言也被改写；`DEPRECATE_MERGED_ENTITY` 时源保留原有注解断言且 `containsEntity(源)` 为 `true`；源与目标有完全相同的 `rdfs:label` 时目标原有的标签被保留；合并两个源实体；五种 `Reason` 各一例且没有任何变更，检查顺序符合上面的约定（例如源为未声明的 `owl:Thing` 时得到 `BUILT_IN_ENTITY` 而不是 `UNKNOWN_ENTITY`）；
  - 经 `ChangePipeline` 应用后：`containsEntity(源)` 在删除方式下为 `false`；类层级视图中源的子类挂到目标下（BS-08 视图随之更新）；回滚这个修订（BS-04）后本体恢复原样。

## 实现要点
- 公理改写可以复用 `iri/IriReplacer` 的改写逻辑（它已经在变更列表中替换 IRI），不要另写一套对象复制器；若需要扩展它（例如处理注解值中的 IRI），在本卡内一并加测试。
- 找“注解值为源 IRI 的断言”需要扫描注解断言（`axiomsOfType(ANNOTATION_ASSERTION)`），`ProjectOntologies` 没有这类索引。合并是低频操作，可以接受；在执行记录中写明 50 万公理规模（`testdata` 的合成本体，P0-06）下合并一个实体的耗时，不设门禁。
- 变更顺序要与 BS-13 一致：第 2 步删除的是第 1 步刚加入的“目标实体上的标签”，顺序颠倒会让删除无效（BS-02 会过滤掉）。
- 只依赖 OWL API 5 与内核已有的类型，不依赖 Spring、JDBC。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-23` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BS13_MergeEntitiesGeneratorTest,BS07_DeleteEntitiesGeneratorTest,BS04_RevertRevisionGeneratorTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写


## 不在本卡范围
- 命令、权限与 REST 接口（P6-24）；Web 端（P6-25）
- 讨论线程随合并转移（新系统暂无讨论功能，见 P6-44）
- 旧 IRI 到新 IRI 的重定向表（旧系统也没有）

## 执行记录
- 日期：2026-11-17
- 分支：feature/p6-23-merge-entities-generator
- 测试：2026-11-17T16:50:16+08:00 通过（3 项）
- 实际改动：新增 MergeEntitiesGenerator（BS-13）及 MergedEntityTreatment / MergeEntitiesException；IriReplacer 增加 replaceObject/replaceObjects；引用扫描对齐删除路径（含 domain/range 与公理注解中的 IRI）。约 50 万公理规模下合并一个实体的 generate 约 1.3s（不设门禁）。
- 偏差：无
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
