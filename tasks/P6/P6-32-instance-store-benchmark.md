---
id: P6-32
title: 实例库 PoC（二）：按诺和诺德仓库规模造数，测量 1 ~ 3 跳与深层追溯查询，按通过标准决定是否继续
phase: P6
module: wp-instances
type: impl
size: S
work_type: benchmark
ecc_extra: []
status: todo
depends_on: [P6-31]
---

# P6-32 实例库 PoC（二）：规模基准与通过标准

## 目标
用可重复的合成数据（天津诺和诺德仓库的量级）测量 P6-31 的实例库在 PostgreSQL 18 上的查询性能，写出报告，并按事先定好的通过标准给出结论：达标则继续 P6-33；不达标则阻塞本卡，由人决定是否评估 Apache AGE（ADR-013 第 4、5 节）。

## 参考
- ADR-013：约 1.8 万个货位、5 台堆垛机、7 ~ 8 台 AGV，在库托盘和每日任务在数万量级；深层追溯的代表查询 p95 超过 1 秒时评估 AGE 或另开 ADR
- P6-31：`InstanceStore`、`TraceQueries`、`V1__instances_schema.sql`
- P6-18：基准测法（写入后 `ANALYZE`、预热后采样、记录执行计划、返回行数、autovacuum 与 GC；冒烟模式），以及 `docs/perf/projection-poc-2026-11-14-root-cause.md` 的教训
- `backend/wp-app/src/test/java/wprebuild/app/projection/ProjectionBenchmark.java`：报告格式与 `-Dbench=true`、`-Dbench.smoke=true` 开关的写法

## 产出
- `backend/wp-instances/src/test/java/wprebuild/instances/bench/WarehouseCorpus.java`：固定随机种子的造数器，用 PostgreSQL `COPY` 写入。全量规模：
  - 货位 18,000（每 30 个货位一个温湿度传感器，共 600 个）、堆垛机 5、AGV 8；
  - 批次 20,000；托盘 60,000，每个托盘属于 1 个批次，在 30 天内平均移库 3 次（`storedAt` 关系按有效期产生历史版本）；
  - 任务：每天 30,000 个、共 30 天（90 万），每个任务关联 1 个托盘和 1 台设备（`executedBy`）；
  - 环境事件 100 万条：类 `EnvEvent` 的实例，事件时间写在 `valid_from`（事件不变，`valid_to` 为空），同时写入 `attributes.observedAt`；超温事件带 `attributes.excursion = true`（占 1%）；每条事件与 1 个传感器之间有 `observedBy` 关系，关系的 `valid_from` 也取事件时间；
  - 冒烟模式按 1/100 缩小，只验证管道。
- `backend/wp-instances/src/test/java/wprebuild/instances/bench/InstanceBenchmark.java`（没有 `-Dbench=true` 时跳过）：造数 → `ANALYZE` → 每条查询预热 20 次、采样 100 次 → 记录 p50 / p95 / p99 / max、返回行数、一份 `explain (analyze, buffers)` 计划、采样前后的 autovacuum 计数与 GC 次数。查询与门禁：
  - Q1（1 跳）：某货位当前存放的托盘；
  - Q2（2 跳）：某批次的全部托盘及其当前货位；
  - Q3（3 跳）：某台堆垛机某一天执行的任务所搬运托盘的批次；
  - Q4（深层追溯）：某批次 → 托盘 → 历史货位（按托盘在该货位的有效期）→ 该期间内监测该货位的传感器上的超温事件 → 传感器；
  - Q5（反向追溯）：某条超温事件 → 当时该货位上的托盘 → 批次；
  - W1（写入吞吐）：8 个并发写入线程，按 7:3 混合执行带版本关闭的 `upsert`（托盘状态变化）与 `unlink` + `link`（托盘移库），持续 60 秒，记录吞吐与单次写入的 p95。
  - **通过标准**：Q1 ~ Q3 的 p95 ≤ 200 毫秒；Q4、Q5 的 p95 ≤ 1 秒（ADR-013 的阈值）；W1 持续吞吐 ≥ 200 次写入 / 秒且单次写入 p95 ≤ 50 毫秒（ADR-013 把运行期实例移出编辑器的理由是业务系统并发写入；这两个阈值是本规划的建议，人可以在开工前调整）；造数后的两次运行，各查询返回行数相同。
- 报告 `docs/perf/instances-poc-<日期>.md`：环境（CPU、内存、PostgreSQL 版本、JVM 参数）、各表行数与磁盘大小、每条查询的 SQL、统计值、行数、执行计划摘要、后台活动，以及「门禁结论」一节（逐条写明是否达标）。

## 实现要点
- **本卡只测量，不做任何优化**（`benchmark` 类卡片的约定）：不改 `src/main` 的表结构、索引与查询；不调 PostgreSQL 参数。不达标时照实写报告，运行 `python tools/task.py block P6-32 --reason "…"`，由人在“加索引或物化路径”“评估 Apache AGE（新开卡，ADR-013 第 4 节）”“调整门禁”之间选择。
- Q1 ~ Q3 通过 P6-31 的 `TraceQueries` 执行，W1 通过 `InstanceStore` 执行；Q4、Q5 需要按时间区间连接，`TraceQueries` 的单时间点接口表达不了，用基准代码中命名的 SQL 常量（`Q4_DEEP_TRACE_SQL`、`Q5_REVERSE_TRACE_SQL`）执行，SQL 全文写进报告；是否提升为正式查询由 P6-34 决定。
- 采样参数（起点 IRI）用固定种子从造数结果中抽取，并在报告中列出，便于复现。
- 造数可能需要几分钟；JVM 用 `-Xmx6g`。冒烟模式应在 2 分钟内完成。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-32` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl :wp-instances -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InstanceBenchmark -Dbench=true -DargLine=-Xmx6g
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 报告的「门禁结论」全部达标（不达标时本卡阻塞，不勾选）
- [ ] 执行记录已填写

## 不在本卡范围
- 任何优化：索引、物化路径、闭包表、AGE
- 本体校验（P6-33）
- 服务化之后经网络接口的端到端写入基准（见 P6-34）
