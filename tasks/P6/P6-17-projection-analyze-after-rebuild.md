---
id: P6-17
title: 投影表全量重建后刷新统计信息：提交后对 5 张投影表执行 ANALYZE
phase: P6
module: wp-app
type: impl
size: S
work_type: db
ecc_extra: [concurrency]
status: done
depends_on: [P6-10]
---

# P6-17 投影表全量重建后刷新统计信息

## 目标
全量重建一个项目的投影之后，立即刷新投影表的统计信息，不再等 autoanalyze（项目多时可能很久都不触发）。目标是消除“新项目约 1 行”这类极端低估；共享表上的小项目不一定能进入最常见值列表，这时规划器按其余值的平均频率估计，这是可以接受的。

## 参考
- 根因分析：`docs/perf/projection-poc-2026-11-14-root-cause.md` 第 2、3 节；首次报告：`docs/perf/projection-poc-2026-11-14.md`
- P6-09：`projection/ProjectionWriter.java`（`lockProject`、`replaceProject`，加入调用方已有的事务，不自己开事务）、`db/migration/V5__projection_schema.sql`（5 张表：`entities`、`labels`、`hierarchy_edges`、`property_domains`、`property_ranges`）
- P6-10：`projection/ProjectionRebuilder.java`（`TransactionTemplate`，有外层事务时加入外层事务；P6-12 的中继会在中继事务内调用它）、`projection/ProjectionRebuildTest.java`（项目的准备方式；它是 `@Transactional`，测试结束会回滚）
- Spring `TransactionSynchronization#afterCommit` 的 Javadoc：回调执行时事务已经提交，但事务资源仍然绑定；在回调里访问数据库，要用 `PROPAGATION_REQUIRES_NEW` 开新事务
- PostgreSQL `ANALYZE`：可以在事务块内执行；持有 `SHARE UPDATE EXCLUSIVE` 锁直到事务结束（它和自身冲突，不阻塞普通读写）；执行者不是表的所有者时，只发出 WARNING 并跳过这张表，不报错（PostgreSQL 17 起也可以授予 `MAINTAIN` 权限）

## 产出
- `projection/ProjectionWriter.java`：新增 `public void refreshStatistics()`，执行一条 `analyze projection.entities, projection.labels, projection.hierarchy_edges, projection.property_domains, projection.property_ranges`。表名列表是常量，和 V5 一致，不接受外部输入。Javadoc 写明：调用方负责在重建事务提交之后调用；数据库账号必须是这些表的所有者，或拥有 `MAINTAIN` 权限（PostgreSQL 17 起）。
  - 权限不足时 PostgreSQL 只发 WARNING 并跳过，而 Spring `JdbcTemplate` 默认忽略 SQLWarning（只记 DEBUG），所以这种失效必须显式发现。新增包内可见的 `List<String> tablesMissingMaintain()`，用 `has_table_privilege(<表>, 'MAINTAIN')` 返回缺少权限的表名。`refreshStatistics()` 先调用它：有缺失时用 `log.warn` 列出表名，只分析其余的表；5 张都缺时不执行 `analyze`。
- `projection/ProjectionRebuilder.java`：`rebuild` 中，`replaceProject` 返回 `true` 之后注册一个 `TransactionSynchronization`；在 `afterCommit` 里，用传播行为为 `PROPAGATION_REQUIRES_NEW` 的 `TransactionTemplate` 调用 `writer.refreshStatistics()`。同一个事务里多次调用 `rebuild`（例如重建多个项目）时只注册一次，用事务资源标记去重。
  - `rebuild` 自己开事务时：本事务提交后刷新；
  - 加入外层事务时（P6-12 的中继）：外层事务提交后刷新；外层回滚时不刷新；
  - `replaceProject` 返回 `false`（存储的修订号更新，投影行没有变）时不注册，不刷新；
  - 刷新失败时用 `log.warn` 记录项目 ID 和异常，不再抛出。重建已经提交，统计信息之后还会由 autoanalyze 补上；抛出的话，调用方（P6-12 的中继）会把已经提交的批次当成失败。
