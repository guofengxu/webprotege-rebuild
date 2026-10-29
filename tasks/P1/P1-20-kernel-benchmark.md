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
depends_on: [P1-11, P1-17, P1-19, P1-06]
---

# P1-20 内核性能基准与门禁

## 目标
用数据回答“OWL API 自带索引 + 逐行编码”是否够用。

## 参考
- ADR-005、ADR-011（两者都依赖本卡的测量结果）

## 产出
- `wp-kernel/src/test/java/wprebuild/kernel/bench/KernelBenchmark.java`：`@EnabledIfSystemProperty(named = "bench", matches = "true")`，默认构建不运行。
- 场景（分别用生成器生成约 10 万和约 50 万条公理的项目）：（a）快照编码与解码耗时；（b）从内存日志重放 1,000 个修订（每个 10 条变更）的吞吐；（c）通过 `ChangePipeline`（内存日志）连续 1,000 次单实体编辑的 p50 / p95 延迟，派生视图全部启用；（d）加载后的堆占用（调用 `System.gc()` 之后读取 `MemoryMXBean`）；（e）`OfnLineChangeCodec` 解码 10 万行的耗时。
- 运行结束输出 Markdown 表格到 `docs/perf/kernel-benchmark-<yyyy-MM-dd>.md`，包含机器配置（CPU、内存、JDK 版本、`-Xmx`）。

## 实现要点
- 初始门禁（50 万公理规模，可由人调整）：快照解码 ≤ 30 秒；加载后堆占用 ≤ 2.5 GB；单次编辑 p95 ≤ 100 毫秒；重放 ≥ 5,000 条变更 / 秒；逐行解码 ≥ 20,000 行 / 秒。
- 有任何一项不达标时：照样提交报告，把卡片状态改为 `blocked`，在「阻塞」中列出未达标项和数据，由人决定是否修订 ADR-005 / ADR-011。不要自行优化或改架构。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-20` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=KernelBenchmark -Dbench=true -DargLine=-Xmx6g
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] `docs/perf/` 下有本次报告
- [ ] 报告中每项都标注“达标 / 未达标”
- [ ] 执行记录已填写

## 不在本卡范围
- 任何性能优化
