# 投影表 PoC 基准（2026-11-14）

测量 `ProjectionRows.compute`、`ProjectionWriter.replaceProject`、统计信息刷新与三条典型 SQL。本卡不做任何优化。

## 机器配置

| 项 | 值 |
| --- | --- |
| CPU | Intel(R) Xeon(R) CPU E5-2673 v3 @ 2.40GHz |
| 逻辑处理器 | 8 |
| 内存 | 32814464 kB |
| JDK | 25.0.5.0.3 (OpenJDK 64-Bit Server VM) |
| -Xmx | -Xmx6g |
| 堆上限 Runtime.maxMemory | 6.00 GiB（6442450944 字节） |
| PostgreSQL | 18.6 (Debian 18.6-1.pgdg13+2) (Testcontainers postgres:18) |
| random_page_cost | 4 |
| jit | on |
| effective_cache_size | 4GB |
| work_mem | 4MB |
| plan_cache_mode | auto |
| shared_buffers | 128MB |
| jit_above_cost | 100000 |
| jit_inline_above_cost | 500000 |
| autovacuum_naptime | 1min |
| jit_optimize_above_cost | 500000 |
| pgjdbc prepareThreshold | 5 |

## 结果

| 规模 | 场景 | 测量值 | 门禁 | 判定 |
| --- | --- | --- | --- | --- |
| 约 10 万（105,998 公理） | ProjectionRows.compute (a) | 0.277 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | ProjectionWriter.replaceProject (b) | 4.313 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 统计信息刷新 (b2) | 0.749 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 全量重建 (a+b+b2) | 5.339 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 第二次重建统计信息刷新 (b2) | 0.725 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 p50 | 29.81 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 p95 | 47.18 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 p99 | 3272.83 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 max | 3283.51 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 p50 | 24.83 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 p95 | 31.18 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 p99 | 45.66 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 max | 47.03 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 冷样本 max | 44.59 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 冷样本中位数 | 25.33 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 prepared custom_plans / generic_plans | 5 / 105 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 返回行数 | 1,364 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 p50 | 0.74 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 p95 | 1.05 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 p99 | 1.30 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 max | 1.34 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 冷样本 max | 3.78 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 冷样本中位数 | 1.07 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 返回行数 | 20 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 p50 | 0.54 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 p95 | 0.90 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 p99 | 1.40 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 max | 1.53 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 冷样本 max | 2.31 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 冷样本中位数 | 0.89 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 返回行数 | 2 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 两次重建 md5 一致 (e) | 相同 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | ProjectionRows.compute (a) | 1.131 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | ProjectionWriter.replaceProject (b) | 20.562 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | 统计信息刷新 (b2) | 2.090 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | 全量重建 (a+b+b2) | 23.784 秒 | ≤ 60 秒 | 达标 |
| 约 50 万（506,000 公理） | 第二次重建统计信息刷新 (b2) | 2.427 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 p50 | 117.04 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 p95 | 1416.45 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 p99 | 1496.41 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 max | 1561.82 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 后代 p50 | 115.36 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 后代 p95 | 145.90 毫秒 | ≤ 200 毫秒 | 达标 |
| 约 50 万（506,000 公理） | Q1 后代 p99 | 158.22 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 后代 max | 158.27 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 冷样本 max | 144.42 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 冷样本中位数 | 115.10 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 prepared custom_plans / generic_plans | 5 / 105 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 返回行数 | 5,460 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 标签前缀 p50 | 31.67 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 标签前缀 p95 | 41.99 毫秒 | ≤ 200 毫秒 | 达标 |
| 约 50 万（506,000 公理） | Q2 标签前缀 p99 | 54.84 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 标签前缀 max | 57.03 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 冷样本 max | 47.91 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 冷样本中位数 | 34.54 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 返回行数 | 20 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 p50 | 0.52 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 p95 | 0.98 毫秒 | ≤ 200 毫秒 | 达标 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 p99 | 1.25 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 max | 1.34 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 冷样本 max | 1.18 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 冷样本中位数 | 0.79 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 返回行数 | 2 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | 两次重建 md5 一致 (e) | 相同 | 相同 | 达标 |

