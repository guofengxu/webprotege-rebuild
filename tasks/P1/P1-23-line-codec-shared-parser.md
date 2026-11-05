---
id: P1-23
title: 逐行编解码共用解析上下文（修复 P1-20 逐行解码吞吐）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: []
status: done
depends_on: [P1-06]
---

# P1-23 逐行编解码共用解析上下文（修复 P1-20 逐行解码吞吐）

## 目标
`ofn-lines/1` 解码 10 万行的吞吐达到每秒 2 万行以上。编码时的回读校验也不再每条变更新建一个 `OWLOntologyManager`。行格式不变。

## 参考
- `docs/perf/kernel-benchmark-2026-11-05-root-cause.md` 第 3 节（根因与各方案实测吞吐）
- P1-06 卡片、ADR-011（`ofn-lines/1` 格式、行转义、匿名个体）
- `backend/wp-kernel/src/main/java/wprebuild/kernel/codec/OfnLines.java`：`decodeLine`、`parseDocument`、`manager`、`loader`、`renderChecked`、`oneAxiom`、`oneAnnotation`、`rejectImports`、`blockDocumentFetch`
- `backend/wp-kernel/src/main/java/wprebuild/kernel/codec/OfnLineChangeCodec.java`：`encode`、`decode`、`text`

## 产出
- 新增 `wprebuild.kernel.codec.OfnLineDecoder`（`public final`，**非线程安全**：一批行用一个实例，用完即弃）：
  - 构造器 `OfnLineDecoder(OWLDataFactory dataFactory)`：只在这里创建一次解析上下文。上下文包括一个 `OWLOntologyManager`，配置与现在的 `OfnLines.manager` 完全相同：只装函数式语法解析器，复制标准本体工厂，`withRemapAllAnonymousIndividualsIds(false)`，挂 `blockDocumentFetch` IRI mapper。另外还有一个暂存本体。
  - `OntologyChange decode(String line)`：输入、输出和异常与 `OfnLines.decodeLine(line, dataFactory)` 完全相同。
  - 包内可见的 `OWLOntology parse(String ofn)`：先清空暂存本体（移除全部公理、本体注解和导入声明），再把 `Ontology(<urn:wp:codec> …)` 包装文档用 `OWLFunctionalSyntaxOWLParser.parse(OWLOntologyDocumentSource, OWLOntology, OWLOntologyLoaderConfiguration)` 解析进暂存本体，检查返回的格式是 `FunctionalSyntaxDocumentFormat`，最后返回暂存本体。解析异常的包装方式与现在的 `parseDocument` 相同，都转成 `IllegalArgumentException`。
- 修改 `OfnLines`：
  - `parseDocument`、`manager` 移入 `OfnLineDecoder`，或者改为委托给它；`axiom`、`annotation`、`renderChecked` 改为使用调用方传入的 `OfnLineDecoder`。
  - `decodeLine(String, OWLDataFactory)` 签名和行为不变，实现为 `new OfnLineDecoder(dataFactory).decode(line)`。单行调用方（例如 P3-10）不受影响。
  - `encodeLine(OntologyChange)` 签名和行为不变。新增包内可见的 `encodeLine(OntologyChange, OfnLineDecoder checker)`，回读校验使用传入的 checker。
  - `oneAxiom`、`oneAnnotation`、`rejectImports`、`split`、`iriToken`、`ontology`、`importChange` 的校验规则不改。
- 修改 `OfnLineChangeCodec`：`decode` 每次调用创建一个 `OfnLineDecoder(dataFactory)`，所有行共用；`encode` 每次调用创建一个 `OfnLineDecoder(OWLManager.getOWLDataFactory())` 作为回读校验器（与现在 `renderChecked` 用的数据工厂相同），所有变更共用。
- 在 `OfnLineChangeCodecTest` 中新增测试：
  - `decoder_matches_decode_line_for_a_thousand_distinct_axioms`：用同一个 `OfnLineDecoder` 连续解码 1,000 条不同公理的行，每条结果都等于 `OfnLines.decodeLine` 的结果。这条用来验证暂存本体每次都清空了。
  - `decoder_rejects_a_bad_line_and_still_decodes_the_next`：同一个 decoder 先解码一条残缺的公理行，抛出 `IllegalArgumentException`；接着解码一条正常的行，结果正确。
  - `decoder_keeps_anonymous_individual_node_ids_across_lines`：同一个 decoder 解码两条都提到 `_:b1` 的行，两条结果里的匿名个体都等于 `DATA.getOWLAnonymousIndividual("b1")`。
  - `round_trips_fifty_thousand_lines_within_fifteen_seconds`：5 万条 `AddAxiom`，`SubClassOf(C_i C_(i/4))` 与 `AnnotationAssertion(rdfs:label C_i "c-i"@en)` 交替出现。先 `codec.encode` 再 `codec.decode`，结果相等。方法上加 `@Timeout(value = 15, unit = TimeUnit.SECONDS)`，用来防止退化：修复前编码加解码约 30～40 秒，修复后约 4 秒。

## 实现要点
- 首选方案 A：每行直接解析进复用的暂存本体，分析实测 27,265 行/秒。要在每行解析**之前**清空暂存本体，这样上一行解析失败留下的残余不会带到下一行。
- 如果方案 A 让匿名个体相关测试失败（已有的 `round_trips_an_axiom_that_mentions_anonymous_individual_b1`、`round_trips_two_distinct_anonymous_individuals`，以及本卡新增的跨行测试），改用方案 B：共用同一个 manager，每行先 `loadOntologyFromOntologyDocument`，取出结果后立即 `removeOntology`。方案 B 分析实测 19,416 行/秒，报告机器上约 2.5 万行/秒，余量较小。两个方案都不行时，执行 `block` 并附上测试输出。不要改行格式，也不要为此启用 `ofn-lines/2`。
- 不要把 manager 或 decoder 做成 static 共享对象：`OWLOntologyManagerImpl` 自带锁，暂存本体有状态，而一个 `OfnLineChangeCodec` 实例可能被多个线程同时使用。每次 `encode` / `decode` 调用各建一个。
- 公理行里混入 `Import(...)` 时，导入声明同样会进入暂存本体，由 `rejectImports` 拒绝。`blockDocumentFetch` mapper 和 loader 配置里的 `MissingImportHandlingStrategy.SILENT` 都要保留，防止解析器触发导入加载时访问网络。
- 黄金文件 `src/test/resources/codec/ofn-lines-v1-golden.txt` 不能改，编码输出要逐字节不变。
- 预计改动 100～150 行。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-23` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest=OfnLineChangeCodecTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- `ofn-lines/2` 或任何行格式变化（ADR-011）
- 快照编解码（P1-22）
- 迁移包导入是否改用 `OfnLineDecoder` 批量解码（由 P3-10 决定）
- 运行 `KernelBenchmark` 或更新 `docs/perf/` 下的报告（由 P1-20 重跑时统一测量）

## 执行记录
- 日期：2026-11-06
- 分支：feature/p1-23-line-codec-shared-parser
- 测试：2026-11-06T00:39:46+08:00 通过（3 项）
- 实际改动：新增 OfnLineDecoder，每次 encode 或 decode 共用一个解析上下文；每行解析前清空暂存本体。ofn-lines/1 行格式不变。
- 偏差：无
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
