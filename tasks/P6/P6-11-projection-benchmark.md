---
id: P6-11
title: 本体投影表 PoC（三）：50 万公理规模的重建与查询基准，按通过标准决定是否继续
phase: P6
module: wp-app
type: impl
size: S
work_type: benchmark
ecc_extra: []
status: blocked
depends_on: [P6-10]
---

# P6-11 投影表 PoC（三）：基准与通过标准

## 目标
在 P1-20 的 50 万公理规模上测量投影表的全量重建耗时与三条典型 SQL 查询的延迟，并按下面的通过标准给出结论。达标才继续做增量更新（P6-12）；不达标时阻塞，由人决定。

## 参考
- P1-20：`docs/perf/kernel-benchmark-2026-11-06.md`（机器配置与 10 万 / 50 万规模）；`backend/wp-kernel/src/test/java/wprebuild/kernel/bench/KernelBenchmark.java`（语料形状：类树分支因子 4、每个类一个标签；生成器是包内私有的，不能直接复用；验收命令用 `-Dbench=true -DargLine=-Xmx6g`）
- P6-09：`ProjectionWriter`；P6-10：`ProjectionRows.compute`、`ProjectionRebuilder`
- 内核的内存 `RevisionLog` / `SnapshotStore`（P1-08）与 `ProjectRuntimeLoader`（P1-09）

## 产出
- `projection/ProjectionCorpus.java`（测试代码）：按 KernelBenchmark 的形状生成约 10 万与约 50 万条公理的项目；另加 1,000 个对象属性与 1,000 个数据属性，各带定义域与值域。用内核的内存 `RevisionLog` / `SnapshotStore` 与 `ProjectRuntimeLoader` 直接构造 `ProjectRuntime`，不经过应用的上传导入。
- `projection/ProjectionBenchmark.java`（测试代码，`@EnabledIfSystemProperty(named = "bench", matches = "true")`，默认构建不运行）。对两种规模分别测量：
  - （a）`ProjectionRows.compute` 耗时；（b）`ProjectionWriter.replaceProject` 写入 Testcontainers 中 PostgreSQL 18 的耗时；（c）各表行数；
  - （d）以下三条查询各执行 100 次的 p50 / p95：Q1 用递归 CTE 查询一个深度为 3 的类的全部后代；Q2 按标签前缀查询 `lower(value) like 'xxx%'` 并 `limit 20`；Q3 查询某个属性的定义域与值域；
  - （e）连续重建两次，比较各表按主键排序后的 `md5(string_agg(...))`。
- 输出 `docs/perf/projection-poc-<yyyy-MM-dd>.md`：结果表格，以及机器配置（CPU、内存、JDK、`-Xmx`、PostgreSQL 版本），格式参照 P1-20 的报告。
- **通过标准**（初始值，人可以调整）：50 万规模下，全量重建（a + b）≤ 60 秒；Q1、Q2、Q3 的 p95 都 ≤ 200 毫秒；（e）两次结果相同。10 万规模只记录，不作门禁。任何一项不达标时，测试在写出报告之后失败，并在失败信息中列出未达标项。

## 实现要点
- **不要在本卡里做任何优化**（`benchmark` 类卡片的约定）。未达标时：先提交报告，再运行 `task.py block`，附上测量结果，由人决定调整标准、改设计（例如改用 PostgreSQL 驱动的 `CopyManager` 批量写入），还是放弃投影表（同 P1-20 的做法）。
- 本卡只有测试代码与报告，不改 `src/main`。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-11` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectionBenchmark -Dbench=true -DargLine=-Xmx6g
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] `docs/perf/projection-poc-<日期>.md` 已提交，并且全部达标；或者已经按「实现要点」标记阻塞
- [ ] 执行记录已填写

## 不在本卡范围
- 任何性能优化
- 增量更新的延迟测量（P6-12 以功能测试覆盖）

## 阻塞
- 日期：2026-11-14
- 原因：投影表 PoC 基准已写入 docs/perf/projection-poc-2026-11-14.md（JDK 25.0.5.0.3，-Xmx6g，Intel Xeon E5-2673 v3 × 8，内存 32814464 kB，PostgreSQL 18.6 Testcontainers）。约 50 万公理（506,000，含 1000 对象属性 + 1000 数据属性）门禁：全量重建 (a+b) 28.363 秒（≤ 60 秒）达标；Q2 p95 2.21 毫秒达标；Q3 p95 0.81 毫秒达标；两次重建 md5 一致达标。未达标：Q1（深度为 3 的类的递归 CTE 全部后代）p95 1550.33 毫秒（门禁 ≤ 200 毫秒）；同规模 Q1 p50 约 135 毫秒，p50→p95 约 11 倍长尾。约 10 万规模对照 Q1 p95 约 47 毫秒（无门禁）。未改 src/main、未做优化。performance-optimizer 仅分析报告：重建/Q2/Q3/摘要验证了 PoC，Q1 层次闭包在该语料下结构性偏慢。请人决定：上调 Q1 门禁、带 Q1 保留项继续 P6-12、另开卡做层次查询/schema 改造，或放弃投影表。