### 行数

| 规模 | entities | labels | hierarchy_edges | property_domains | property_ranges |
| --- | --- | --- | --- | --- | --- |
| 约 10 万（105,998 公理） | 35,336 | 33,333 | 33,332 | 2,000 | 2,000 |
| 约 50 万（506,000 公理） | 168,670 | 166,667 | 166,666 | 2,000 | 2,000 |

### 刷新前 pg_stats（hierarchy_edges.project_id）

| 规模 | n_distinct | most_common_vals | most_common_freqs |
| --- | --- | --- | --- |
| 约 10 万（105,998 公理） | missing | missing | missing |
| 约 50 万（506,000 公理） | 1.0 | {d9119361-e8af-4f92-a2ec-d565501d2372} | {1} |

## 门禁结论

初始通过标准只看约 50 万公理规模。约 10 万规模用同一测法对照，不单独作为门槛。

**门禁语义（P6-18）**：全量重建为首次重建的 (a + b + b2)；查询门禁用刷新统计信息并预热之后的 100 个样本判定。刷新前采样与冷样本只记录，供人对照；是否接受该语义由人在 P6-11 重跑时确认。

| 项 | 测量值 | 判定 |
| --- | --- | --- |
| 全量重建 (a+b+b2) | 23.784 秒 | 达标 |
| Q1 p95（预热后） | 145.90 毫秒 | 达标 |
| Q1 刷新前 p95 | 1416.45 毫秒 | 只记录 |
| Q1 冷样本 max / 中位数 | 144.42 毫秒 / 115.10 毫秒 | 只记录 |
| Q2 p95（预热后） | 41.99 毫秒 | 达标 |
| Q3 p95（预热后） | 0.98 毫秒 | 达标 |
| 两次重建 md5 | 相同 | 达标 |

结论：全部达标。

## 测法

- 语料：`ProjectionCorpus`，类树与 `KernelBenchmark` 相同（分支系数 4，每类 1 条 `rdfs:label`（en），种子 20），类数 33333 与 166667；另加 1000 个对象属性与 1000 个数据属性，各带定义域与值域。经内存 `RevisionLog` / `SnapshotStore` 与 `ProjectRuntimeLoader` 加载，不经上传导入。
- (a) `ProjectionRows.compute(runtime)`；(b) 同一事务内 `lockProject` + `replaceProject` 写入 Testcontainers PostgreSQL 18。
- (b2) 每次 `replace` 提交后调用 `ProjectionWriter.refreshStatistics()`（`ANALYZE` 五张投影表），耗时单独记录；门禁用的全量重建耗时是**首次重建**的 (a + b + b2)。
- 刷新前：读取 `pg_stats` 中 `hierarchy_edges.project_id` 的统计与各表 `last_autoanalyze`；执行一次 Q1 的 `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`；不预热，直接采样 Q1 100 次（只记录）。
- (c) 刷新之后对各投影表 `count(*)`。
- (d) 每条查询先预热 10 次（冷样本：max / 中位数，不判定），再采样 100 次；门禁用预热后的样本。Q1 的预热与采样固定在同一 JDBC 连接上；Q2/Q3 预热采样期间该连接保持占用。全部采样结束后，在该连接上读取本会话 `pg_prepared_statements` 相对本轮起点的 `custom_plans` / `generic_plans` 增量（先 `DEALLOCATE ALL` 清掉连接复用残留），再在同一连接同一事务内 `PREPARE wp_q1`，分别 `force_custom_plan` 与 `force_generic_plan` 采集两份 `EXPLAIN (ANALYZE, BUFFERS, SETTINGS) EXECUTE`。p50 / p95 / p99 / max 用最近秩：排序后第 `ceil(p × n)` 个样本（p99 为第 99 个，max 为第 100 个）。
- 每条查询（含刷新前 Q1）采样前后读取 `pg_stat_user_tables`、`hierarchy_edges` 的 `pg_class`（`relpages` / `reltuples` / `relallvisible`）与各 `GarbageCollectorMXBean`。
- (e) 连续重建两次（第二次重建后同样刷新统计信息），对各表按主键列排序后取 `md5(string_agg(...))`，比较是否相同；摘要比较放在全部采样之后。
- 比较的表：entities, labels, hierarchy_edges, property_domains, property_ranges。
- 报告文件已存在时不覆盖，依次使用 `-2`、`-3` 后缀（冒烟报告固定写到 `target/projection-poc-smoke.md`）。

