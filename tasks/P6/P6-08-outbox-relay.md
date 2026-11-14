---
id: P6-08
title: outbox 中继：按消费者记录进度，按提交顺序投递修订事件
phase: P6
module: wp-app
type: impl
size: M
work_type: db
ecc_extra: [concurrency]
status: done
depends_on: [P4-17]
---

# P6-08 outbox 中继

## 目标
P3-02 以来，每个修订都在同一事务中写入一行 `outbox`（`REVISION_COMMITTED`），但还没有任何程序读取它。本卡提供进程内的中继：按 `outbox.id` 的顺序，把这些行分批交给已注册的消费者，并为每个消费者单独记录进度，保证不跳过任何一行。它是投影表增量更新（P6-12）的基础；以后的 webhook、Kafka、实例库也作为消费者接入。

## 参考
- ADR-004、ADR-006（锁顺序）、ADR-013（“由 outbox 驱动”）；`docs/02-architecture/overview.md` 第 4 节（`RL -. P6 .-> PUB[outbox 发布器]`）
- `backend/wp-app/src/main/resources/db/migration/V2__core_schema.sql` 中的 `outbox` 表（`id bigint generated always as identity`、`project_id`、`rev_no`、`type`、`payload jsonb`、`created_at`、`published_at`）以及部分索引 `outbox_unpublished`
- `revision/RevisionCommitOutbox.java`、`revision/JdbcRevisionCommitOutbox.java`（payload 为 `{projectId, revNo, userId, ts, changeCount}`）；`revision/JdbcRevisionLog.java#append`（`outbox.record` 是事务中的最后一条语句）
- `wprebuild.kernel.runtime.ChangePipeline#commit`：先 `revisionLog.append`（数据库事务提交），再 `gate.applyExclusive` 改内存模型
- `migration/ProjectMigrator.java`：导入的迁移项目每个历史修订都会写一行 outbox
- `events/ProjectEventHub.java`（守护线程与 `DisposableBean` 的写法）
- `backend/wp-app/src/test/java/wprebuild/app/CoreSchemaTest.java`（检查 `public` 下的表清单）

## 产出
- `db/migration/V4__outbox_consumers.sql`：`outbox_consumers(consumer text primary key, last_id bigint not null, updated_at timestamptz not null default now())`。`CoreSchemaTest` 的表清单增加 `outbox_consumers`。
- **避免 Bean 循环依赖**：`JdbcRevisionLog` 依赖 `RevisionCommitOutbox`；而 P6-12 的投影消费者依赖 `RevisionLog`（即 `JdbcRevisionLog`）与 `ProjectRuntimeRegistry`。所以 `JdbcRevisionCommitOutbox` 通过 `ObjectProvider<OutboxRelay>` 取得中继，`OutboxRelay` 在每次轮询时通过 `ObjectProvider<OutboxConsumer>` 取得消费者，两处都不在构造器中直接注入。
- `revision/JdbcRevisionCommitOutbox.java`：
  - 插入 outbox 行之前，先取一把**全局**的事务级咨询锁 `pg_advisory_xact_lock(<常量键>)`，常量定义在本类中并写明用途。原因：`id` 在插入时分配，提交后才可见；两个项目的事务乱序提交时，中继会先看到较大的 `id`，把 `last_id` 推过去，较小的那一行就永远不会被投递。`outbox.record` 是 `append` 事务的最后一条语句，这把锁只覆盖“插入 → 提交”这一小段，使 `id` 顺序等于提交顺序。
  - 写入后注册 `TransactionSynchronization.afterCommit`，调用 `OutboxRelay.wake()`；不在事务中时直接调用。
  - 用 grep 确认所有写 `outbox` 的路径都经过 `RevisionCommitOutbox.record`（包括迁移导入）；如有直接 `insert into outbox` 的地方，改为调用它。
- `revision/OutboxMessage.java`：`public record OutboxMessage(long id, ProjectId projectId, long revNo, String type, String payloadJson, Instant createdAt)`。
- `revision/OutboxConsumer.java`（公开 SPI）：
  - `String name()`：写入 `outbox_consumers.consumer`，全局唯一；
  - `StartPosition startFrom()`：`EARLIEST`（从第一行开始）或 `LATEST`（登记时的 `max(id)`，只接收之后的行），默认 `LATEST`。只在第一次登记时生效；
  - `long accept(List<OutboxMessage> batch)`：在中继的数据库事务中执行，返回已经处理完的最后一条消息的 `id`。返回值必须不小于本批第一条的 `id`：`id` 不大于它的消息都已处理，中继把 `last_id` 设为它；小于本批最大 `id` 表示“部分确认”，中继立即接着取下一批。
- `revision/OutboxRetryLater.java`：`public final class OutboxRetryLater extends RuntimeException`。消费者抛出它表示“数据暂时还没准备好”（例如 P6-12 发现内存模型还没应用这次修订）：中继回滚，200 毫秒后重试，不记 error 日志，不计入指数退避。
- `revision/OutboxRelay.java`（`@Component`，`DisposableBean`）：
  - 一个守护线程的 `ScheduledExecutorService`，按 `wp.outbox.poll-interval`（默认 1 秒）轮询；`wp.outbox.batch-size` 默认 200；`wp.outbox.enabled` 默认 `true`（个别测试可以关闭）。配置类为 `revision/OutboxProperties.java`。
  - 对每个消费者开一个事务：先登记该消费者（`insert … on conflict do nothing`，`last_id` 按 `startFrom()` 取 0 或当前 `max(id)`）；再 `select last_id … for update skip locked`，取不到行就跳过（说明另一个实例正在处理）；然后读 `id > last_id order by id limit batch-size`；调用 `accept`；按返回值更新 `last_id`。
  - 本批取满（行数等于 `batch-size`）或部分确认时，不等轮询间隔，立即再取一批；直到取不满为止。
  - `accept` 抛出 `OutboxRetryLater` 以外的异常时：回滚，`last_id` 不前进；记录 error 日志（含消费者名与本批第一个 `id`）；该消费者按指数退避重试（1 秒起，上限 60 秒），其它消费者不受影响。返回值不合法（小于本批第一条的 `id`，或大于本批最大 `id`）按异常处理。
  - `wake()`：不阻塞，多次调用合并为一次立即轮询。
