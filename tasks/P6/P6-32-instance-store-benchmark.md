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
depends_on: [P6-31, P6-53, P6-54]
---

# P6-32 实例库 PoC（二）：规模基准与通过标准

## 目标
用可重复的合成数据（天津诺和诺德仓库的量级）测量 P6-31 的实例库在 PostgreSQL 18 上的查询性能，写出报告，并按事先定好的通过标准给出结论：达标则继续 P6-33；不达标则阻塞本卡，由人决定是否评估 Apache AGE（ADR-013 第 4、5 节）。

## 参考
- ADR-013：约 1.8 万个货位、5 台堆垛机、7 ~ 8 台 AGV，在库托盘和每日任务在数万量级；深层追溯的代表查询 p95 超过 1 秒时评估 AGE 或另开 ADR
- P6-31：`InstanceStore`、`TraceQueries`、`V1__instances_schema.sql`
- P6-18：基准测法（写入后 `ANALYZE`、预热后采样、记录执行计划、返回行数、autovacuum 与 GC；冒烟模式），以及 `docs/perf/projection-poc-2026-11-14-root-cause.md` 的教训
- `backend/wp-app/src/test/java/wprebuild/app/projection/ProjectionBenchmark.java`：报告格式与 `-Dbench=true`、`-Dbench.smoke=true` 开关的写法
- 首次运行：`docs/perf/instances-poc-2026-11-20.md`；根因分析：`docs/perf/instances-poc-2026-11-20-root-cause.md`
- 修复卡：P6-53（`TraceQueries.follow` 每一跳一条 SQL）、P6-54（基准测法修订：连接池、连接开销基线、W1 失败次数）

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

### 重跑（P6-53、P6-54 合并之后）
- 本卡已经按原产出跑过一次，结果见「首次运行记录」。重跑时语料、规模、五条查询、W1 的参数和门禁阈值**都不改**。测法以 P6-54 修订后的 `InstanceBenchmark` 为准（查询和写入经连接池执行，采样前连接池已填满）；Q2、Q3 走 P6-53 修改后的 `TraceQueries.follow`。重跑只运行验收命令、生成新报告，**不再改基准代码**；发现基准代码有问题时运行 `task.py block`。
- 开工前，人需要先删除已合并的旧分支：`feature/p6-32-instance-store-benchmark` 已经合并到 `main`（合并提交 `c6d39fc`），但远程分支还在。不删的话，`task.py start P6-32` 会因为远程分支已存在而拒绝；加 `--resume` 又会切到旧分支，拿不到 P6-53、P6-54 的代码。要在人自己的终端运行的命令：`git push origin --delete feature/p6-32-instance-store-benchmark`；如果本地也有这个分支，再运行 `git branch -d feature/p6-32-instance-store-benchmark`。Agent 遇到 `start` 报错时，把报错原文告诉人，不要绕过。
- 尽量在首次运行的同一台机器上重跑（Intel Xeon E5-2673 v3 × 8，32 GB 内存，JDK 25，`-Xmx6g`，Testcontainers 中的 PostgreSQL 18），方便和首次结果对比。换了机器的话，在执行记录中写明。
- 新报告按运行日期命名；与已有报告同名时，`nextReportPath` 会自动加 `-2` 后缀。首次报告和根因分析都保留，不删除、不改写。
- 在执行记录（`--summary`）中逐项写出首次运行与重跑的对比：Q1 ~ Q5 的 p50 / p95 与返回行数、W1 的吞吐、p95 与失败次数，以及报告中「连接开销基线」两行的 p50。
- 重跑后仍有不达标项：照旧把本卡标为 `blocked`，列出数据和相关执行计划，不要自行优化。由人按根因分析最后一节选择下一步。

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
- [ ] 执行记录中写有与首次运行（2026-11-20）的逐项对比
- [ ] 执行记录已填写

## 不在本卡范围
- 任何优化：索引、物化路径、闭包表、AGE（`follow` 的逐节点查询已拆到 P6-53）
- 修改基准代码、语料、规模、查询或门禁阈值（测法修订已拆到 P6-54；调整门禁由人决定）
- 本体校验（P6-33）
- 服务化之后经网络接口的端到端写入基准（见 P6-34）

## 首次运行记录（2026-11-20，当时标为 blocked）
- 日期：2026-11-21
- 原因（原文）：Q3 p95≈94s ≫ 200ms（TraceQueries.follow N+1）；其余查询达标；见 docs/perf/…
- 更正（2026-11-21）：报告为 `docs/perf/instances-poc-2026-11-20.md`。未达标的共 3 项：Q3 p95 94,403.54 毫秒（≤ 200 毫秒）；W1 吞吐 167.6 次/秒（≥ 200）；W1 单次写入 p95 93.66 毫秒（≤ 50 毫秒）。原记录漏了 W1 的两项。Q1 p95 32.36、Q2 p95 102.09、Q4 p95 146.84、Q5 p95 39.21 毫秒和两次行数一致达标。
- 根因（2026-11-21 分析，详见 `docs/perf/instances-poc-2026-11-20-root-cause.md`）：主要是测法缺陷。基准用不带连接池的 `PGSimpleDataSource`，每条 SQL 都新建一条物理连接；按报告数字反推，每次约 20 毫秒，而 Q1 的执行只要 0.46 毫秒。生产代码的 `TraceQueries.follow` 逐节点查询，把它放大了：Q3 一次调用发出 4,617 条 SQL（4,617 × 约 20 毫秒 ≈ 94 秒）。W1 每次写入平均要建 1.6 次连接（约 32 毫秒），报告中每次写入平均约 48 毫秒。本次分析没有在机器上复现，连接开销的直接测量由 P6-54 加进报告。现有数据不能作为评估 Apache AGE 的依据。
- 处理：人要求按根因分析拆卡（2026-11-21）。拆出 P6-53（生产：`follow` 每一跳一条 SQL）和 P6-54（基准测法修订：连接池、连接开销基线、W1 失败次数），两张都合并后重跑本卡。不调整门禁，不改 V1 表结构，不加索引，不改 ADR-013。本卡状态改回 `todo`，并依赖这两张卡。
