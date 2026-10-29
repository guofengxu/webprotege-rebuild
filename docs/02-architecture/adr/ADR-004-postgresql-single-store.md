# ADR-004 PostgreSQL 作为唯一存储；先持久化，后更新内存

- 状态：已接受
- 日期：2026-10-28
- 修订：2026-10-29 —— 版本升级为 PostgreSQL 18；明确本 ADR 只覆盖编辑器自身的数据，本体实例的放置见 ADR-013。

## 背景
旧系统：修订在文件里（BinaryOWL，异步写盘），元数据在 MongoDB。两个存储之间没有事务，并且已确认会丢失已返回给用户的修订（`docs/01-rea/README.md` 第 7 节）。之前的架构讨论里还要求把本体发布到图数据库，双写同样没有事务保证。

## 决策
- 编辑器只用一个 PostgreSQL 18 数据库，表的大致划分：
  - `revisions(project_id, rev_no, user_id, ts, description, change_count, codec, payload bytea)`，主键 `(project_id, rev_no)`，只追加；
  - `revision_subjects(project_id, rev_no, subject_iri)`，用于按实体查历史（P3-20）；
  - `snapshots(project_id, rev_no, codec, payload bytea, created_at)`；
  - `outbox(id, project_id, rev_no, type, payload jsonb, created_at, published_at)`；
  - 元数据：`users`、`api_keys`、`projects`、`role_assignments`、`project_settings` 等。
- 写入顺序固定为：在**同一事务**中插入修订 + 主语索引 + outbox → 提交 → 更新内存（`docs/01-rea/write-path.md`）。
- 追加修订用乐观并发：插入时带期望的 head 修订号，主键冲突即视为并发写入失败。

## 理由
- 一个事务覆盖修订和 outbox，向外部系统（图数据库、Kafka）发布时不再有双写问题。
- 备份、恢复、权限都只有一套。
- 修订是只追加的大对象，PostgreSQL 处理这种负载没有问题；冷启动靠快照（P1-07）。

## 后果
- 不再需要 MongoDB 和数据目录下的文件。
- 单个修订的 payload 可能很大（导入时的修订 1），由编码层压缩（ADR-011）。
- 本 ADR 覆盖的是编辑器（设计期）的数据：本体模型、设计期实例、修订历史、用户与权限。运行期的大规模本体实例另有实例库，见 ADR-013。