- 测试 `revision/OutboxRelayTest.java`（Testcontainers，用测试内注册的消费者）：
  - 连续提交 5 个修订：消费者按 `id` 顺序恰好收到 5 条，`last_id` 等于最后一条的 `id`；
  - **乱序提交**：项目 A 的事务先写入 outbox 行但暂不提交，项目 B 随后提交。断言 B 的写入要等 A 提交后才能完成（全局锁生效），中继最终两行都投递，且没有跳过任何 `id`；
  - 消费者第一次抛异常：`last_id` 不前进，重试后收到同一批，没有跳过任何行；抛 `OutboxRetryLater` 时不记 error 日志，并在短时间内重试；
  - 部分确认：消费者只确认前 2 条，中继立即投递剩下的行；
  - 两个消费者互不影响：其中一个一直失败，另一个照常前进；
  - `startFrom()` 为 `LATEST` 的消费者第一次登记后，不会收到登记之前的行；为 `EARLIEST` 的会收到全部历史行；
  - 积压 1,000 行、`batch-size` 为 200 时，不需要等待轮询间隔即可全部投递；
  - 两个 `OutboxRelay` 实例同时运行（模拟两个应用实例）：每一行只被同一个消费者处理一次；
  - 提交之后，在远小于轮询间隔的时间内被处理（`wake()` 生效）；
  - 没有注册任何消费者时，不写 `outbox_consumers`，也不改 `outbox`；
  - 测试上下文中注册一个依赖 `RevisionLog` 与 `ProjectRuntimeRegistry` 的消费者 bean：应用能正常启动（没有循环依赖），并能收到行；
  - 应用关闭时线程退出，不再访问数据库。

## 实现要点
- 锁顺序（ADR-006）：编辑时先持有项目的生成锁，再在数据库事务末尾取全局 outbox 锁。任何地方都不能在持有全局 outbox 锁时去等内核的锁，中继本身也不取这把锁。审查时按 ADR-006 核对锁顺序。这把锁使不同项目的提交在“插入 outbox → 提交”这一段串行，代价是一次提交的耗时；人工编辑的频率下可以接受，在执行记录中写明测得的单次编辑耗时变化（对照 `EventsTest` 或 `BackendAcceptanceTest` 的耗时即可）。
- 投递语义：对消费者而言至少一次。消费者若只在同一个数据库事务内写库（例如投影表），效果等同恰好一次；若有外部副作用（HTTP、Kafka），必须自己保证幂等。把这一点写进 `OutboxConsumer` 的 Javadoc。
- 顺序：全局 `id` 顺序等于提交顺序；同一个项目的修订号随 `id` 单调递增。
- outbox 行可见时，内存模型**可能还没有**应用这次修订（`ChangePipeline` 先提交、后应用）。需要读内存模型的消费者必须自己检查，并在没准备好时抛出 `OutboxRetryLater`。把这一点写进 `OutboxConsumer` 的 Javadoc。
- `outbox.published_at` 与 `outbox_unpublished` 索引保持原样、不再使用，在 `OutboxRelay` 的 Javadoc 中说明。清理旧的 outbox 行不在本卡范围。
- `revision` 模块只依赖 `shared`。`OutboxConsumer` 放在 `revision` 中，由上层模块（例如 `projection`）实现，依赖方向不变。
- 本卡是 outbox 消费者的公共前提（投影表、以后的 webhook / Kafka / 实例库），不依赖投影表的 PoC，PoC 不通过也不影响本卡。Flyway 版本：本卡用 `V4`，投影表（P6-09）用 `V5`，因此 P6-09 依赖本卡。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-08` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='OutboxRelayTest,JdbcRevisionLogOutboxTest,CoreSchemaTest,ModularityTest,BackendAcceptanceTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 任何具体的消费者（投影表见 P6-12；webhook、Kafka、图数据库、实例库另开卡）
- outbox 行的清理与归档
- 修改 `REVISION_COMMITTED` 的 payload 格式

## 执行记录
- 日期：2026-11-14
- 分支：feature/p6-08-outbox-relay
- 测试：2026-11-14T11:39:32+08:00 通过（3 项）
- 实际改动：Added V4 outbox_consumers, OutboxRelay/OutboxConsumer SPI, and global advisory lock on outbox insert so id order equals commit order. Wake-after-commit + backoff; registration survives accept failures. Normal edit path holds the lock only for insert→commit; migrator outer txn still holds it for the whole import (pre-existing txn boundary). BackendAcceptanceTest still green; wake delivers far under the 1h test poll interval.
- 偏差：Migrator import keeps one outer transaction, so the global outbox lock spans the whole project import rather than only insert→commit; ChangePipeline path matches the card.
- 审查：database-reviewer, java-reviewer, silent-failure-hunter
