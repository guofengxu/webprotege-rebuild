---
id: P1-26
title: 逐行编解码直接调用函数式语法产生式（修复 P1-20 逐行解码吞吐之二）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: []
status: done
depends_on: [P1-23]
---

# P1-26 逐行编解码直接调用函数式语法产生式（修复 P1-20 逐行解码吞吐之二）

## 目标
`ofn-lines/1` 每解析一行，不再新建解析器，也不再改动暂存本体。P1-20 在报告机器上重跑时，逐行解码达到每秒 2 万行以上。被拒绝的行，异常类型和消息与 P1-23 之后完全相同；行格式不变。

## 参考
- `docs/perf/kernel-benchmark-2026-11-06.md`：P1-23 合并后，逐行解码 12,184 行/秒，门禁 20,000 行/秒
- `docs/perf/kernel-benchmark-2026-11-05-root-cause.md` 第 3 节；P1-20「第二次运行记录」；P1-23 卡片
- ADR-011（行格式、匿名个体）；ADR-003（内核使用 OWL API 5.5.x，根 `pom.xml` 固定为 5.5.1）
- `backend/wp-kernel/src/main/java/wprebuild/kernel/codec/OfnLineDecoder.java`：构造器、`parse`、`clear`、`manager`
- `backend/wp-kernel/src/main/java/wprebuild/kernel/codec/OfnLines.java`：`axiom`、`annotation`、`renderChecked`、`oneAxiom`、`oneAnnotation`、`rejectImports`
- OWL API 5.5.1 的 `owlapi-parsers` jar 自带语法文件 `org/semanticweb/owlapi/functional/parser/OWLFunctionalSyntaxParser.jj`：`setUp`、`getIRI`、`parse()`、`Ontology()`、`Axiom()`、`Annotation()`、`AxiomAnnotationSet()`

## 产出
- 新增 `wprebuild.kernel.codec.OfnExpressionParser`（包内可见，`final`，**非线程安全**；由 `OfnLineDecoder` 持有，生命周期与它相同）：
  - 构造器 `OfnExpressionParser(OWLOntology context, OWLOntologyLoaderConfiguration loader)`：新建一个 `OWLFunctionalSyntaxParser`，只调用一次 `setUp(context, loader)`。解析器从 `context` 所属的 manager 取数据工厂和 `withRemapAllAnonymousIndividualsIds(false)` 配置。
  - `Optional<OWLAxiom> axiom(String ofn)`：用 `ReInit` 把输入换成 `ofn + " )"`（新建 `CustomTokenizer(new StringReader(..))`），调用产生式 `Axiom()`；之后要求下一个记号是 `CLOSEPAR`，再下一个是 `EOF`。满足时返回该公理。
  - `Optional<OWLAnnotation> annotation(String ofn)`：同上，产生式换成 `Annotation()`。
  - 两个方法在以下情况返回 `Optional.empty()`，交给慢路径裁决：产生式或记号读取抛出 `RuntimeException`；后面两个记号不是 `CLOSEPAR`、`EOF`。OWL API 的 `ParseException`、`TokenMgrException` 都是 `OWLParserException` 的包内子类，未定义前缀时抛的是 `OWLRuntimeException`。
  - 用到的 OWL API 成员在类初始化时用反射解析一次，存为 `static final` 字段，每行不再查找。包括包内可见的 `CustomTokenizer(Reader)`、`TokenManager`、`Token.kind`、`OWLFunctionalSyntaxParserConstants.CLOSEPAR` 和 `EOF`，以及公开的 `OWLFunctionalSyntaxParser(TokenManager)`、`ReInit(TokenManager)`、`getNextToken()`。解析失败说明 OWL API 内部结构变了，此时抛 `IllegalStateException`，消息写明依赖的是 OWL API 5.5.1 函数式语法解析器的内部类。**不要**静默退回慢路径。
- 修改 `OfnLineDecoder`：
  - 构造器建好暂存本体之后，用暂存本体和现有 `loader` 创建 `OfnExpressionParser`。
  - 新增包内可见方法 `OWLAxiom axiom(String ofn)` 和 `OWLAnnotation annotation(String ofn)`：先走快路径；快路径返回空时，走现有的 `parse(ofn)`，再分别交给 `OfnLines.oneAxiom` 或 `OfnLines.oneAnnotation`。这样所有被拒绝的行，异常和消息都由原来的路径产生。
  - `parse(String)`、`clear()`、`manager(..)`、`blockDocumentFetch` 不改。
