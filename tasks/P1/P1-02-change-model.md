---
id: P1-02
title: 本体变更模型：OntologyKey 与 7 种 OntologyChange
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: done
depends_on: [P1-01, P0-06]
---

# P1-02 本体变更模型：OntologyKey 与 7 种 OntologyChange

## 目标
内核有一套与 OWL API 的 `OWLOntologyChange` 解耦、按本体 key 引用的不可变变更模型。

## 参考
- ADR-011（变更按本体 key 引用；ONTOLOGY 类型）
- 旧变更模型：`../webprotege/webprotege-server-api/src/main/java/edu/stanford/bmir/protege/web/server/change/OntologyChange.java` 及同目录 `AddAxiomChange.java` 等

## 产出
- `wprebuild.kernel.change.OntologyKey`：`record OntologyKey(String value)`，匹配 `o[1-9][0-9]*`；`static OntologyKey of(int n)` 生成 `o<n>`。
- `wprebuild.kernel.change.OntologyChange`：`sealed interface`，方法 `OntologyKey ontology()`、`OntologyChange inverse()`、`Set<OWLEntity> signature()`、`boolean isAxiomChange()`、`Optional<OWLAxiom> axiom()`。
- 7 个 `record` 实现：`DeclareOntology(OntologyKey ontology, Optional<IRI> ontologyIri, Optional<IRI> versionIri)`（有版本 IRI 时必须有本体 IRI）、`AddAxiom(OntologyKey, OWLAxiom)`、`RemoveAxiom(OntologyKey, OWLAxiom)`、`AddOntologyAnnotation(OntologyKey, OWLAnnotation)`、`RemoveOntologyAnnotation(OntologyKey, OWLAnnotation)`、`AddImport(OntologyKey, IRI)`、`RemoveImport(OntologyKey, IRI)`。
- `inverse()`：Add 与 Remove 互换；`DeclareOntology.inverse()` 抛 `UnsupportedOperationException`（BS-04 新增规则）。
- `signature()`：公理变更返回 `axiom.getSignature()`；本体注解变更返回注解的签名；导入和登记本体返回空集。
- 测试 `OntologyChangeTest`：对语料 `axioms-all-types.ofn` 中的每条公理，`AddAxiom` 的 `inverse().inverse()` 等于自身；签名与公理签名一致；`DeclareOntology` 的校验与 `inverse()` 异常。

## 实现要点
- 不要引入访问者模式，用 `switch` 模式匹配处理各类型（Java 25）。
- `record` 的 `equals` 依赖 OWL API 对象的结构相等，这正是需要的语义。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-02` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest=OntologyChangeTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 变更列表最小化（P1-03）
- 编码（P1-06）

## 执行记录
- 日期：2026-11-02
- 分支：feature/p1-02-change-model
- 测试：2026-11-02T15:12:53+08:00 通过（3 项）
- 实际改动：在 wp-kernel 增加 OntologyKey 与 7 种不可变 OntologyChange，以及 OntologyChangeTest。公理分量名为 owlAxiom，因为接口 axiom() 返回 Optional。
- 偏差：AddAxiom 与 RemoveAxiom 的公理分量名为 owlAxiom。接口方法 axiom() 必须返回 Optional，record 不能再用同名访问器返回 OWLAxiom。
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