## 采样期间后台活动

### 约 10 万（105,998 公理）

| 查询 | Δautovacuum | Δautoanalyze | Δrelpages | Δreltuples | Δrelallvisible | GC |
| --- | --- | --- | --- | --- | --- | --- |
| Q1 刷新前 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | 575 | 33333.0 | 573 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |
| Q1 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |
| Q2 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |
| Q3 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |

刷新前各表 last_autoanalyze：entities=null, labels=null, hierarchy_edges=null, property_domains=null, property_ranges=null

### 约 50 万（506,000 公理）

| 查询 | Δautovacuum | Δautoanalyze | Δrelpages | Δreltuples | Δrelallvisible | GC |
| --- | --- | --- | --- | --- | --- | --- |
| Q1 刷新前 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | 2299 | 166666.0 | 2296 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |
| Q1 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |
| Q2 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |
| Q3 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0 |

刷新前各表 last_autoanalyze：entities=2026-11-14T15:29:29.066124Z, labels=2026-11-14T15:29:29.462274Z, hierarchy_edges=2026-11-14T15:29:29.923266Z, property_domains=2026-11-14T15:29:29.933685Z, property_ranges=2026-11-14T15:29:28.934435Z

## 附录：Q1 样本序列

耗时单位：毫秒；每行 10 个样本。慢样本连成一段通常表示计划切换；零散分布通常表示干扰。

### 约 10 万（105,998 公理） — 刷新前

采样开始：2026-11-14T15:29:23.191320757Z；结束：2026-11-14T15:29:35.864870199Z

```
3272.46 3272.83 3283.51 27.72 29.89 26.51 33.33 26.06 31.35 31.53
31.84 30.66 31.55 30.70 41.71 30.69 27.57 36.43 49.29 32.13
30.73 26.31 31.16 30.79 31.34 30.88 32.43 30.98 30.76 30.83
30.61 30.43 29.81 24.58 30.95 24.87 24.58 24.20 25.51 24.59
32.47 31.72 24.99 24.11 33.18 30.10 32.31 32.73 31.91 32.19
32.57 26.00 25.30 52.32 32.38 32.97 31.58 32.19 32.18 25.31
31.62 24.97 24.18 24.55 25.48 24.17 24.74 25.19 24.97 30.35
25.03 42.21 24.83 24.81 24.95 24.68 24.73 25.70 24.05 24.97
25.95 25.96 47.18 25.87 25.50 25.06 25.30 24.35 24.96 25.46
24.83 42.27 31.03 25.15 24.57 40.40 24.71 24.87 25.47 24.96
```

### 约 10 万（105,998 公理） — 刷新并预热后

采样开始：2026-11-14T15:29:36.934984472Z；结束：2026-11-14T15:29:39.531356606Z

```
25.51 24.85 24.54 24.75 31.43 24.50 25.59 25.45 24.83 25.18
25.85 24.74 24.77 25.24 36.76 24.99 26.03 24.69 25.51 25.58
24.49 47.03 25.29 24.46 25.14 24.80 25.06 25.18 25.01 25.07
25.09 25.09 25.11 24.49 24.56 24.94 24.72 24.30 25.38 24.73
24.87 25.00 25.79 24.50 25.16 25.47 31.18 30.89 30.54 30.84
25.20 26.27 25.23 24.86 45.66 30.61 30.88 28.39 23.76 24.23
23.88 23.54 23.85 38.26 23.69 24.25 26.15 24.18 24.17 24.40
24.14 23.88 23.69 25.36 23.70 23.42 23.87 23.82 23.43 23.84
24.37 24.24 24.00 23.71 24.35 24.11 24.26 25.22 25.80 24.18
24.26 30.78 24.33 24.27 24.45 24.86 24.05 31.08 24.23 23.59
```

