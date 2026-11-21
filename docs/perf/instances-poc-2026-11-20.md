# 实例库 PoC 基准（2026-11-20）

测量 P6-31 `InstanceStore` / `TraceQueries` 与 Q4/Q5 区间连接 SQL。本卡只测量，不做优化。

## 机器配置

| 项 | 值 |
| --- | --- |
| CPU | Intel(R) Xeon(R) CPU E5-2673 v3 @ 2.40GHz |
| 逻辑处理器 | 8 |
| 内存 | 32814444 kB |
| JDK | 25.0.5.0.3 (OpenJDK 64-Bit Server VM) |
| -Xmx | -Xmx6g |
| PostgreSQL | 18.6 (Debian 18.6-1.pgdg13+2) (Testcontainers postgres:18) |
| autovacuum_naptime | 1min |
| work_mem | 4MB |
| random_page_cost | 4 |
| effective_cache_size | 4GB |
| jit | on |
| shared_buffers | 128MB |

## 语料

| 项 | 值 |
| --- | --- |
| 模式 | 全量 |
| 货位 | 18,000 |
| 传感器 | 600 |
| 堆垛机 / AGV | 5 / 8 |
| 批次 / 托盘 | 20,000 / 60,000 |
| 任务 | 900,000（每天 30,000 × 30 天） |
| 环境事件 | 1,000,000 |
| COPY 耗时 | 329.815 秒 |
| instances 行数 / 体积 | 1,998,613 / 754 MB |
| links 行数 / 体积 | 3,098,610 / 2174 MB |

### 采样参数（固定种子）

```
SampleIris[slotIri=http://wp.local/wh/Slot/756, batchIri=http://wp.local/wh/Batch/15051, stackerIri=http://wp.local/wh/Stacker/2, taskDay=8, deepBatchIri=http://wp.local/wh/Batch/0, excursionEventIri=http://wp.local/wh/EnvEvent/0]
```

## 查询结果

| 查询 | p50 | p95 | p99 | max | 行数（两次） | 门禁 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q1 | 22.79 毫秒 | 32.36 毫秒 | 34.97 毫秒 | 35.10 毫秒 | 1 / 1 | ≤ 200 毫秒 | 达标 |
| Q2 | 87.00 毫秒 | 102.09 毫秒 | 113.91 毫秒 | 115.00 毫秒 | 3 / 3 | ≤ 200 毫秒 | 达标 |
| Q3 | 93659.41 毫秒 | 94403.54 毫秒 | 94746.41 毫秒 | 95000.69 毫秒 | 2308 / 2308 | ≤ 200 毫秒 | 未达标 |
| Q4 | 129.94 毫秒 | 146.84 毫秒 | 149.60 毫秒 | 159.26 毫秒 | 21 / 21 | ≤ 1000 毫秒 | 达标 |
| Q5 | 26.85 毫秒 | 39.21 毫秒 | 40.97 毫秒 | 41.60 毫秒 | 4 / 4 | ≤ 1000 毫秒 | 达标 |

### W1 写入

| 项 | 值 | 门禁 | 判定 |
| --- | --- | --- | --- |
| 持续 | 60000 ms | — | — |
| 操作数 | 10,063 | — | — |
| 吞吐 | 167.6 次/秒 | ≥ 200 | 未达标 |
| 单次 p95 | 93.66 毫秒 | ≤ 50 毫秒 | 未达标 |

## 门禁结论

| 项 | 测量值 | 判定 |
| --- | --- | --- |
| Q1 p95 | 32.36 毫秒 | 达标 |
| Q2 p95 | 102.09 毫秒 | 达标 |
| Q3 p95 | 94403.54 毫秒 | 未达标 |
| Q4 p95 | 146.84 毫秒 | 达标 |
| Q5 p95 | 39.21 毫秒 | 达标 |
| W1 吞吐 | 167.6 次/秒 | 未达标 |
| W1 p95 | 93.66 毫秒 | 未达标 |
| 两次行数一致 | 相同 | 达标 |