- 修改 `OfnLines`：
  - `axiom`、`annotation`、`renderChecked` 改为调用 `decoder.axiom(ofn)` 或 `decoder.annotation(ofn)`。
  - `oneAxiom`、`oneAnnotation` 由 `private` 改为包内可见，校验规则不改。
  - 公开方法的签名和行为不变。
- 新增测试 `OfnExpressionParserTest`（同包）。测试自己建上下文本体：`OWLManager.createOWLOntologyManager()`，`getOntologyConfigurator().withRemapAllAnonymousIndividualsIds(false)`，`createOntology()`，loader 用默认的 `OWLOntologyLoaderConfiguration`。“慢路径结果”指 `OfnLines.oneAxiom(new OfnLineDecoder(DATA).parse(ofn), ofn)`，注解用 `oneAnnotation`。
  - `fast_path_parses_every_corpus_axiom_like_the_staging_parse`：对 `TestOntologies.load("axioms-all-types.ofn")` 的每条公理，用 `OfnLines.encodeLine(new AddAxiom(KEY, axiom))` 取出第 4 个字段 `ofn`。断言 `axiom(ofn)` 非空，并且等于慢路径结果。
  - `fast_path_parses_ontology_annotations_like_the_staging_parse`：普通注解、带语言标签的注解（`"人"@zh`）、注解上再带注解，各一条。断言 `annotation(ofn)` 非空，并且等于慢路径结果。
  - `fast_path_declines_every_expression_the_staging_parse_rejects`：对下列表达式，`axiom(..)` 都返回空，并且 `new OfnLineDecoder(DATA).decode("+\tAXIOM\to1\t" + ofn)` 抛 `IllegalArgumentException`：
    - `Hello`
    - `Annotation(<http://www.w3.org/2000/01/rdf-schema#comment> "hidden") SubClassOf(<http://example.org/A> <http://example.org/B>)`
    - `Import(<file:///tmp/wp-missing.ofn>)`
    - `Import(<file:///tmp/wp-missing.ofn>) SubClassOf(<http://example.org/A> <http://example.org/B>)`
    - 两条公理连写
    - 公理后面跟 `# comment`
    - 公理后面多一个 `)`
    - 残缺的 `SubClassOf(<http://example.org/A>`
    - 以 `Prefix(ex:=<http://example.org/>)` 开头的表达式

    另外，`annotation(..)` 对两条注解连写、对一条公理都返回空。
  - `fast_path_keeps_anonymous_individual_node_ids`：`ClassAssertion(<http://example.org/A> _:b1)` 和 `ObjectPropertyAssertion(<http://example.org/knows> _:b1 _:b2)`，快路径都非空，结果里的匿名个体分别等于 `DATA.getOWLAnonymousIndividual("b1")` 和 `DATA.getOWLAnonymousIndividual("b2")`。
  - `fast_path_recovers_after_a_declined_expression`：同一个实例先对 `SubClassOf(<http://example.org/A>` 返回空，接着解析一条正常公理，结果非空且正确。
- `OfnLineChangeCodecTest` 不改，全部通过。其中 `rejects_a_line_that_is_not_one_functional_syntax_expression` 对消息 `annotation` 的断言由回退路径保证。

## 实现要点
- **为什么只能用反射**（OWL API 5.5.1 实测）：`CustomTokenizer`、`TokenManager`、`Token`、`OWLFunctionalSyntaxParserConstants`、`ParseException` 都是包内可见。`OWLFunctionalSyntaxParser` 唯一的构造器和 `ReInit` 的参数类型都是 `TokenManager`。公开入口 `OWLFunctionalSyntaxOWLParser.parse` 和 `FunctionalSyntaxForAxiomsOnlyParser.parse` 都走 `parse()` → `Ontology(...)` 这条路：每行新建解析器、新建 `BufferedReader`（8K 字符），并对本体做 `SetOntologyID` 和 `AddAxiom` 两次变更。`FunctionalSyntaxForAxiomsOnlyParser` 会直接丢掉导入声明和本体注解，`rejectImports` 因此失效，也无法解析 `ANNOTATION` 行，**不要用**。
- **不要**把类放进 `org.semanticweb.owlapi.*` 包来绕开访问限制。那样会形成拆分包，也违反根包约定（`.cursor/rules/20-coding-conventions.mdc`）。反射只能出现在 `OfnExpressionParser` 一个类里。wp-kernel 没有 `module-info.java`，OWL API 在类路径（未命名模块）上，所以 `setAccessible` 不需要 `--add-opens`。在类注释里用一句话写明这个前提：以后改成 JPMS 模块或升级 OWL API 时，需要重新评估。
- 可以用 `Method` / `Constructor`，也可以用 `MethodHandle`。用反射调用时，`InvocationTargetException` 要取出原因，再按“返回空”处理；其他 `ReflectiveOperationException` 按内部 API 变化处理，抛 `IllegalStateException`。
- **哨兵 ` )`**：它对应现有包装文档 `Ontology(<urn:wp:codec> … )` 末尾的右括号。行尾带 `#` 注释时，注释会吞掉哨兵，快路径返回空，交给慢路径拒绝，与现在的行为一致。如果去掉哨兵、只检查 `EOF`，带注释的行会被接受（探针已验证）。
- **解析器状态跨行不串**：
  - `setUp` 只调用一次。
  - `AxiomAnnotationSet()` 每条公理都新建集合；`currentAnnotations` 不会被产生式写入。
  - `ReInit` 会清掉上一行失败时留下的记号状态。
  - `string2IRI` 缓存会随批次增长，上限是一批里不同 IRI 的数量。`OfnLineDecoder` 本来就是每次 `encode` / `decode` 新建一个，可以接受，不要另加清理逻辑。
