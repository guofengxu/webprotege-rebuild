---
id: P6-53
title: TraceQueries.follow 改为每一跳一条 SQL，消除逐节点查询（N+1）
phase: P6
module: wp-instances
type: impl
size: S
work_type: db
ecc_extra: []
status: done
depends_on: [P6-31]
---

# P6-53 TraceQueries.follow 按跳批量查询

## 目标
`TraceQueries.follow` 一次调用只取一个连接，每一跳只发一条 SQL。Q3 这样的三跳追溯从 4,617 次数据库往返降到 3 次，方法签名和返回结果不变。

## 参考
- 根因分析：`docs/perf/instances-poc-2026-11-20-root-cause.md` 第 2 节；首次报告：`docs/perf/instances-poc-2026-11-20.md`（Q3 p95 约 94 秒）
- P6-31：`backend/wp-instances/src/main/java/wprebuild/instances/TraceQueries.java`（`follow`、`neighbors`）、`PathStep`、`Direction`；`V1__instances_schema.sql` 中的索引 `links_history_subject_property`、`links_history_object_property`
- P6-31 的现有测试：`backend/wp-instances/src/test/java/wprebuild/instances/InstanceStoreTest.java`（`follow` 在“批次 → 托盘 → 货位”上按 `asOf` 区分移库前后）
- `reachable` 的写法：同一个 `Connection` 上 `createArrayOf("text", …)` 绑定数组参数

## 产出
- `TraceQueries.java`（`src/main`）：
  - `public List<List<String>> follow(String startIri, List<PathStep> path, Instant asOf)` 签名不变，参数校验不变（`path` 为空时返回 `List.of(List.of(startIri))`）。
  - 整个调用只调用一次 `dataSource.getConnection()`，各跳的 SQL 在同一个连接上执行。
  - 每一跳：取当前前沿中所有路径的末端节点，去重后作为一个 `text[]` 参数，执行一条 SQL，一次取回该跳的全部 `(from_iri, to_iri)` 对：
    ```sql
    -- FORWARD
    select subject_iri as from_iri, object_iri as to_iri
    from links
    where subject_iri = any (?) and property_iri = ?
      and valid_from <= ? and (valid_to is null or valid_to > ?)
    -- BACKWARD：from_iri 取 object_iri，to_iri 取 subject_iri，条件改为 object_iri = any (?)
    ```
    在内存中把结果按 `from_iri` 分组，再按前沿的原有顺序逐条扩展路径。某一跳的结果为空时直接返回 `List.of()`，不再执行后面的跳。
  - 末端节点超过 `MAX_BATCH = 10_000` 个时，按 `MAX_BATCH` 分批执行同一条 SQL，结果合并后再扩展。分批只限制单条语句的参数大小，返回结果与不分批时相同。
  - 删除私有方法 `neighbors`。
- 测试 `backend/wp-instances/src/test/java/wprebuild/instances/TraceQueriesFollowTest.java`（Testcontainers `postgres:18`，用 `InstanceSchema.migrate` 建表，`InstanceStore.link` 造数）：
  - 扇出：起点经第 1 跳到 50 个中间节点，每个中间节点经第 2 跳到 2 个终点，返回 100 条完整路径 `[start, mid, end]`，内容与期望值相同（`containsExactlyInAnyOrderElementsOf`）；
  - 菱形：`A → B`、`A → C`、`B → D`、`C → D`、`D → E`，三跳返回 `[A, B, D, E]` 和 `[A, C, D, E]` 两条路径（共享末端的路径各自扩展，不因去重丢失）；
  - 反向一跳（`Direction.BACKWARD`）与正向混合的三跳路径，结果正确；
  - 中间某一跳没有匹配时返回空列表；`asOf` 早于关系的 `valid_from`、或晚于等于 `valid_to` 时，该关系不参与；
  - 往返次数：用测试内的计数 `DataSource` 包装（`java.lang.reflect.Proxy` 代理 `DataSource` 与 `Connection`，统计 `getConnection` 和 `prepareStatement` 的调用次数），对上面 50 × 2 的扇出调用一次 `follow`，断言 `getConnection` 为 1 次、`prepareStatement` 为 2 次（等于跳数）；
  - 分批：分批逻辑抽成包内可见的纯函数 `static <T> List<List<T>> chunks(List<T> items, int size)`，单独测试空列表、恰好整除、有余数三种情况。
- `InstanceStoreTest.java` 不修改，现有的 `follow` 测试继续通过。

## 实现要点
- 返回路径的**顺序**与原实现一样不作保证：原实现依赖每条 SQL 的返回顺序，新实现依赖一条 SQL 的返回顺序加分组。新测试一律用与顺序无关的断言；`InstanceStoreTest` 现有的 `containsExactly` 只有一条路径，不受影响。
- 路径数量的语义不变：一个末端节点有 k 个邻居时，经过它的每条路径都扩展成 k 条。同一时间点上同一个 `(subject, property, object)` 只有一个有效版本，所以不会多出重复路径。
- 同一次调用内各跳不放进显式事务，与原实现相同（各语句各自取快照）。是否需要整次追溯共用一个快照，由 P6-34 服务化时决定。
- 连接用 `try-with-resources` 取一次；每一跳（每一批）在该连接上 `createArrayOf` 一个数组，用完后 `free()`。
- 不改 `reachable`（已经是一条递归 CTE），不改 `InstanceStore`，不改表结构和索引。现有索引 `links_history_subject_property (subject_iri, property_iri, valid_from)` 与 `links_history_object_property (object_iri, property_iri, valid_from)` 支持 `= any (?)` 的索引扫描。
- 不在 `src/main` 引入连接池：连接池是调用方（基准、P6-34 的服务）的事。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-53` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstanceStoreTest,TraceQueriesFollowTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] `TraceQueriesFollowTest` 断言了单次 `follow` 调用的 `getConnection` 与 `prepareStatement` 次数
- [x] 执行记录已填写

## 不在本卡范围
- 修改基准代码、运行基准（P6-54 修订测法，P6-32 重跑）
- `reachable`、`InstanceStore`、表结构、索引的任何改动
- 把 Q3 或 Q4、Q5 改写成一条连接查询并提升为正式接口（P6-34 决定）
- 在 `wp-instances` 的 `src/main` 中引入连接池或新的第三方依赖

## 执行记录
- 日期：2026-11-21
- 分支：feature/p6-53-trace-follow-batched
- 测试：2026-11-21T14:50:13+08:00 通过（3 项）
- 实际改动：TraceQueries.follow 改为每跳一条 SQL（同一连接、tips 去重后 = any(?)，超 10k 分批），删除 neighbors；新增 TraceQueriesFollowTest（扇出/菱形/正反向/asOf/往返次数/chunks）。顺带 spotless 格式化了基准测试源文件（无逻辑改动）。
- 偏差：为通过全量 spotless:check，对 InstanceBenchmark.java 与 WarehouseCorpus.java 仅做了 Spotless 格式化（无行为改动）；卡片写明不改基准代码，故记入偏差。
- 审查：database-reviewer, java-reviewer
