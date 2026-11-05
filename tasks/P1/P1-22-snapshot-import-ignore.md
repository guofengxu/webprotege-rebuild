---
id: P1-22
title: 快照编解码只忽略导入声明的 IRI（修复 P1-20 快照解码超时）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-07]
---

# P1-22 快照编解码只忽略导入声明的 IRI（修复 P1-20 快照解码超时）

## 目标
`ofn-zip/1` 快照的编码和解码耗时随公理数线性增长，50 万公理的解码回到 30 秒门禁以内。

## 参考
- `docs/perf/kernel-benchmark-2026-11-05-root-cause.md` 第 1 节（根因与实测数据）
- `docs/perf/kernel-benchmark-2026-11-05.md`（首次基准报告）
- P1-07 卡片（快照格式；解码不得访问网络）、ADR-011
- `backend/wp-kernel/src/main/java/wprebuild/kernel/codec/OfnZipSnapshotCodec.java`：`parse`、`ignoreBracketIris`、`readingManager`、`requireLossless`

## 产出
- 修改 `wprebuild.kernel.codec.OfnZipSnapshotCodec`：
  - 删除 `ignoreBracketIris` 和常量 `BRACKET_IRI`。
  - 新增包内可见的 `static Set<IRI> importIris(byte[] document)`：只匹配函数式语法的导入声明 `Import(<iri>)`（`Import`、`(`、`<` 之间允许有空白），跳过空白 IRI，按首次出现的顺序去重，返回不可变集合。
  - `parse` 改为：从 `manager.getOntologyConfigurator().buildLoaderConfiguration()` 出发，对 `importIris(document)` 中的每个 IRI 调用一次 `addIgnoredImport`，再 `manager.setOntologyLoaderConfiguration(config)`。
  - `readingManager` 不改：IRI mapper（`urn:wp:snapshot:blocked-import`）、`MissingImportHandlingStrategy.SILENT`、超时和重试设置都保持原样。
- 在 `OfnZipSnapshotCodecTest` 中新增测试：
  - `import_iris_lists_only_import_declarations`：文档中有本体 IRI、版本 IRI、实体 IRI、内容为 `<http://example.org/in-literal>` 的字面量，以及两条导入声明（其中一条出现两次）。结果只包含这两个导入 IRI，按首次出现的顺序排列。
  - `round_trips_sixty_thousand_axioms_within_fifteen_seconds`：用 `SyntheticOntologyGenerator.Parameters(20_000, 4, 1, 0, 0, 20L)` 生成约 6 万条公理的本体，编码再解码后结构相等（复用本测试类的 `assertSameStructure`）。方法上加 `@Timeout(value = 15, unit = TimeUnit.SECONDS)`。它用来防止平方级退化再次出现：修复前本地约需 25 秒，修复后约 3 秒。

## 实现要点
- 为什么旧写法是平方级：OWL API 5.5.1 的 `OWLOntologyLoaderConfiguration` 不可变，`addIgnoredImport` 每次都复制整个已忽略集合。旧代码给文档里的**每个**尖括号 IRI 都调用一次，所以总成本是 O(n²)。
- 原注释仍然成立：导入声明只记录、不加载；`makeLoadImportRequest` 读的是加载配置，不是 configurator。所以导入 IRI 仍然要加进加载配置的忽略列表，只是其他 IRI 不再加进去。
- 正则在 UTF-8 解码后的整个文档上运行一次即可。字面量里恰好出现 `Import(<…>)` 文本时，只会多忽略一个 IRI，没有副作用，不需要为此写解析器。
- 编码时的回读校验 `requireLossless` 也走 `parse`，修好后编码同样会变快。不要删掉这个校验。
- 以下已有测试必须保持通过：`decodes_an_unresolved_import_without_loading_the_document`、`rejects_an_import_iri_that_functional_syntax_cannot_quote`、`round_trips_corpus_synthetic_and_anonymous_ontologies`。
- 如果发现去掉其他 IRI 的忽略后，解码会尝试访问网络或拖慢“不可解析导入”的测试，停下来按 `AGENTS.md` 第 6 节执行 `block`，不要恢复对所有 IRI 的忽略。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-22` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest=OfnZipSnapshotCodecTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 逐行编码 `ofn-lines/1` 的性能（P1-23）
- 快照格式的任何改动（仍是 `ofn-zip/1`，不新增格式版本）
- 运行 `KernelBenchmark` 或更新 `docs/perf/` 下的报告（由 P1-20 重跑时统一测量）
