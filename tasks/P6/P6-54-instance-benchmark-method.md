---
id: P6-54
title: 实例库基准测法修订：改用连接池，记录连接开销基线与 W1 失败次数
phase: P6
module: wp-instances
type: impl
size: S
work_type: benchmark
ecc_extra: []
status: done
depends_on: [P6-31]
---

# P6-54 实例库基准测法修订

## 目标
`InstanceBenchmark` 在复用连接的状态下测量查询和写入，不再把“每条 SQL 新建一条物理连接”的开销算进 Q1 ~ Q5 和 W1。报告附上连接开销基线和 W1 的失败次数，看报告就能区分“建连接慢”和“查询/写入慢”。本卡只改测试代码，不运行正式基准，也不判定门禁；正式重跑在 P6-32。

## 参考
- 根因分析：`docs/perf/instances-poc-2026-11-20-root-cause.md` 第 1、3 节；首次报告：`docs/perf/instances-poc-2026-11-20.md`
- P6-32：`backend/wp-instances/src/test/java/wprebuild/instances/bench/InstanceBenchmark.java`（`dataSource()`、`measureW1` 与报告生成部分）、`WarehouseCorpus.java`（COPY 通过 `connection.unwrap(PGConnection.class)` 取 `CopyManager`）
- P6-18：`backend/wp-app/src/test/java/wprebuild/app/projection/ProjectionBenchmark.java`（在一条连接上预热和采样的做法）。`wp-instances` 的 Enforcer 禁止依赖 `org.springframework*`，不能照搬其中的 `SingleConnectionDataSource`
- `backend/wp-instances/pom.xml`：已 import `spring-boot-dependencies`（`spring-boot.version` 4.1.1），其中管理 `com.zaxxer:HikariCP` 的版本；`enforce-architecture` 中的 `dependencyConvergence` 与 `bannedDependencies`
- HikariCP：`HikariConfig`、`HikariDataSource#getHikariPoolMXBean()`（`getIdleConnections`、`getTotalConnections`）

## 产出
- `backend/wp-instances/pom.xml`：新增 test 作用域依赖 `com.zaxxer:HikariCP`，**不写版本号**（由 Spring Boot BOM 管理）。这是本卡唯一的新依赖，只在测试类路径上；`src/main` 不使用它。
- `InstanceBenchmark.java`（测试代码）：
  1. `dataSource()` 改为返回 `HikariDataSource`：`jdbcUrl`、用户名、密码取自容器；`maximumPoolSize = 10`、`minimumIdle = 10`（W1 的 8 个写入线程各占一条，另留 2 条给基准自身的查询）；`autoCommit = true`；`poolName = "instances-bench"`。其余用 HikariCP 默认值，pgjdbc 的 `prepareThreshold` 保持默认。`@AfterEach` 中关闭连接池。
  2. 采样前预热连接池：在造数和 `ANALYZE` 之后、第一轮查询之前，等到 `getIdleConnections()` 等于 `maximumPoolSize`（每 100 毫秒检查一次，最多等 30 秒，超时则测试失败并说明原因）。W1 开始前再检查一次。
  3. **连接开销基线**（只记录，不判定），在第一轮查询之前测量：
     - 新建连接：用一个单独的 `PGSimpleDataSource`（与首次运行相同的配置），执行 20 次“`getConnection()` → `select 1` → `close()`”，记录每次的总耗时；
     - 池内连接：从连接池执行 100 次“`getConnection()` → `select 1` → `close()`”，前 10 次作为预热不计；
     - 报告新增「连接开销基线」一节，两行分别列出 p50、p95、max 和样本数，并写一句说明：首次运行（2026-11-20）的每条 SQL 都付出了“新建连接”这一行的开销。
  4. W1 按异常类型统计失败次数：保留现有的 `catch`，另用 `ConcurrentHashMap<String, LongAdder>` 按异常的简单类名计数（`IllegalArgumentException`、`IllegalStateException`、`SQLException` 等）。报告的「W1 写入」表增加“失败次数”和“失败率（失败 ÷ (成功 + 失败)）”两行，并按异常类名列出明细。失败次数只记录，不作门禁；吞吐和 p95 的计算口径不变（只计成功的操作）。
  5. 机器配置表增加连接池的配置：`maximumPoolSize`、`minimumIdle`、`autoCommit`、HikariCP 版本（`HikariDataSource.class.getPackage().getImplementationVersion()`，取不到时写 `unknown`），以及 pgjdbc 的 `prepareThreshold`。
  6. 「测法」一节同步说明：查询和写入都通过连接池执行，采样前连接池已填满；以及连接开销基线的测法。
  7. `@Timeout` 从 12 小时改为 3 小时，同时更新上面的注释（P6-53 合并后 Q3 不再逐节点查询；本卡单独合并时只跑冒烟模式，不受影响）。
  8. 冒烟模式 `-Dbench.smoke=true`：报告仍写到 `backend/wp-instances/target/instances-poc-smoke.md`，不写 `docs/perf/`，不判定门禁。另断言报告中有「连接开销基线」一节（两行都有数值）、W1 的“失败次数”和“失败率”两行、机器配置表中的 `maximumPoolSize`。不加这个参数时，正式模式的行为不变（由 P6-32 重跑验证）。
