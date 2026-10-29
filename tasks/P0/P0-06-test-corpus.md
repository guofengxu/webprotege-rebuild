---
id: P0-06
title: 测试本体语料与合成本体生成器
phase: P0
module: testdata, wp-kernel
type: impl
size: M
work_type: test-data
ecc_extra: []
status: todo
depends_on: [P0-02]
---

# P0-06 测试本体语料与合成本体生成器

## 目标
后续所有内核测试共用一套覆盖全部 OWL 2 公理类型的语料，以及一个可以按规模生成大本体的生成器。

## 参考
- BS-12
- `docs/02-architecture/adr/ADR-011-change-codec.md`（编码测试要用到全部公理类型）

## 产出
- `testdata/ontologies/axioms-all-types.ofn`：手写的函数式语法本体，`AxiomType.AXIOM_TYPES` 中的每种类型至少出现一次，包括：6 种实体声明；SubClassOf；带 `ObjectIntersectionOf` 的 EquivalentClasses；DisjointClasses；DisjointUnion；对象 / 数据 / 注解属性的子属性、等价、不相交、定义域、值域；属性链；7 种对象属性特征；函数型数据属性；类断言；正、负对象属性断言与数据属性断言；SameIndividual；DifferentIndividuals；HasKey；DatatypeDefinition；带中文和英文语言标签的注解断言；含匿名个体的断言；一条 SWRL 规则。
- `testdata/ontologies/labels-multilingual.ttl`：20 个类，`rdfs:label` 与 `skos:prefLabel` 各有 zh / en 两种语言，部分类只有其中一种。
- `testdata/ontologies/imports/`：`main.owl` 导入 `a.owl`、`b.owl`（RDF/XML，使用 `http://example.org/wp-test/...` 下的 IRI）。
- `testdata/ontologies/warehouse-sample.ttl`：约 30 个类的仓库领域小本体（托盘、货位、巷道、堆垛机、AGV 等），中英文标签。
- `testdata/ontologies/formats/`：把 `warehouse-sample` 分别保存为 `.owl`（RDF/XML）、`.ttl`、`.owx`、`.ofn`、`.omn` 五种格式。
- `testdata/README.md`：逐个文件说明用途与来源（全部为手写或由生成器生成，无第三方版权）。
- `wp-kernel/src/test/java/wprebuild/kernel/testsupport/TestOntologies.java`：`static Path dir()`（读取系统属性 `wp.testdata.dir`，默认 `../testdata/ontologies`）、`static OWLOntology load(String relativePath)`。
- `wp-kernel/src/test/java/wprebuild/kernel/testsupport/SyntheticOntologyGenerator.java`：参数为类数量、平均分支数、每类标签语言数、每类注解断言数、个体数、随机种子；同样的参数和种子生成完全相同的本体。
- `wp-kernel/src/test/java/wprebuild/kernel/testsupport/TestCorpusTest.java`：所有语料文件都能解析；`axioms-all-types.ofn` 覆盖全部公理类型（如有无法覆盖的类型，在测试里用常量列出并注明原因）；生成器在相同种子下两次生成的公理集合相等。

## 实现要点
- 配置 wp-kernel 的 surefire：`<systemPropertyVariables><wp.testdata.dir>${maven.multiModuleProjectDirectory}/testdata/ontologies</wp.testdata.dir></systemPropertyVariables>`。
- 生成 zip 的工作交给需要它的测试在运行时完成，不要提交二进制 zip 文件。

## ECC 技能
工作项类型：`test-data` —— 测试语料与测试支持代码。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-06` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=TestCorpusTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 性能基准（P1-20）
