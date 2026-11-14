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
| plan_cache_mode | auto |
| work_mem | 4MB |
| effective_cache_size | 4GB |
| jit | on |
| random_page_cost | 4 |
| jit_optimize_above_cost | 500000 |
| autovacuum_naptime | 1min |
| jit_inline_above_cost | 500000 |
| jit_above_cost | 100000 |
| shared_buffers | 128MB |
| pgjdbc prepareThreshold | 5 |

## 结果

| 规模 | 场景 | 测量值 | 门禁 | 判定 |
| --- | --- | --- | --- | --- |
| 约 10 万（105,998 公理） | ProjectionRows.compute (a) | 0.362 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | ProjectionWriter.replaceProject (b) | 4.367 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 统计信息刷新 (b2) | 0.728 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 全量重建 (a+b+b2) | 5.456 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 第二次重建统计信息刷新 (b2) | 0.711 秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 p50 | 24.95 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 p95 | 46.38 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 p99 | 3292.62 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 刷新前 max | 3478.11 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 p50 | 24.74 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 p95 | 35.77 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 p99 | 48.55 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 后代 max | 49.94 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 冷样本 max | 39.78 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 冷样本中位数 | 24.33 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 prepared custom_plans / generic_plans | 5 / 105 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q1 返回行数 | 1,364 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 p50 | 0.67 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 p95 | 1.04 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 p99 | 1.14 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 标签前缀 max | 1.50 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 冷样本 max | 1.90 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 冷样本中位数 | 0.80 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q2 返回行数 | 20 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 p50 | 0.51 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 p95 | 0.87 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 p99 | 1.00 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 定义域/值域 max | 1.05 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 冷样本 max | 2.08 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 冷样本中位数 | 0.84 毫秒 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | Q3 返回行数 | 2 | 无 | 无门禁 |
| 约 10 万（105,998 公理） | 两次重建 md5 一致 (e) | 相同 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | ProjectionRows.compute (a) | 0.984 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | ProjectionWriter.replaceProject (b) | 21.236 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | 统计信息刷新 (b2) | 2.203 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | 全量重建 (a+b+b2) | 24.423 秒 | ≤ 60 秒 | 达标 |
| 约 50 万（506,000 公理） | 第二次重建统计信息刷新 (b2) | 2.334 秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 p50 | 116.31 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 p95 | 1452.25 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 p99 | 1517.63 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 刷新前 max | 1552.49 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 后代 p50 | 113.62 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 后代 p95 | 151.10 毫秒 | ≤ 200 毫秒 | 达标 |
| 约 50 万（506,000 公理） | Q1 后代 p99 | 160.02 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 后代 max | 167.67 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 冷样本 max | 143.57 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 冷样本中位数 | 111.62 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 prepared custom_plans / generic_plans | 5 / 105 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q1 返回行数 | 5,460 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 标签前缀 p50 | 0.65 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 标签前缀 p95 | 1.07 毫秒 | ≤ 200 毫秒 | 达标 |
| 约 50 万（506,000 公理） | Q2 标签前缀 p99 | 1.28 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 标签前缀 max | 1.64 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 冷样本 max | 5.00 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 冷样本中位数 | 0.77 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q2 返回行数 | 20 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 p50 | 0.54 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 p95 | 1.20 毫秒 | ≤ 200 毫秒 | 达标 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 p99 | 1.80 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 定义域/值域 max | 2.39 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 冷样本 max | 1.51 毫秒 | 无 | 无门禁 |
| 约 50 万（506,000 公理） | Q3 冷样本中位数 | 0.49 毫秒 | 无 | 无门禁 |
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
| 约 50 万（506,000 公理） | 1.0 | {31429559-11f5-4f4a-8946-ad75246a4bfb} | {1} |

## 门禁结论

初始通过标准只看约 50 万公理规模。约 10 万规模用同一测法对照，不单独作为门槛。

**门禁语义（P6-18）**：全量重建为首次重建的 (a + b + b2)；查询门禁用刷新统计信息并预热之后的 100 个样本判定。刷新前采样与冷样本只记录，供人对照；是否接受该语义由人在 P6-11 重跑时确认。