- **注解公理**：慢路径的 `addAxiom` 在 `isLoadAnnotationAxioms()` 为假时会丢弃注解公理。`loader` 用的是默认配置（为真），所以快慢两条路径结果一致。不要改 `loader`。
- **导入**：`Import(...)` 不是 `Axiom()` 能识别的产生式，所以快路径不会触发 `makeLoadImportRequest`。含导入的行仍由慢路径处理，`blockDocumentFetch` mapper 和 `SILENT` 策略都保留。
- 回退时不打日志：坏数据会刷屏，而异常仍由慢路径照常抛出。
- **探针数据**：2026-11-06 在开发机（Windows，JDK 11）上，用临时程序（未入库）测 10 万行。每种做法单独跑一个 JVM，取 5 轮中位数。开发机波动较大，表中给出多次运行的范围。

  | 做法 | 类声明 | SubClassOf / 标签交替 | 每行分配 |
  |---|---|---|---|
  | 现有实现（P1-23） | 1.90～2.17 万行/秒 | 1.76 万行/秒 | 约 31 KB |
  | 不选的做法：暂存本体换成只记录、不建索引的子类（只用公开 API） | 2.68 万行/秒 | 1.95 万行/秒 | 约 24 KB |
  | 本卡做法（含哨兵） | 3.95～6.47 万行/秒 | 5.22 万行/秒 | 约 3.1 KB |

  - 2,003 条有效行（含匿名个体、带注解的公理）三种做法结果相同；上面“被拒绝的表达式”清单也全部被拒绝。
  - 按最保守的 2 倍加速推算，报告机器约 2.4 万行/秒，余量不大。**是否达标以 P1-20 重跑为准**，本卡不做吞吐断言。
- 预计改动 120～160 行（不含测试）。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-26` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest='OfnExpressionParserTest,OfnLineChangeCodecTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- `ofn-lines/2` 或任何行格式变化（ADR-011）。黄金文件 `src/test/resources/codec/ofn-lines-v1-golden.txt` 不改，编码输出逐字节不变。
- 快照编解码 `OfnZipSnapshotCodec`（它整份文档只解析一次，不受影响）
- 每次 `decode` 新建 `OfnLineDecoder` 的固定开销；跨调用复用或缓存 decoder（涉及线程安全）
- 迁移包导入改用批量解码（由 P3-10 决定）
- 新增计时断言，或修改 `round_trips_fifty_thousand_lines_within_fifteen_seconds`
- 运行 `KernelBenchmark` 或更新 `docs/perf/` 下的报告（由 P1-20 重跑时统一测量）
- 调整门禁阈值（由人决定）；升级 OWL API

## 执行记录
- 日期：2026-11-06
- 分支：feature/p1-26-line-codec-direct-parser
- 测试：2026-11-06T16:06:52+08:00 通过（3 项）
- 实际改动：新增 OfnExpressionParser：每个 encode/decode 复用一个函数式语法解析器，每行 ReInit 后直接调用 Axiom() 或 Annotation()，并用哨兵右括号确认没有多余记号。快路径返回空时仍走原来的暂存本体解析，拒绝类型和消息不变。
- 偏差：生产代码约 185 行（新类 151 行加上接入），高于卡片预估的 120–160 行，多出来的是一次性反射解析。未写吞吐断言，也未改行格式。
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
