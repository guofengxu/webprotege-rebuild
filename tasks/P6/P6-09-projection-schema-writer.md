---
id: P6-09
title: 本体投影表 PoC（一）：projection 模块、表结构与写入器
phase: P6
module: wp-app
type: impl
size: M
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-08, P6-03]
---

# P6-09 投影表 PoC（一）：表结构与写入器

## 目标
为 ADR-013 的“派生投影表”建立表结构和写入器：一个项目的实体、标签、层级父子关系、属性的定义域与值域，可以整体替换写入 PostgreSQL 的 `projection` schema，写入按项目串行、修订号只进不退。本卡是投影表 PoC 的第一张卡；从内存模型计算行见 P6-10，规模测量与通过标准见 P6-11。

## 参考
- ADR-013 第 2 节（投影表：派生、只读、可随时重建）、ADR-004
- `docs/02-architecture/overview.md` 第 2 节（模块依赖矩阵）
- `backend/wp-app/src/main/resources/db/migration/`：P6-08 使用 `V4__outbox_consumers.sql`，本卡使用 `V5`
- `revision/JdbcRevisionLog.java`（`JdbcClient` 与事务的写法）

## 产出
- 新的 Spring Modulith 模块 `projection`：`projection/package-info.java`，`allowedDependencies = {"ontology", "shared"}`。在 `docs/02-architecture/overview.md` 第 2 节表格中增加一行，职责为“由本体派生的只读投影表，供外部 SQL 查询（ADR-013）”。
- `db/migration/V5__projection_schema.sql`：新建 schema `projection`，包含以下表（列名按此）：
  | 表 | 列 | 主键 / 索引 |
  |---|---|---|
  | `project_state` | `project_id uuid`、`rev_no bigint`、`rebuilt_at timestamptz`、`updated_at timestamptz` | PK `project_id` |
  | `entities` | `project_id`、`iri text`、`entity_type text`（`CLASS` / `OBJECT_PROPERTY` / `DATA_PROPERTY` / `ANNOTATION_PROPERTY` / `NAMED_INDIVIDUAL` / `DATATYPE`，check 约束）、`deprecated boolean` | PK `(project_id, iri, entity_type)` |
  | `labels` | `project_id`、`iri`、`property_iri`、`lang text not null default ''`、`value text` | 索引 `(project_id, iri)`、`(project_id, lower(value) text_pattern_ops)` |
  | `hierarchy_edges` | `project_id`、`hierarchy text`（`CLASS` / `OBJECT_PROPERTY` / `DATA_PROPERTY` / `ANNOTATION_PROPERTY`）、`child_iri`、`parent_iri` | PK `(project_id, hierarchy, child_iri, parent_iri)`；索引 `(project_id, hierarchy, parent_iri)` |
  | `property_domains` | `project_id`、`property_iri`、`class_iri` | PK 三列 |
  | `property_ranges` | `project_id`、`property_iri`、`range_iri`、`range_kind`（`CLASS` / `DATATYPE`） | PK `(project_id, property_iri, range_iri)` |
- `projection/ProjectionSnapshot.java` 及每张表的行 `record`（`EntityRow`、`LabelRow`、`HierarchyEdgeRow`、`DomainRow`、`RangeRow`）。`ProjectionSnapshot` 持有五个不可变列表。
- `projection/ProjectionWriter.java`（`@Component`）：
  - `void lockProject(ProjectId projectId)`：取项目级的事务咨询锁 `pg_advisory_xact_lock`，键由项目 ID 推导。以后所有写投影的地方都先调用它，使用同一把锁。不在事务中调用时抛出 `IllegalStateException`。
  - `boolean replaceProject(ProjectId projectId, ProjectionSnapshot snapshot, long revNo)`：要求当前事务已经持有该项目的锁。`revNo` 小于现有 `project_state.rev_no` 时什么也不做，返回 `false`（投影只能前进，不能倒退）；否则删除该项目的旧行，用 JDBC 批量插入（每批 1,000 行），upsert `project_state`（`rebuilt_at` 与 `updated_at` 取当前时间），返回 `true`。
  - 已有事务时加入该事务（P6-10 与 P6-12 需要）。
- 测试 `projection/ProjectionWriterTest.java`（Testcontainers，手工构造 `ProjectionSnapshot`）：
  - `projection` schema 下六张表都存在（`CoreSchemaTest` 只检查 `public`，所以在本测试中检查）；
  - 写入后各表的行与快照相同；用新的快照再写一次，旧行全部被替换；两个项目的数据互不影响；
  - 用比现有 `rev_no` 小的修订号调用时返回 `false`，表中数据不变；
  - 没有事务时调用 `lockProject` 抛出异常；
  - 两个线程同时对同一项目调用（各自在事务中 `lockProject` 后写入）：第二个等第一个提交后才执行，最终数据是其中一份完整快照，不会混合。

## 实现要点
- 投影是派生数据：真源仍然是修订日志（ADR-004）。任何时候都可以清空后重建，不要在投影表里存放不能重建的信息。
- 不存显示名称：显示名称随项目的显示语言设置而变，而改设置不产生修订，存下来会过期。BI 与 AI Agent 直接用 `labels` 表。
- 依赖 P6-03 只是为了串行修改 `docs/02-architecture/overview.md` 第 2 节的表格（P6-03 修改 `events` 行）；依赖 P6-08 是为了 Flyway 版本按 V4 → V5 合并。本卡不使用这两张卡的代码。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-09` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProjectionWriterTest,CoreSchemaTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 从内存模型计算投影行、全量重建（P6-10）
- 规模测量与通过标准（P6-11）
- 增量更新与 outbox 消费（P6-12）；重建接口、只读数据库角色与查询说明（P6-13）
- 回收站中项目的过滤（投影保留其行，查询方自行决定）
