---
id: P6-18
title: 投影表基准测法修订：刷新统计信息与预热后采样，并记录执行计划、行数与后台活动
phase: P6
module: wp-app
type: impl
size: S
work_type: benchmark
ecc_extra: []
status: todo
depends_on: [P6-17]
---

# P6-18 投影表基准测法修订

## 目标
`ProjectionBenchmark` 在稳定、可解释的状态下测量三条查询：写入后先刷新统计信息、再预热、然后采样。报告附上 Q1 的执行计划、各查询的返回行数，以及采样期间的后台活动，看报告就能区分“计划差”和“环境干扰”。本卡只改测试代码，不运行正式基准，也不判定门禁；正式重跑在 P6-11。

## 参考
- 根因分析：`docs/perf/projection-poc-2026-11-14-root-cause.md` 第 2 节（4 条测法缺陷）；首次报告：`docs/perf/projection-poc-2026-11-14.md`
- P6-11：`backend/wp-app/src/test/java/wprebuild/app/projection/ProjectionBenchmark.java`、`ProjectionCorpus.java`（本卡修改的对象）
- P6-17：`ProjectionWriter.refreshStatistics()`
- P1-20：`docs/perf/kernel-benchmark-2026-11-06.md`（报告格式）
- PostgreSQL 18：`EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`；`PREPARE` / `EXPLAIN EXECUTE` / `DEALLOCATE`；`plan_cache_mode`（`force_custom_plan` / `force_generic_plan`）；`pg_stat_user_tables`；pgjdbc `PGConnection#getPrepareThreshold`

## 产出
- `ProjectionBenchmark.java`（测试代码）：
  1. 每次 `replace`（首次重建和第二次重建）之后，调用 `writer.refreshStatistics()`，耗时单独记为 (b2)“统计信息刷新”。门禁用的全量重建耗时是**首次重建**的 (a + b + b2)，阈值仍是 ≤ 60 秒；第二次重建的 b2 只记录。
  2. 首次重建之后、刷新统计信息之前（“刷新前”状态，只记录，不判定）：
     - 读取 `pg_stats` 中 `projection.hierarchy_edges.project_id` 的 `n_distinct`、`most_common_vals`、`most_common_freqs`，以及 5 张表的 `last_autoanalyze`，用来证实或推翻“统计信息里没有新项目”的推断；
     - 执行一次 Q1 的 `explain (analyze, buffers, settings)`（与 `runQ1` 一样用 `JdbcClient` 绑定参数），计划文本记为“重建后立即（刷新前）”；
     - 不预热，直接把 Q1 采样 100 次。
  3. 刷新之后，每条查询先预热 10 次，再采样 100 次。门禁用预热之后的 100 个样本判定；预热的 10 次作为“冷样本”单独报告 max 和中位数，不判定（BI 或 Agent 的一次性语句碰到的正是这个窗口）。Q1 的预热和采样固定在**同一个连接**上执行：在 `JdbcTemplate.execute(ConnectionCallback)` 内用 `new SingleConnectionDataSource(con, true)` 构造 `JdbcClient`，自动提交不变。采样结束后，在这个连接上读取 `pg_prepared_statements` 中 Q1 的 `generic_plans` 和 `custom_plans`，确认采样实际走的是哪种计划。全部采样结束后，在**同一个连接、同一个事务**内（`JdbcTemplate.execute(ConnectionCallback)` 加 `TransactionTemplate`）采集 Q1 的两份计划：`prepare wp_q1(uuid, text, uuid) as <Q1 SQL，占位符写成 $1 $2 $3>`；`set local plan_cache_mode = force_custom_plan` 后执行 `explain (analyze, buffers, settings) execute wp_q1(<与 runQ1 相同的参数>)`，记为“刷新并预热后（定制计划）”；`set local plan_cache_mode = force_generic_plan` 后再执行一次，记为“刷新并预热后（通用计划）”；最后 `deallocate wp_q1`。不要用 `explain (generic_plan)`：它不能绑定参数值，也不能和 `analyze` 同时使用。
  4. 每条查询记录 p50、p95、p99、max（都用现有的最近秩算法），以及返回行数。
  5. **每条查询**（Q1、Q2、Q3 分别）采样前后各读一次 5 张投影表的 `pg_stat_user_tables`（`autovacuum_count`、`autoanalyze_count`、`last_autovacuum`、`last_autoanalyze`），同时读取 `projection.hierarchy_edges` 的 `pg_class.relpages`、`reltuples`、`relallvisible`（按插入量触发的 VACUUM 不会被手动 ANALYZE 重置，它更新这些值后会引起重新规划），以及每个 `GarbageCollectorMXBean` 的次数和累计时间。GC 按 bean 名分别记录：JDK 20 起 `G1 Concurrent GC` 记的是并发周期时间，不是停顿时间。报告中每个规模一张“采样期间后台活动”表，每条查询一行（“刷新前”的 Q1 采样也算一行）：autovacuum 次数变化、autoanalyze 次数变化、`relallvisible` 变化、各 GC bean 的次数和时间变化。
  6. 机器配置表增加以下 PostgreSQL 设置（`show` 读取）：`shared_buffers`、`work_mem`、`effective_cache_size`、`random_page_cost`、`jit`、`jit_above_cost`、`jit_inline_above_cost`、`jit_optimize_above_cost`、`autovacuum_naptime`、`plan_cache_mode`；以及 pgjdbc 的 `prepareThreshold`。
  7. 报告附录按执行顺序列出 Q1“刷新前”和“刷新并预热后”各 100 个样本的耗时（毫秒，每行 10 个），以及每段采样的开始和结束时间（与 `last_autovacuum`、`last_autoanalyze` 对时）。慢样本连成一段，说明是计划切换；零散分布，说明是干扰。
  8. 报告的「测法」一节同步说明：统计信息刷新、预热次数、执行计划的采集时机。执行计划放在报告末尾的「执行计划」一节，每段用代码块。
  9. 报告文件已经存在时不覆盖，依次改用 `-2`、`-3` 后缀（例如 `projection-poc-2026-11-14-2.md`）。首次报告必须保留；重跑和首次运行可能在同一天。选文件名的逻辑抽成包内可见的纯函数 `static Path nextReportPath(Path dir, String baseName)`，文件名断言相应放宽。
  10. 冒烟模式 `-Dbench.smoke=true`：两个规模都换成约 2,000 个类的语料。报告写到 `backend/wp-app/target/projection-poc-smoke.md`，不写 `docs/perf/`，也不判定门禁；只断言报告里有三段 Q1 执行计划、`pg_stats` 记录、`generic_plans` / `custom_plans` 次数、冷样本、Q1 样本序列附录，以及每条查询一行的“采样期间后台活动”表。不加这个参数时，正式模式的行为不变（由 P6-11 重跑验证）。