| 项 | 测量值 | 判定 |
| --- | --- | --- |
| 全量重建 (a+b+b2) | 24.423 秒 | 达标 |
| Q1 p95（预热后） | 151.10 毫秒 | 达标 |
| Q1 刷新前 p95 | 1452.25 毫秒 | 只记录 |
| Q1 冷样本 max / 中位数 | 143.57 毫秒 / 111.62 毫秒 | 只记录 |
| Q2 p95（预热后） | 1.07 毫秒 | 达标 |
| Q3 p95（预热后） | 1.20 毫秒 | 达标 |
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
| Q1 刷新前 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | 575 | 33333.0 | 574 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0 |
| Q1 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=1 Δms=15 |
| Q2 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0 |
| Q3 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0 |

刷新前各表 last_autoanalyze：entities=null, labels=null, hierarchy_edges=null, property_domains=null, property_ranges=null

### 约 50 万（506,000 公理）

| 查询 | Δautovacuum | Δautoanalyze | Δrelpages | Δreltuples | Δrelallvisible | GC |
| --- | --- | --- | --- | --- | --- | --- |
| Q1 刷新前 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | entities:1, labels:1, hierarchy_edges:1, property_domains:1, property_ranges:1 | 2299 | 166666.0 | 2297 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0 |
| Q1 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=1 Δms=23 |
| Q2 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0 |
| Q3 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | entities:0, labels:0, hierarchy_edges:0, property_domains:0, property_ranges:0 | 0 | 0.0 | 0 | G1 Concurrent GC Δcount=0 Δms=0; G1 Old Generation Δcount=0 Δms=0; G1 Young Generation Δcount=0 Δms=0 |

刷新前各表 last_autoanalyze：entities=2026-11-14T15:33:52.936631Z, labels=2026-11-14T15:33:53.248236Z, hierarchy_edges=2026-11-14T15:33:53.757550Z, property_domains=2026-11-14T15:33:53.765217Z, property_ranges=2026-11-14T15:33:52.797707Z

## 附录：Q1 样本序列

耗时单位：毫秒；每行 10 个样本。慢样本连成一段通常表示计划切换；零散分布通常表示干扰。

### 约 10 万（105,998 公理） — 刷新前

采样开始：2026-11-14T15:33:47.113536010Z；结束：2026-11-14T15:33:59.858420838Z

```
3292.62 3280.35 3478.11 37.50 34.47 47.54 31.06 26.33 31.83 43.36
25.60 47.48 31.18 31.12 31.94 31.15 31.17 31.28 31.33 31.10
32.22 31.04 25.88 31.97 32.14 31.19 25.35 25.61 25.06 30.92
25.15 24.91 30.92 31.28 32.04 31.73 30.99 31.72 31.20 30.71
31.83 30.50 26.03 24.54 24.00 25.60 24.98 23.91 23.82 24.92
24.46 24.21 24.42 27.21 24.77 24.07 24.04 24.69 24.04 24.72
24.10 24.00 24.47 25.54 24.92 24.71 24.35 24.55 23.89 24.28
24.26 24.06 30.75 24.95 24.14 23.95 24.00 24.09 23.44 23.55
24.63 24.45 24.03 24.32 24.22 46.38 27.87 24.11 23.76 23.70
24.23 23.79 24.05 42.23 23.91 24.00 24.48 23.92 23.92 24.10
```

### 约 10 万（105,998 公理） — 刷新并预热后

采样开始：2026-11-14T15:34:00.889151086Z；结束：2026-11-14T15:34:03.649119038Z

```
24.69 24.14 23.54 23.86 24.28 23.55 23.69 39.67 25.02 24.24
24.93 24.66 24.74 24.07 24.61 24.03 24.17 24.17 23.86 23.93
23.97 24.26 23.45 23.68 23.96 24.19 24.20 23.89 31.34 24.14
31.03 24.46 24.25 24.25 23.78 24.05 23.85 32.86 36.26 48.55
31.77 31.12 31.69 32.39 32.35 49.94 32.14 32.79 32.73 31.69
32.08 32.63 32.60 31.62 31.83 25.50 24.58 24.16 37.48 25.07
35.77 25.04 24.55 24.65 24.61 25.07 32.30 24.82 24.75 24.83
25.26 24.67 24.22 24.15 24.55 24.16 24.69 24.97 24.25 23.82
30.29 24.74 23.91 24.12 25.34 24.56 24.17 30.80 31.14 24.66
30.88 31.15 30.32 30.72 31.26 30.94 30.74 30.69 30.44 30.96
```