结论：存在未达标项：Q3 p95 94403.54 毫秒 > 200 毫秒；W1 吞吐 167.6 < 200 次/秒；W1 p95 93.66 毫秒 > 50 毫秒。

## 测法

- 造数：`WarehouseCorpus`，种子 42，PostgreSQL `COPY`；写入后 `ANALYZE`。
- Q1 ~ Q3：`TraceQueries`；每条查询预热 20 次、采样 100 次。
- Q4 / Q5：基准内命名 SQL 常量（全文见下）；p50/p95/p99/max 用最近秩。
- W1：8 线程，upsert:unlink+link = 7:3，持续 60 秒。
- 造数后跑两轮查询，断言各查询返回行数相同。

## SQL

### Q4_DEEP_TRACE_SQL

```sql
with trays as (
    select l.object_iri as tray_iri
    from links l
    where l.subject_iri = ?
      and l.property_iri = ?
      and l.valid_from <= ?
      and (l.valid_to is null or l.valid_to > ?)
),
placements as (
    select s.tray_iri,
           l.object_iri as slot_iri,
           l.valid_from as stay_from,
           l.valid_to as stay_to
    from trays s
    join links l
      on l.subject_iri = s.tray_iri
     and l.property_iri = ?
),
monitored as (
    select p.tray_iri,
           p.slot_iri,
           p.stay_from,
           p.stay_to,
           m.subject_iri as sensor_iri
    from placements p
    join links m
      on m.object_iri = p.slot_iri
     and m.property_iri = ?
     and m.valid_from <= p.stay_from
     and (m.valid_to is null or m.valid_to > p.stay_from)
)
select distinct e.iri as event_iri,
       m.sensor_iri,
       m.slot_iri,
       m.tray_iri
from monitored m
join links o
  on o.object_iri = m.sensor_iri
 and o.property_iri = ?
join instances e
  on e.iri = o.subject_iri
 and e.class_iri = ?
 and (e.attributes->>'excursion') = 'true'
 and e.valid_from >= m.stay_from
 and (m.stay_to is null or e.valid_from < m.stay_to)

```

### Q5_REVERSE_TRACE_SQL

```sql
with event as (
    select i.iri as event_iri,
           i.valid_from as event_at,
           o.object_iri as sensor_iri
    from instances i
    join links o
      on o.subject_iri = i.iri
     and o.property_iri = ?
    where i.iri = ?
),
slot as (
    select e.event_iri,
           e.event_at,
           e.sensor_iri,
           m.object_iri as slot_iri
    from event e
    join links m
      on m.subject_iri = e.sensor_iri
     and m.property_iri = ?
     and m.valid_from <= e.event_at
     and (m.valid_to is null or m.valid_to > e.event_at)
),
trays as (
    select s.event_iri,
           s.event_at,
           s.sensor_iri,
           s.slot_iri,
           st.subject_iri as tray_iri
    from slot s
    join links st
      on st.object_iri = s.slot_iri
     and st.property_iri = ?
     and st.valid_from <= s.event_at
     and (st.valid_to is null or st.valid_to > s.event_at)
)
select c.subject_iri as batch_iri,
       tr.tray_iri,
       tr.slot_iri,
       tr.sensor_iri
from trays tr
join links c
  on c.object_iri = tr.tray_iri
 and c.property_iri = ?
 and c.valid_from <= tr.event_at
 and (c.valid_to is null or c.valid_to > tr.event_at)

```

## 执行计划摘要

### Q1（1 跳 representative）