- `projection/ProjectionStatisticsRefreshTest.java`（`@DatabaseTest`，**不加** `@Transactional`，否则提交后的回调永远不会触发）。项目的准备方式参照 `ProjectionRebuildTest`。以下断言都基于 `pg_stat_user_tables.analyze_count`：它只统计手动执行的 ANALYZE，不含 `autoanalyze_count`，所以不受 autovacuum 干扰。前后两次读取必须在不同的事务中（`stats_fetch_consistency` 默认为 `cache`，同一事务内会读到缓存值）；正例断言“至少加 1”，不断言恰好加 1。
  - 直接调用 `refreshStatistics()`：5 张表的 `analyze_count` 都增加；
  - 没有外层事务时调用 `rebuild`：返回后 5 张表的 `analyze_count` 都增加，并且 `hierarchy_edges` 的 `pg_class.reltuples` 不小于该项目写入的边数；
  - 在外层 `TransactionTemplate` 中调用 `rebuild`：`rebuild` 返回时（外层尚未提交）`analyze_count` 不变，外层提交后增加；另一例中外层回滚，`analyze_count` 不变；
  - 同一个外层事务中重建两个项目：提交后 `hierarchy_edges` 的 `analyze_count` 只增加 1；
  - 权限检查：在一个最终回滚的事务里 `create role` 一个临时角色（`nologin`，不授予任何权限），`set local role` 切换过去，`tablesMissingMaintain()` 返回全部 5 张表；切回原账号后返回空列表；
  - 先用更大的修订号写入该项目的 `project_state`，再调用 `rebuild`（`replaceProject` 返回 `false`）：`analyze_count` 不变；
  - 刷新失败：用覆盖了 `refreshStatistics()`、让它抛出 `DataAccessResourceFailureException` 的 `ProjectionWriter` 匿名子类，手工构造一个 `ProjectionRebuilder`（不用 mock 框架）。`rebuild` 正常返回，不抛异常，投影行和 `project_state` 已经提交；
  - 统计视图可能有短暂延迟：正例最多轮询 5 秒；负例等待 1 秒后再断言没有变化。

## 实现要点
- 只刷新统计信息，不执行 VACUUM。VACUUM 不能在事务块内执行；回收死元组、设置可见性映射交给 autovacuum。
- 不在 `replaceProject` 内部刷新：那时数据还没提交，而且 ANALYZE 的锁会一直持有到重建事务结束，其它项目的并发重建会在 ANALYZE 上排队。P6-12 的增量路径（`replaceSubjects`）也不刷新，小批变更交给 autoanalyze。
- 锁顺序（ADR-006）：刷新发生在重建事务提交之后，这时已经不持有投影咨询锁和内核读锁。两个项目同时重建时，后一个 ANALYZE 会等前一个结束，这是预期行为，不会死锁。
- `ProjectionRebuildTest` 是 `@Transactional`，测试结束时回滚，提交后的回调不会触发，它的断言不受影响。
- `afterCommit` 执行时，外层事务的连接还没有归还连接池，`REQUIRES_NEW` 会再占用一条连接。连接池至少要有 2 条连接（Hikari 默认 10 条）。
- 耗时：ANALYZE 默认每张表采样约 3 万行，50 万公理规模下预计合计不到 1 秒。P6-18 会把这部分时间计入全量重建的耗时。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-17` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProjectionStatisticsRefreshTest,ProjectionRebuildTest,ProjectionWriterTest,ModularityTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] `refreshStatistics` 的 Javadoc 写明了调用时机与数据库账号权限要求
- [x] 执行记录已填写


## 不在本卡范围
- VACUUM、新增索引、修改表结构（见根因分析最后一节，由人决定）
- 修改 `ProjectionBenchmark` 或 `ProjectionCorpus`（P6-18）
- 增量更新路径的统计信息（P6-12 交给 autoanalyze）
- 配置开关（`wp.projection.*` 属性在 P6-12 引入）
- 运行基准、新增 `docs/perf/` 下的报告（P6-11 重跑）

## 执行记录
- 日期：2026-11-14
- 分支：feature/p6-17-projection-analyze-after-rebuild
- 测试：2026-11-14T22:23:43+08:00 通过（3 项）
- 实际改动：ProjectionWriter.refreshStatistics 对 5 张投影表执行 ANALYZE（缺 MAINTAIN 时告警并跳过）；ProjectionRebuilder 在 replaceProject 成功后注册 afterCommit，用 REQUIRES_NEW 刷新且同事务去重；刷新失败只 warn 不抛。新增 ProjectionStatisticsRefreshTest。
- 偏差：docs/INDEX 与 dependency-graph 由 finish 自动更新；hasMaintainPrivilege 仅将 permission denied / insufficient privilege 视为缺 MAINTAIN，其它 DAE 重新抛出。
- 审查：database-reviewer, java-reviewer, silent-failure-hunter