- `WarehouseCorpus.java`：原则上不改。COPY 已经通过 `unwrap(PGConnection.class)` 取得底层连接，可以经 HikariCP 的代理连接工作；如果实测不行，只改取连接的那几行，并在执行记录中说明。

## 实现要点
- **本卡只修测法，不做优化**（`benchmark` 类卡片的约定）。不改 `src/main`（`TraceQueries.follow` 的 N+1 由 P6-53 处理）；不改语料、规模、查询 SQL、采样次数、W1 的线程数和混合比例、门禁阈值；不改 PostgreSQL 容器配置，不关闭 autovacuum 或 JIT。
- 连接池是测法的一部分，不是优化：服务化之后（P6-34）一定有连接池，首次运行测到的是建连接的开销，不是实例库本身。新建连接的耗时作为基线写进报告，方便对照首次运行。
- 连接池大小固定写在代码里，并写进报告；不要按 CPU 数自动计算，重跑时要可比。
- 连接开销基线放在第一轮查询之前测，避免和 W1 的写入混在一起。“新建连接”的 20 次一共约 0.5 秒。
- 新增依赖之后，确认 `./mvnw -q -pl :wp-instances -am verify` 中 `enforce-architecture` 仍然通过（HikariCP 只依赖 `slf4j-api`，版本由 BOM 统一）。
- 本卡没有正式报告。审查 agent `performance-optimizer` 审查的是测法本身（连接池配置、预热时机、失败计数）和冒烟报告。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-54` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl :wp-instances -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InstanceBenchmark -Dbench=true -Dbench.smoke=true
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 冒烟报告中能看到「连接开销基线」两行、W1 的失败次数与失败率、连接池配置；执行记录中贴出冒烟报告里两行基线的 p50
- [x] `docs/perf/` 下没有新增报告（本卡不运行正式基准）
- [x] 执行记录已填写

## 不在本卡范围
- 运行正式基准、判定门禁（P6-32 重跑）
- `TraceQueries`、`InstanceStore` 或任何 `src/main` 改动（P6-53）
- 修改语料、规模、查询、采样次数、W1 的参数或门禁阈值
- 调整 PostgreSQL 配置；把 HikariCP 引入 `src/main`

## 执行记录
- 日期：2026-11-21
- 分支：feature/p6-54-instance-benchmark-method
- 测试：2026-11-21T18:04:35+08:00 通过（3 项）
- 实际改动：InstanceBenchmark 改用 HikariCP（10/10）测查询与写入；报告新增连接开销基线（新建连接 p50≈23.90ms，池内 p50≈0.75ms）与 W1 失败次数/失败率；超时改为 3h。WarehouseCorpus 未改。docs/perf/ 无新报告。performance-optimizer 审查 LGTM（无 CRITICAL/HIGH/MEDIUM；LOW：HikariCP 版本常为 unknown，属卡片允许回退）。
- 偏差：无
- 审查：performance-optimizer