### 约 50 万（506,000 公理） — 刷新前

采样开始：2026-11-14T15:35:09.053556447Z；结束：2026-11-14T15:36:05.356681973Z

```
1552.49 1411.58 1342.37 1428.82 1517.63 1430.42 1415.58 1470.43 1328.72 1441.12
1461.89 1351.93 1483.94 1452.25 1348.81 1328.08 1387.15 1314.44 1327.09 1317.22
1328.36 1332.60 1315.22 1322.67 1326.06 1329.81 1322.68 1315.76 1326.96 1428.61
1436.38 1374.13 1435.48 1431.20 1430.89 113.82 110.28 112.21 113.05 113.94
112.91 113.13 116.54 112.76 114.37 112.70 112.11 113.42 116.31 115.42
113.44 135.37 112.90 114.12 113.19 114.32 113.59 155.22 144.02 143.54
141.48 123.13 109.98 111.89 111.51 111.07 143.50 133.36 111.37 135.42
156.76 144.74 145.90 134.65 114.07 114.45 113.73 113.46 113.00 113.88
113.51 112.36 113.85 114.07 115.37 114.35 114.59 112.71 114.53 113.74
113.39 112.10 112.76 115.00 113.00 112.60 113.14 113.45 112.89 116.72
```

### 约 50 万（506,000 公理） — 刷新并预热后

采样开始：2026-11-14T15:36:08.818453645Z；结束：2026-11-14T15:36:21.069337963Z

```
111.42 112.94 112.76 111.64 112.24 113.11 113.39 113.57 112.30 112.14
112.17 112.85 113.05 123.59 112.32 111.76 111.77 112.68 111.46 111.36
112.28 113.31 112.76 112.39 112.60 111.74 112.42 111.61 112.03 111.90
115.83 113.62 113.16 112.72 112.60 131.94 139.91 149.91 145.48 146.14
140.77 113.14 151.51 117.37 113.91 112.64 113.67 113.91 114.90 117.36
151.10 143.00 144.19 127.77 121.91 114.28 114.47 132.01 143.62 143.69
118.36 111.84 111.64 112.76 158.94 144.04 142.85 139.06 111.85 110.85
111.85 110.10 110.11 111.54 112.46 146.37 143.87 143.27 128.45 112.60
112.10 111.89 112.79 112.15 160.02 116.37 115.23 115.34 157.92 116.07
114.91 116.78 142.97 120.69 146.81 141.04 134.81 116.26 117.77 167.67
```

## 执行计划

### 约 10 万（105,998 公理）

#### 重建后立即（刷新前）

```
CTE Scan on descendants  (cost=96.15..96.37 rows=11 width=32) (actual time=0.030..6323.993 rows=1364.00 loops=1)
  Storage: Memory  Maximum Storage: 112kB
  Buffers: shared hit=167132
  CTE descendants
    ->  Recursive Union  (cost=0.41..96.15 rows=11 width=32) (actual time=0.029..6323.264 rows=1364.00 loops=1)
          Storage: Memory  Maximum Storage: 123kB
          Buffers: shared hit=167132
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.41..8.44 rows=1 width=32) (actual time=0.028..0.044 rows=4.00 loops=1)
                Index Cond: ((project_id = '31429559-11f5-4f4a-8946-ad75246a4bfb'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C1637'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.41..8.76 rows=1 width=32) (actual time=934.603..1264.310 rows=272.00 loops=5)
                Join Filter: (e.parent_iri = d.iri)
                Rows Removed by Join Filter: 9092698
                Buffers: shared hit=167125
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.41..8.43 rows=1 width=64) (actual time=0.013..29.550 rows=33332.00 loops=5)
                      Index Cond: ((project_id = '31429559-11f5-4f4a-8946-ad75246a4bfb'::uuid) AND (hierarchy = 'CLASS'::text))
                      Index Searches: 5
                      Buffers: shared hit=167125
                ->  WorkTable Scan on descendants d  (cost=0.00..0.20 rows=10 width=32) (actual time=0.000..0.014 rows=272.80 loops=166660)
Planning:
  Buffers: shared hit=16
Planning Time: 0.195 ms
Execution Time: 6324.150 ms
```