### 约 50 万（506,000 公理） — 刷新前

采样开始：2026-11-14T15:30:43.126122448Z；结束：2026-11-14T15:31:40.697342005Z

```
1496.41 1385.44 1423.33 1354.56 1416.45 1367.77 1349.56 1415.52 1561.82 1482.82
1333.80 1297.97 1310.08 1379.74 1407.83 1396.29 1326.66 1356.67 1370.91 1362.65
1356.20 1349.43 1338.93 1346.16 1291.68 1287.24 1295.97 1291.44 1292.16 1282.34
1285.99 1289.94 1298.67 1346.80 1299.54 1341.68 1419.35 115.61 155.82 115.90
113.23 112.56 112.37 113.45 112.42 113.43 112.17 111.48 156.66 142.55
117.83 111.90 110.66 111.43 110.25 110.60 110.69 111.53 117.64 122.00
111.63 110.82 109.83 115.29 110.85 116.95 113.09 111.27 110.38 111.30
152.56 133.62 110.82 110.72 111.39 111.24 111.23 111.40 159.55 116.14
110.53 110.56 111.73 111.62 119.36 117.04 116.03 112.56 111.02 110.43
110.50 110.70 111.69 110.95 118.49 142.14 121.31 110.76 110.76 110.82
```

### 约 50 万（506,000 公理） — 刷新并预热后

采样开始：2026-11-14T15:31:44.065523466Z；结束：2026-11-14T15:31:56.000686638Z

```
115.15 115.43 116.46 116.16 115.98 114.79 115.54 143.47 116.05 114.51
114.30 114.05 116.53 114.94 116.14 114.36 115.20 116.14 114.44 114.59
115.21 116.52 115.05 115.46 142.79 115.40 115.38 115.86 116.01 158.22
116.71 115.96 114.29 157.84 114.39 116.35 115.03 145.90 115.98 115.46
115.18 154.46 115.92 114.42 158.27 116.55 115.63 141.09 117.86 114.43
114.64 115.40 115.35 115.69 114.95 113.92 114.24 115.99 142.13 140.34
117.66 115.95 114.79 142.32 116.00 115.16 116.29 148.17 115.36 114.55
114.79 114.91 115.72 115.40 114.41 116.21 142.22 114.73 115.03 114.00
113.40 112.66 111.54 111.81 111.77 115.92 112.58 111.83 112.47 111.97
112.55 112.13 110.51 112.02 144.23 116.75 113.79 113.02 113.13 112.45
```

## 执行计划

### 约 10 万（105,998 公理）

#### 重建后立即（刷新前）

```
CTE Scan on descendants  (cost=96.15..96.37 rows=11 width=32) (actual time=0.034..6401.788 rows=1364.00 loops=1)
  Storage: Memory  Maximum Storage: 112kB
  Buffers: shared hit=167132
  CTE descendants
    ->  Recursive Union  (cost=0.41..96.15 rows=11 width=32) (actual time=0.033..6400.974 rows=1364.00 loops=1)
          Storage: Memory  Maximum Storage: 123kB
          Buffers: shared hit=167132
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.41..8.44 rows=1 width=32) (actual time=0.031..0.037 rows=4.00 loops=1)
                Index Cond: ((project_id = 'd9119361-e8af-4f92-a2ec-d565501d2372'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C1637'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.41..8.76 rows=1 width=32) (actual time=923.927..1279.773 rows=272.00 loops=5)
                Join Filter: (e.parent_iri = d.iri)
                Rows Removed by Join Filter: 9092698
                Buffers: shared hit=167125
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.41..8.43 rows=1 width=64) (actual time=0.015..29.589 rows=33332.00 loops=5)
                      Index Cond: ((project_id = 'd9119361-e8af-4f92-a2ec-d565501d2372'::uuid) AND (hierarchy = 'CLASS'::text))
                      Index Searches: 5
                      Buffers: shared hit=167125
                ->  WorkTable Scan on descendants d  (cost=0.00..0.20 rows=10 width=32) (actual time=0.000..0.013 rows=272.80 loops=166660)
Planning:
  Buffers: shared hit=16
Planning Time: 0.214 ms
Execution Time: 6401.976 ms
```