```
Index Scan using links_history_object_property on links  (cost=0.56..38.97 rows=3 width=32) (actual time=0.347..0.409 rows=1.00 loops=1)
  Index Cond: ((object_iri = 'http://wp.local/wh/Slot/756'::text) AND (property_iri = 'http://wp.local/wh#storedAt'::text) AND (valid_from <= '2026-01-31 07:59:59+08'::timestamp with time zone))
  Filter: ((valid_to IS NULL) OR (valid_to > '2026-01-31 07:59:59+08'::timestamp with time zone))
  Rows Removed by Filter: 9
  Index Searches: 1
  Buffers: shared hit=3 read=11
Planning:
  Buffers: shared hit=186
Planning Time: 1.151 ms
Execution Time: 0.461 ms
```

### Q4

```
Unique  (cost=208.60..208.62 rows=1 width=121) (actual time=78.173..78.186 rows=21.00 loops=1)
  Buffers: shared hit=20189
  ->  Sort  (cost=208.60..208.61 rows=1 width=121) (actual time=78.172..78.176 rows=21.00 loops=1)
        Sort Key: e.iri, m.subject_iri, l.object_iri, l_1.object_iri
        Sort Method: quicksort  Memory: 27kB
        Buffers: shared hit=20189
        ->  Nested Loop  (cost=2.77..208.59 rows=1 width=121) (actual time=0.217..78.022 rows=21.00 loops=1)
              Join Filter: ((e.valid_from >= l.valid_from) AND ((l.valid_to IS NULL) OR (e.valid_from < l.valid_to)))
              Rows Removed by Join Filter: 19
              Buffers: shared hit=20186
              ->  Nested Loop  (cost=2.22..207.71 rows=1 width=136) (actual time=0.139..7.031 rows=3344.00 loops=1)
                    Buffers: shared hit=3466
                    ->  Nested Loop  (cost=1.67..19.21 rows=1 width=104) (actual time=0.108..0.444 rows=2.00 loops=1)
                          Buffers: shared hit=72
                          ->  Nested Loop  (cost=1.11..17.16 rows=1 width=72) (actual time=0.076..0.131 rows=12.00 loops=1)
                                Buffers: shared hit=22
                                ->  Index Scan using links_history_subject_property on links l_1  (cost=0.56..8.58 rows=1 width=28) (actual time=0.040..0.045 rows=3.00 loops=1)
                                      Index Cond: ((subject_iri = 'http://wp.local/wh/Batch/0'::text) AND (property_iri = 'http://wp.local/wh#contains'::text) AND (valid_from <= '2026-01-31 07:59:59+08'::timestamp with time zone))
                                      Filter: ((valid_to IS NULL) OR (valid_to > '2026-01-31 07:59:59+08'::timestamp with time zone))
                                      Index Searches: 1
                                      Buffers: shared hit=7
                                ->  Index Scan using links_history_subject_property on links l  (cost=0.56..8.57 rows=1 width=76) (actual time=0.023..0.025 rows=4.00 loops=3)
                                      Index Cond: ((subject_iri = l_1.object_iri) AND (property_iri = 'http://wp.local/wh#storedAt'::text))
                                      Index Searches: 3
                                      Buffers: shared hit=15
                          ->  Index Scan using links_history_object_property on links m  (cost=0.56..2.04 rows=1 width=76) (actual time=0.025..0.025 rows=0.17 loops=12)
                                Index Cond: ((object_iri = l.object_iri) AND (property_iri = 'http://wp.local/wh#monitors'::text) AND (valid_from <= l.valid_from))
                                Filter: ((valid_to IS NULL) OR (valid_to > l.valid_from))
                                Index Searches: 12
                                Buffers: shared hit=50
                    ->  Index Scan using links_history_object_property on links o  (cost=0.56..187.91 rows=59 width=60) (actual time=0.029..3.041 rows=1672.00 loops=2)
                          Index Cond: ((object_iri = m.subject_iri) AND (property_iri = 'http://wp.local/wh#observedBy'::text))
                          Index Searches: 2
                          Buffers: shared hit=3394
              ->  Index Scan using instances_pkey on instances e  (cost=0.55..0.87 rows=1 width=41) (actual time=0.021..0.021 rows=0.01 loops=3344)
                    Index Cond: (iri = o.subject_iri)
                    Filter: ((class_iri = 'http://wp.local/wh#EnvEvent'::text) AND ((attributes ->> 'excursion'::text) = 'true'::text))
                    Rows Removed by Filter: 1
                    Index Searches: 3344
                    Buffers: shared hit=16720
Planning:
  Buffers: shared hit=435
Planning Time: 5.081 ms
Execution Time: 78.420 ms
```