#### 刷新并预热后（定制计划）

```
CTE Scan on descendants  (cost=5040.80..5072.88 rows=1604 width=32) (actual time=0.032..23.684 rows=1364.00 loops=1)
  Storage: Memory  Maximum Storage: 112kB
  Buffers: shared hit=5457
  CTE descendants
    ->  Recursive Union  (cost=0.41..5040.80 rows=1604 width=43) (actual time=0.031..23.344 rows=1364.00 loops=1)
          Storage: Memory  Maximum Storage: 123kB
          Buffers: shared hit=5457
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.41..13.75 rows=4 width=43) (actual time=0.029..0.035 rows=4.00 loops=1)
                Index Cond: ((project_id = '31429559-11f5-4f4a-8946-ad75246a4bfb'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C1637'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.41..501.10 rows=160 width=43) (actual time=3.198..4.528 rows=272.00 loops=5)
                Buffers: shared hit=5450
                ->  WorkTable Scan on descendants d  (cost=0.00..0.80 rows=40 width=32) (actual time=0.000..0.027 rows=272.80 loops=5)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.41..12.47 rows=4 width=86) (actual time=0.015..0.016 rows=1.00 loops=1364)
                      Index Cond: ((project_id = '31429559-11f5-4f4a-8946-ad75246a4bfb'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 1364
                      Buffers: shared hit=5450
Settings: plan_cache_mode = 'force_custom_plan'
Planning Time: 0.261 ms
Execution Time: 23.773 ms
```

#### 刷新并预热后（通用计划）

```
CTE Scan on descendants  (cost=5040.80..5072.88 rows=1604 width=32) (actual time=0.029..23.373 rows=1364.00 loops=1)
  Storage: Memory  Maximum Storage: 112kB
  Buffers: shared hit=5457
  CTE descendants
    ->  Recursive Union  (cost=0.41..5040.80 rows=1604 width=43) (actual time=0.028..23.026 rows=1364.00 loops=1)
          Storage: Memory  Maximum Storage: 123kB
          Buffers: shared hit=5457
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.41..13.75 rows=4 width=43) (actual time=0.027..0.031 rows=4.00 loops=1)
                Index Cond: ((project_id = $1) AND (hierarchy = 'CLASS'::text) AND (parent_iri = $2))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.41..501.10 rows=160 width=43) (actual time=3.199..4.479 rows=272.00 loops=5)
                Buffers: shared hit=5450
                ->  WorkTable Scan on descendants d  (cost=0.00..0.80 rows=40 width=32) (actual time=0.000..0.031 rows=272.80 loops=5)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.41..12.47 rows=4 width=86) (actual time=0.015..0.016 rows=1.00 loops=1364)
                      Index Cond: ((project_id = $3) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 1364
                      Buffers: shared hit=5450
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.239 ms
Execution Time: 23.466 ms
```

### 约 50 万（506,000 公理）

#### 重建后立即（刷新前）

```
CTE Scan on descendants  (cost=70.77..70.99 rows=11 width=32) (actual time=0.045..1569.731 rows=5460.00 loops=1)
  Storage: Memory  Maximum Storage: 448kB
  Buffers: shared hit=1004443, temp read=12476 written=12476
  CTE descendants
    ->  Recursive Union  (cost=0.42..70.77 rows=11 width=43) (actual time=0.044..1568.458 rows=5460.00 loops=1)
          Storage: Memory  Maximum Storage: 441kB
          Buffers: shared hit=1004443, temp read=12476 written=12476
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.42..6.19 rows=1 width=43) (actual time=0.041..0.048 rows=4.00 loops=1)
                Index Cond: ((project_id = 'd9f01ab1-5a4f-4cca-bf7c-ad59d5792e7b'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C107154'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Hash Join  (cost=6.20..6.45 rows=1 width=43) (actual time=234.976..260.313 rows=909.33 loops=6)
                Hash Cond: (d.iri = e.parent_iri)
                Buffers: shared hit=1004436, temp read=12476 written=12476
                ->  WorkTable Scan on descendants d  (cost=0.00..0.20 rows=10 width=32) (actual time=0.001..0.056 rows=910.00 loops=6)
                ->  Hash  (cost=6.19..6.19 rows=1 width=86) (actual time=229.819..229.819 rows=166666.00 loops=6)
                      Buckets: 65536 (originally 1024)  Batches: 4 (originally 1)  Memory Usage: 7681kB
                      Buffers: shared hit=1004436, temp written=9900
                      ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.42..6.19 rows=1 width=86) (actual time=0.030..140.614 rows=166666.00 loops=6)
                            Index Cond: ((project_id = 'd9f01ab1-5a4f-4cca-bf7c-ad59d5792e7b'::uuid) AND (hierarchy = 'CLASS'::text))
                            Index Searches: 6
                            Buffers: shared hit=1004436
Planning:
  Buffers: shared hit=4 read=1 written=1
Planning Time: 0.389 ms
Execution Time: 1572.425 ms
```

