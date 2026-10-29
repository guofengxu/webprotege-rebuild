---
id: P1-06
title: 变更编码 ofn-lines/1（ADR-011）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-02, P0-06]
---

# P1-06 变更编码 ofn-lines/1（ADR-011）

## 目标
一个修订的变更列表可以无损地编码为压缩文本，并解码回来。

## 参考
- ADR-011
- `docs/02-architecture/interchange-format.md` 第 4 节（迁移包复用同一行格式）

## 产出
- `wprebuild.kernel.codec.ChangeCodec`：`String id()`；`byte[] encode(List<OntologyChange>)`；`List<OntologyChange> decode(byte[])`。
- `wprebuild.kernel.codec.OfnLines`：`static String encodeLine(OntologyChange)`、`static OntologyChange decodeLine(String, OWLDataFactory)`，处理**规范形式**变更行 `<op>\t<kind>\t<ontologyKey>\t<ofn>`（按前三个制表符切分）；`static String escapeLine(String)`、`static String unescapeLine(String)` 实现 ADR-011 的行转义。迁移包导入（P3-10）会直接调用 `decodeLine`。
- `wprebuild.kernel.codec.OfnLineChangeCodec implements ChangeCodec`：`id()` 返回 `ofn-lines/1`；每条变更行经 `escapeLine` 后占一个物理行，UTF-8 编码后 gzip。
- 测试 `OfnLineChangeCodecTest`：语料中每条公理分别作为 `AddAxiom`、`RemoveAxiom` 往返编解码后结构相等；本体注解、导入、命名 / 带版本 / 匿名三种 `DeclareOntology` 往返；含换行、制表符、双引号、反斜杠、中文的字面量往返。
- 黄金文件测试：固定的 10 条变更编码后的**未压缩**文本，与 `src/test/resources/codec/ofn-lines-v1-golden.txt` 逐字节相同，防止格式被无意修改。

## 实现要点
- 渲染：使用 OWL API 5 的函数式语法对象渲染器，不设置前缀，保证输出完整 IRI。渲染结果中如果出现字面量之外的换行（例如带注解的公理被格式化成多行），把这些换行替换为空格；字面量内部的换行保持原样，交给行转义处理。为此写一个专门的测试。
- 解析：把一行公理包装成 `Ontology(<urn:wp:codec> <公理>)` 文档，交给函数式语法解析器解析到一个临时本体中，再取出那一条公理。第一版逐行解析即可，性能由 P1-20 测量。
- **匿名个体**：写一个测试，确认含 `_:b1` 这类匿名个体的公理往返后是否仍然相等。如果不能保证相等，按 ADR-011 的说明把卡片标为 `blocked`，附上测试结果，由人决定是否启用 `ofn-lines/2`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-06` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=OfnLineChangeCodecTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 快照编码（P1-07）
- 批量解析优化
