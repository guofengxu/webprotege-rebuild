---
id: P3-02
title: PostgreSQL 版 RevisionLog 与 SnapshotStore（含 outbox）
phase: P3
module: wp-app/revision
type: impl
size: M
work_type: db
ecc_extra: [concurrency]
status: todo
depends_on: [P3-01, P1-08, P1-06, P1-07]
---

# P3-02 PostgreSQL 版 RevisionLog 与 SnapshotStore（含 outbox）

## 目标
修订、主语索引和 outbox 在同一个事务中写入，行为与内存实现完全一致。

## 参考
- ADR-004
- WP-3、WP-5
- P1-08 的契约测试 `RevisionLogContract`、`SnapshotStoreContract`

## 产出
- wp-app 测试依赖 wp-kernel 的 `test-jar`（`<type>test-jar</type>`，test 范围）。
- `wprebuild.app.revision.JdbcRevisionLog implements RevisionLog`（`@Component`）：`append` 在一个事务内完成：查询当前 head（`select coalesce(max(rev_no), 0)`），不等于 `expectedHead` 则抛 `ConcurrentAppendException`；两个事务读到相同 head 并同时插入时，后提交的一方会遇到主键冲突，同样转换为 `ConcurrentAppendException`（注意：PostgreSQL 不允许对聚合查询使用 `FOR UPDATE`，不要用这种写法）→ 插入 `revisions`（payload 用 `OfnLineChangeCodec` 编码，`codec` 列写 `ofn-lines/1`）→ 插入 `revision_subjects`（变更签名中全部实体的 IRI，加上注解断言中 IRI 类型的主语，去重）→ 插入 `outbox`（`type = 'REVISION_COMMITTED'`，payload 为 `{projectId, revNo, userId, ts, changeCount}`）。并发冲突抛 `ConcurrentAppendException`。
- `read` 按修订号顺序流式读取，不要一次把整个项目的 payload 读进内存。
- `wprebuild.app.revision.JdbcSnapshotStore implements SnapshotStore`（`@Component`）。
- `wprebuild.app.revision.KernelCodecConfig`：把 `OfnLineChangeCodec`、`OfnZipSnapshotCodec` 注册为 Bean。
- 测试 `JdbcRevisionLogTest extends RevisionLogContract`、`JdbcSnapshotStoreTest extends SnapshotStoreContract`（Testcontainers）。
- 测试 `JdbcRevisionLogOutboxTest`：每追加一个修订就有一条 outbox 记录；`revision_subjects` 中的 IRI 正确；用一个会抛异常的测试替身替换 outbox 写入后，`revisions` 中也没有新行（事务回滚）。

## 实现要点
- 测试中的项目行需要先插入 `projects` 和 `users`，写一个测试工具类 `TestData` 负责插入，供后续卡片复用。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-02` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Jdbc*Test'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- outbox 发布器（P6）
- 修订历史查询 API（P3-20）