#### 刷新并预热后（定制计划）

```
CTE Scan on descendants  (cost=5783.45..5811.73 rows=1414 width=32) (actual time=0.052..110.473 rows=5460.00 loops=1)
  Storage: Memory  Maximum Storage: 448kB
  Buffers: shared hit=21839
  CTE descendants
    ->  Recursive Union  (cost=0.42..5783.45 rows=1414 width=44) (actual time=0.050..109.040 rows=5460.00 loops=1)
          Storage: Memory  Maximum Storage: 441kB
          Buffers: shared hit=21839
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.42..14.48 rows=4 width=44) (actual time=0.048..0.056 rows=4.00 loops=1)
                Index Cond: ((project_id = 'd9f01ab1-5a4f-4cca-bf7c-ad59d5792e7b'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = 'http://example.org/wp-test/synthetic#C107154'::text))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.42..575.48 rows=141 width=44) (actual time=12.681..17.747 rows=909.33 loops=6)
                Buffers: shared hit=21832
                ->  WorkTable Scan on descendants d  (cost=0.00..0.80 rows=40 width=32) (actual time=0.000..0.078 rows=910.00 loops=6)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.42..14.33 rows=4 width=88) (actual time=0.018..0.019 rows=1.00 loops=5460)
                      Index Cond: ((project_id = 'd9f01ab1-5a4f-4cca-bf7c-ad59d5792e7b'::uuid) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 5460
                      Buffers: shared hit=21832
Settings: plan_cache_mode = 'force_custom_plan'
Planning Time: 0.261 ms
Execution Time: 110.779 ms
```

#### 刷新并预热后（通用计划）

```
CTE Scan on descendants  (cost=2113.42..2121.86 rows=422 width=32) (actual time=0.038..114.777 rows=5460.00 loops=1)
  Storage: Memory  Maximum Storage: 448kB
  Buffers: shared hit=21839
  CTE descendants
    ->  Recursive Union  (cost=0.42..2113.42 rows=422 width=44) (actual time=0.037..113.281 rows=5460.00 loops=1)
          Storage: Memory  Maximum Storage: 441kB
          Buffers: shared hit=21839
          ->  Index Scan using hierarchy_edges_parent on hierarchy_edges  (cost=0.42..10.45 rows=2 width=44) (actual time=0.035..0.041 rows=4.00 loops=1)
                Index Cond: ((project_id = $1) AND (hierarchy = 'CLASS'::text) AND (parent_iri = $2))
                Index Searches: 1
                Buffers: shared hit=7
          ->  Nested Loop  (cost=0.42..209.87 rows=42 width=44) (actual time=13.188..18.467 rows=909.33 loops=6)
                Buffers: shared hit=21832
                ->  WorkTable Scan on descendants d  (cost=0.00..0.40 rows=20 width=32) (actual time=0.000..0.077 rows=910.00 loops=6)
                ->  Index Scan using hierarchy_edges_parent on hierarchy_edges e  (cost=0.42..10.45 rows=2 width=88) (actual time=0.019..0.020 rows=1.00 loops=5460)
                      Index Cond: ((project_id = $3) AND (hierarchy = 'CLASS'::text) AND (parent_iri = d.iri))
                      Index Searches: 5460
                      Buffers: shared hit=21832
Settings: plan_cache_mode = 'force_generic_plan'
Planning Time: 0.270 ms
Execution Time: 115.103 ms
```