#### 刷新并预热后（定制计划）

```
CTE Scan on descendants  (cost=5040.80..5072.88 rows=1604 width=32) (actual time=0.036..23.317 rows=1364.00 loops=1)
  Storage: Memory  Maximum Storage: 112kB
  Buffers: shared hit=5457
  CTE descendants
    ->  Recursive Union  (cost=0.41..5040.80 rows=1604 width=43) (actual time=0.035..22.981 rows=1364.00 loops=1)
          Storage: Memory  Maximum Storage: 123kB
          Buffers: shared hit=5457
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.41..13.75 rows=4 width=43) (actual time=0.032..0.039 rows=4.00 loops=1)
                Index Cond: ((project_id = 'd9119361-e8af-4f92-a2ec-d565501d2372'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C1637'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.41..501.10 rows=160 width=43) (actual time=3.185..4.456 rows=272.00 loops=5)
                Buffers: shared hit=5450
                ->  WorkTable Scan on descendants d  (cost=0.00..0.80 rows=40 width=32) (actual time=0.000..0.022 rows=272.80 loops=5)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.41..12.47 rows=4 width=86) (actual time=0.015..0.016 rows=1.00 loops=1364)
                      Index Cond: ((project_id = 'd9119361-e8af-4f92-a2ec-d565501d2372'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 1364
                      Buffers: shared hit=5450
Settings: plan_cache_mode = 'force_custom_plan'
Planning Time: 0.237 ms
Execution Time: 23.407 ms
```

#### 刷新并预热后（通用计划）

```
CTE Scan on descendants  (cost=5040.80..5072.88 rows=1604 width=32) (actual time=0.030..23.478 rows=1364.00 loops=1)
  Storage: Memory  Maximum Storage: 112kB
  Buffers: shared hit=5457
  CTE descendants
    ->  Recursive Union  (cost=0.41..5040.80 rows=1604 width=43) (actual time=0.029..23.113 rows=1364.00 loops=1)
          Storage: Memory  Maximum Storage: 123kB
          Buffers: shared hit=5457
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.41..13.75 rows=4 width=43) (actual time=0.027..0.032 rows=4.00 loops=1)
                Index Cond: ((project_id = $1) AND (hierarchy = 'CLASS'::text) AND (parent_iri = $2))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.41..501.10 rows=160 width=43) (actual time=3.200..4.487 rows=272.00 loops=5)
                Buffers: shared hit=5450
                ->  WorkTable Scan on descendants d  (cost=0.00..0.80 rows=40 width=32) (actual time=0.000..0.026 rows=272.80 loops=5)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.41..12.47 rows=4 width=86) (actual time=0.015..0.016 rows=1.00 loops=1364)
                      Index Cond: ((project_id = $3) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 1364
                      Buffers: shared hit=5450
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.239 ms
Execution Time: 23.576 ms
```

### 约 50 万（506,000 公理）

#### 重建后立即（刷新前）

