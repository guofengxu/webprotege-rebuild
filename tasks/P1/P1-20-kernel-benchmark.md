---
id: P1-20
title: 内核性能基准与门禁
phase: P1
module: wp-kernel
type: impl
size: S
work_type: benchmark
ecc_extra: []
status: todo
depends_on: [P1-11, P1-17, P1-19, P1-06, P1-22, P1-23, P1-24, P1-25]
---

# P1-20 内核性能基准与门禁

## 目标
用数据回答“OWL API 自带索引 + 逐行编码”是否够用。

## 参考
- ADR-005、ADR-011（两者都依赖本卡的测量结果）
- 首次运行：`docs/perf/kernel-benchmark-2026-11-05.md`；根因分析：`docs/perf/kernel-benchmark-2026-11-05-root-cause.md`
- 修复卡：P1-22（快照解码）、P1-23（逐行解码）、P1-24（层级视图）、P1-25（显示名称视图）

## 产出
- `backend/wp-kernel/src/test/java/wprebuild/kernel/bench/KernelBenchmark.java`：`@EnabledIfSystemProperty(named = "bench", matches = "true")`，默认构建不运行。
- 场景（分别用生成器生成约 10 万和约 50 万条公理的项目）：（a）快照编码与解码耗时；（b）从内存日志重放 1,000 个修订（每个 10 条变更）的吞吐；（c）通过 `ChangePipeline`（内存日志）连续 1,000 次单实体编辑的 p50 / p95 延迟，派生视图全部启用；（d）加载后的堆占用（调用 `System.gc()` 之后读取 `MemoryMXBean`）；（e）`OfnLineChangeCodec` 解码 10 万行的耗时。
- 运行结束输出 Markdown 表格到 `docs/perf/kernel-benchmark-<yyyy-MM-dd>.md`，包含机器配置（CPU、内存、JDK 版本、`-Xmx`）。

## 实现要点
- 初始门禁（50 万公理规模，可由人调整）：快照解码 ≤ 30 秒；加载后堆占用 ≤ 2.5 GB；单次编辑 p95 ≤ 100 毫秒；重放 ≥ 5,000 条变更 / 秒；逐行解码 ≥ 20,000 行 / 秒。
- 有任何一项不达标时：照样提交报告，把卡片状态改为 `blocked`，在「阻塞」中列出未达标项和数据，由人决定是否修订 ADR-005 / ADR-011。不要自行优化或改架构。

### 重跑（P1-22～P1-25 合并之后）
- 本卡已经按原产出跑过一次，结果见「首次运行记录」。重跑时 `KernelBenchmark` 的场景、规模和门禁**都不改**，只重新运行验收命令，生成新报告。
- 开工前，人需要先删除已合并的旧分支：`feature/p1-20-kernel-benchmark` 已经合并到 `main`（合并提交 `8c8b968`），但远程分支还在。不删的话，`task.py start P1-20` 会因为远程分支已存在而拒绝；加 `--resume` 又会切到旧分支，拿不到 P1-22～P1-25 的修复代码。要在人自己的终端运行的命令：`git push origin --delete feature/p1-20-kernel-benchmark`；如果本地也有这个分支，再运行 `git branch -d feature/p1-20-kernel-benchmark`。Agent 遇到 `start` 报错时，把报错原文告诉人，不要绕过。
- 尽量在首次运行的同一台机器上重跑（Intel Xeon E5-2673 v3 × 8，32 GB 内存，JDK 25，`-Xmx6g`），方便和首次结果对比。换了机器的话，在执行记录中写明。
- 新报告按运行日期命名（`kernel-benchmark-<yyyy-MM-dd>.md`）。首次报告和根因分析都保留，不删除、不改写。
- 在执行记录（`--summary`）中逐项写出首次运行与重跑的对比：解码、单次编辑 p50 / p95、重放、堆占用、逐行解码。
- 重跑后仍有不达标项：照旧把本卡标为 `blocked`，列出数据，不要自行优化。按 ADR-005，由人决定是否新开 ADR 自建索引。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-20` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest=KernelBenchmark -Dbench=true -DargLine=-Xmx6g
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] `docs/perf/` 下有本次报告
- [ ] 报告中每项都标注“达标 / 未达标”
- [ ] 执行记录中写有与首次运行（2026-11-05）的逐项对比
- [ ] 执行记录已填写

## 不在本卡范围
- 任何性能优化（修复已拆到 P1-22～P1-25）
- 修改基准场景、规模或门禁阈值（调整门禁由人决定）

## 首次运行记录（2026-11-05，当时标为 blocked）
- 日期：2026-11-05
- 原因：基准已写入 docs/perf/kernel-benchmark-2026-11-05.md（JDK 25.0.4.1，-Xmx6g，Intel Xeon E5-2673 v3 × 8，内存 32814464 kB）。约 50 万公理规模未达标：快照解码 1679.532 秒（门禁 ≤ 30 秒）；单次编辑 p95 1421.51 毫秒（门禁 ≤ 100 毫秒）；ofn-lines/1 解码 3203 行/秒（门禁 ≥ 20000 行/秒）。达标：加载后堆占用 0.23 GiB（≤ 2.5 GiB）；重放 137145 条变更/秒（≥ 5000）。约 10 万规模对照同样未过解码（40.443 秒）和编辑 p95（255.97 毫秒）。未改内核。请人决定是否修订 ADR-005 / ADR-011。
- 根因（2026-11-05 分析，详见 `docs/perf/kernel-benchmark-2026-11-05-root-cause.md`）：3 项未达标都是实现缺陷，不是 OWL API 索引的能力上限。（1）快照解码：`OfnZipSnapshotCodec.ignoreBracketIris` 对每个 IRI 调用一次 `addIgnoredImport`，总成本平方级；（2）单次编辑：4 个层级视图的 `onApplied` 每次物化整个项目签名，`DisplayNameView.onApplied` 每次全量重建；（3）逐行解码：`OfnLines` 每行新建一个 `OWLOntologyManager`。不挂视图时，50 万公理单次编辑 p95 为 0.25 毫秒。
- 处理：人已确认按根因分析拆出修复卡 P1-22～P1-25，全部合并后重跑本卡。暂不修订 ADR-005 / ADR-011，不为性能启用 `ofn-lines/2`。本卡状态改回 `todo`，并依赖这 4 张卡。