- `projection/ProjectionReportPathTest.java`（测试代码，普通 JUnit，不需要 `-Dbench`，用 `@TempDir`）：文件不存在时用原名；原名已存在时用 `-2`；`-2` 也存在时用 `-3`。
- `ProjectionCorpus.java`（测试代码）：新增 `static Loaded smoke()`，约 2,000 个类，其余形状不变（分支系数 4、每个类一个标签、1,000 个对象属性和 1,000 个数据属性）。2,000 个类时，“深度为 3”的类仍然有后代。

## 实现要点
- **本卡只修测法，不做优化**（`benchmark` 类卡片的约定）。不改 `src/main`（`refreshStatistics` 已由 P6-17 提供）；不改三条查询的 SQL、语料形状、规模和门禁阈值；不改 PostgreSQL 容器配置，不关闭 autovacuum 或 JIT，也不等待 autovacuum 结束。autovacuum 和 GC 的数据只记录，不作为门禁。
- 把 Q1 的 SQL 主体提成常量，`runQ1`、刷新前的 `explain` 和 `prepare` 共用它，参数值也相同；`prepare` 只把 `?` 换成 `$1`、`$2`、`$3`。`explain (analyze …)` 会真正执行查询，所以它不计入样本。
- 测量顺序：重建 → 读取 `pg_stats` → 刷新前的 explain → 刷新前的 Q1 采样 → (b2) 刷新 → 计数 → 每条查询依次预热、采样（采样前后读取 autovacuum 计数、`pg_class` 和 GC）→ 读取 `pg_prepared_statements` → 预热后的两份 Q1 计划 → 两次重建的摘要比较（第二次重建后同样刷新统计信息）。摘要比较放在采样之后的理由保持不变。
- 统计信息过期时，刷新前的 Q1 采样在 50 万规模下每次可能要 1 秒以上，100 次合计几分钟，在现有的 3 小时超时之内，可以接受。
- 门禁语义从“写入后立即”变为“刷新统计信息并预热之后”。本卡在报告的「门禁结论」下写明这一点，并列出刷新前和冷样本的数据供人对照；是否接受由人在 P6-11 确认。
- p99 是 100 个样本中的第 99 个，max 是第 100 个。
- 冒烟模式的作用是验证整条报告管道（刷新、explain、预热、计数器读取、报告写出），不是测性能。
- 本卡没有正式报告，审查 agent `performance-optimizer` 审查的是测法本身（采样顺序、预热、执行计划的采集时机）和冒烟报告。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-18` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProjectionBenchmark,ProjectionReportPathTest' -Dbench=true -Dbench.smoke=true
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 冒烟报告里能看到三段 Q1 执行计划、`pg_stats` 记录、`generic_plans` / `custom_plans` 次数、冷样本、Q1 样本序列和“采样期间后台活动”表；执行记录中贴出其中的 Q1 行数和计划节点类型
- [ ] `docs/perf/` 下没有新增报告（本卡不运行正式基准）
- [ ] 执行记录已填写

## 不在本卡范围
- 运行正式基准、判定门禁（P6-11 重跑）
- 任何性能优化，任何 `src/main` 改动，调整 PostgreSQL 配置
- 修改门禁阈值、语料形状或规模