```
CTE Scan on descendants  (cost=70.77..70.99 rows=11 width=32) (actual time=0.034..1443.300 rows=5460.00 loops=1)
  Storage: Memory  Maximum Storage: 448kB
  Buffers: shared hit=1004443, temp read=12476 written=12476
  CTE descendants
    ->  Recursive Union  (cost=0.42..70.77 rows=11 width=43) (actual time=0.033..1441.796 rows=5460.00 loops=1)
          Storage: Memory  Maximum Storage: 441kB
          Buffers: shared hit=1004443, temp read=12476 written=12476
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.42..6.19 rows=1 width=43) (actual time=0.031..0.037 rows=4.00 loops=1)
                Index Cond: ((project_id = 'ecb71f54-1f9b-4637-b67e-7c78064d967d'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C107154'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Hash Join  (cost=6.20..6.45 rows=1 width=43) (actual time=213.830..239.160 rows=909.33 loops=6)
                Hash Cond: (d.iri = e.parent_iri)
                Buffers: shared hit=1004436, temp read=12476 written=12476
                ->  WorkTable Scan on descendants d  (cost=0.00..0.20 rows=10 width=32) (actual time=0.001..0.059 rows=910.00 loops=6)
                ->  Hash  (cost=6.19..6.19 rows=1 width=86) (actual time=208.460..208.460 rows=166666.00 loops=6)
                      Buckets: 65536 (originally 1024)  Batches: 4 (originally 1)  Memory Usage: 7681kB
                      Buffers: shared hit=1004436, temp written=9900
                      ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.42..6.19 rows=1 width=86) (actual time=0.028..124.433 rows=166666.00 loops=6)
                            Index Cond: ((project_id = 'ecb71f54-1f9b-4637-b67e-7c78064d967d'::uuid) AND (hierarchy = 'CLASS'::text))
                            Index Searches: 6
                            Buffers: shared hit=1004436
Planning:
  Buffers: shared hit=4 read=1
Planning Time: 0.257 ms
Execution Time: 1446.130 ms
```

#### 刷新并预热后（定制计划）

```
CTE Scan on descendants  (cost=5795.69..5823.97 rows=1414 width=32) (actual time=0.036..111.537 rows=5460.00 loops=1)
  Storage: Memory  Maximum Storage: 448kB
  Buffers: shared hit=21839
  CTE descendants
    ->  Recursive Union  (cost=0.42..5795.69 rows=1414 width=44) (actual time=0.036..110.053 rows=5460.00 loops=1)
          Storage: Memory  Maximum Storage: 441kB
          Buffers: shared hit=21839
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.42..14.51 rows=4 width=44) (actual time=0.034..0.041 rows=4.00 loops=1)
                Index Cond: ((project_id = 'ecb71f54-1f9b-4637-b67e-7c78064d967d'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C107154'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.42..576.70 rows=141 width=44) (actual time=12.720..17.901 rows=909.33 loops=6)
                Buffers: shared hit=21832
                ->  WorkTable Scan on descendants d  (cost=0.00..0.80 rows=40 width=32) (actual time=0.000..0.103 rows=910.00 loops=6)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.42..14.36 rows=4 width=88) (actual time=0.018..0.019 rows=1.00 loops=5460)
                      Index Cond: ((project_id = 'ecb71f54-1f9b-4637-b67e-7c78064d967d'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 5460
                      Buffers: shared hit=21832
Settings: plan_cache_mode = 'force_custom_plan'
Planning Time: 0.233 ms
Execution Time: 111.813 ms
```

#### 刷新并预热后（通用计划）

```
CTE Scan on descendants  (cost=2115.52..2123.96 rows=422 width=32) (actual time=0.031..110.419 rows=5460.00 loops=1)
  Storage: Memory  Maximum Storage: 448kB
  Buffers: shared hit=21839
  CTE descendants
    ->  Recursive Union  (cost=0.42..2115.52 rows=422 width=44) (actual time=0.030..109.055 rows=5460.00 loops=1)
          Storage: Memory  Maximum Storage: 441kB
          Buffers: shared hit=21839
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.42..10.46 rows=2 width=44) (actual time=0.028..0.033 rows=4.00 loops=1)
                Index Cond: ((project_id = $1) AND (hierarchy = 'CLASS'::text) AND (parent_iri = $2))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.42..210.08 rows=42 width=44) (actual time=12.686..17.759 rows=909.33 loops=6)
                Buffers: shared hit=21832
                ->  WorkTable Scan on descendants d  (cost=0.00..0.40 rows=20 width=32) (actual time=0.000..0.078 rows=910.00 loops=6)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.42..10.46 rows=2 width=88) (actual time=0.018..0.019 rows=1.00 loops=5460)
                      Index Cond: ((project_id = $3) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 5460
                      Buffers: shared hit=21832
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.278 ms
Execution Time: 110.719 ms
```