### Q5

```
Nested Loop  (cost=2.77..58.15 rows=3 width=120) (actual time=0.311..0.390 rows=4.00 loops=1)
  Buffers: shared hit=43
  ->  Index Only Scan using instances_pkey on instances i  (cost=0.55..8.57 rows=1 width=41) (actual time=0.135..0.136 rows=1.00 loops=1)
        Index Cond: (iri = 'http://wp.local/wh/EnvEvent/0'::text)
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=5
  ->  Nested Loop  (cost=2.22..49.57 rows=1 width=200) (actual time=0.173..0.250 rows=4.00 loops=1)
        Buffers: shared hit=38
        ->  Nested Loop  (cost=1.67..40.98 rows=1 width=152) (actual time=0.137..0.143 rows=4.00 loops=1)
              Join Filter: ((m.valid_from <= i.valid_from) AND ((m.valid_to IS NULL) OR (m.valid_to > i.valid_from)))
              Buffers: shared hit=18
              ->  Nested Loop  (cost=1.11..17.16 rows=1 width=104) (actual time=0.095..0.096 rows=1.00 loops=1)
                    Buffers: shared hit=10
                    ->  Index Only Scan using links_pkey on links o  (cost=0.56..8.57 rows=1 width=60) (actual time=0.063..0.063 rows=1.00 loops=1)
                          Index Cond: ((subject_iri = 'http://wp.local/wh/EnvEvent/0'::text) AND (property_iri = 'http://wp.local/wh#observedBy'::text))
                          Heap Fetches: 0
                          Index Searches: 1
                          Buffers: shared hit=5
                    ->  Index Scan using links_history_subject_property on links m  (cost=0.56..8.57 rows=1 width=76) (actual time=0.028..0.029 rows=1.00 loops=1)
                          Index Cond: ((subject_iri = o.object_iri) AND (property_iri = 'http://wp.local/wh#monitors'::text))
                          Index Searches: 1
                          Buffers: shared hit=5
              ->  Index Scan using links_history_object_property on links st  (cost=0.56..23.77 rows=3 width=76) (actual time=0.040..0.044 rows=4.00 loops=1)
                    Index Cond: ((object_iri = m.object_iri) AND (property_iri = 'http://wp.local/wh#storedAt'::text) AND (valid_from <= i.valid_from))
                    Filter: ((valid_to IS NULL) OR (valid_to > i.valid_from))
                    Index Searches: 1
                    Buffers: shared hit=8
        ->  Index Scan using links_history_object_property on links c  (cost=0.56..8.58 rows=1 width=76) (actual time=0.025..0.026 rows=1.00 loops=4)
              Index Cond: ((object_iri = st.subject_iri) AND (property_iri = 'http://wp.local/wh#contains'::text) AND (valid_from <= i.valid_from))
              Filter: ((valid_to IS NULL) OR (valid_to > i.valid_from))
              Index Searches: 4
              Buffers: shared hit=20
Planning:
  Buffers: shared hit=463
Planning Time: 4.728 ms
Execution Time: 0.557 ms
```

## 采样期间后台活动（第一轮）

- Q1: autovacuum Δ=0, autoanalyze Δ=0, GC counts Δ=0
- Q4: autovacuum Δ=0, autoanalyze Δ=0, GC counts Δ=0
- Q5: autovacuum Δ=0, autoanalyze Δ=0, GC counts Δ=1
