---
id: P1-04
title: 内存本体读模型 ProjectOntologies（OWL API 5 实现）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-02]
---

# P1-04 内存本体读模型 ProjectOntologies（OWL API 5 实现）

## 目标
一个项目的所有本体都在内存中，可以按变更模型修改，并提供后续卡片需要的查询。

## 参考
- ADR-005
- BS-03

## 产出
- `wprebuild.kernel.ontology.OntologyInfo`：`record OntologyInfo(OntologyKey key, Optional<IRI> ontologyIri, Optional<IRI> versionIri)`。
- `wprebuild.kernel.ontology.ProjectOntologies`（只读接口）：`Set<OntologyKey> keys()`；`Optional<OntologyInfo> info(OntologyKey)`；`boolean containsAxiom(OntologyKey, OWLAxiom)`；`boolean containsAnnotation(OntologyKey, OWLAnnotation)`；`boolean containsImport(OntologyKey, IRI)`；`Stream<OWLAxiom> axioms(OntologyKey)`；`Set<OWLAnnotation> annotations(OntologyKey)`；`Set<IRI> imports(OntologyKey)`；`<T extends OWLAxiom> Stream<T> axiomsOfType(AxiomType<T>)`（跨全部本体）；`Stream<OWLAxiom> referencingAxioms(OWLEntity)`（跨全部本体）；`Stream<OWLAnnotationAssertionAxiom> annotationAssertions(IRI subject)`；`boolean containsEntity(OWLEntity)`；`Stream<OWLEntity> signature()`；`long axiomCount()`；`OWLDataFactory dataFactory()`。
- `wprebuild.kernel.ontology.MutableProjectOntologies extends ProjectOntologies`：`void apply(List<OntologyChange> changes)`，按顺序直接应用（不做有效性过滤）；`AddAxiom` 等引用未登记的 key 时抛 `IllegalStateException`；`DeclareOntology` 的 key 已存在时抛 `IllegalStateException`。
- `wprebuild.kernel.ontology.OwlApiProjectOntologies implements MutableProjectOntologies`：`static OwlApiProjectOntologies empty()`；内部用 `OWLManager.createConcurrentOWLOntologyManager()`，每个 key 对应一个 `OWLOntology`。
- 测试 `ProjectOntologiesTest`：登记两个本体，增删公理、注解、导入后逐个查询验证；用语料 `axioms-all-types.ofn` 的全部公理作为 `AddAxiom` 应用，`axiomCount()` 与源本体一致；未登记 key 抛异常。

## 实现要点
- **不得访问网络**：`AddImport` 只记录导入声明，不加载被导入的文档。为管理器设置 `MissingImportHandlingStrategy.SILENT`，并写一个测试：导入 `http://invalid.invalid/x`，断言不抛异常、耗时小于 1 秒。
- 匿名本体用 `manager.createOntology()` 创建，key 与 `OWLOntology` 之间的映射由本类维护，不依赖 `OWLOntologyID`。
- 查询方法优先使用 OWL API 自带的索引方法（如 `referencingAxioms`、`axioms(AxiomType)`、`annotationAssertionAxioms(IRI)`），不要自己建索引（ADR-005）。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-04` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=ProjectOntologiesTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 有效变更过滤（P1-05）
- 层级、显示名称（P1-17 ~ P1-19）
