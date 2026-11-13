---
id: P6-12
title: 投影表增量更新：消费 outbox，只重算受影响的实体
phase: P6
module: wp-app
type: impl
size: M
work_type: db
ecc_extra: [concurrency]
status: todo
depends_on: [P6-11, P6-08]
---

# P6-12 投影表增量更新

## 目标
每次提交修订后，投影表在几秒内跟上：中继（P6-08）把 `REVISION_COMMITTED` 交给投影消费者，消费者读出这些修订的变更，只重算受影响的实体；项目第一次出现、或一批变更太大时改为全量重建（P6-10）。投影只会确认内存模型已经应用的修订，任何时候都等于用某个已应用修订全量计算的结果。

## 参考
- P6-08：`revision/OutboxConsumer`（`startFrom()`；`long accept(batch)` 返回已处理到的 `id`，支持部分确认；在中继事务内执行）、`revision/OutboxRetryLater`、`revision/OutboxMessage`
- P6-09：`projection/ProjectionWriter`（`lockProject`、`replaceProject`）、`projection.project_state`；P6-10：`ProjectionRows.compute`、`ProjectionRebuilder`；P6-11：PoC 结论（本卡只在 PoC 达标后开工）
- 内核：`wprebuild.kernel.revision.RevisionLog#read(ProjectId, RevisionNumber, RevisionNumber)`（实现为 `revision/JdbcRevisionLog`）；`wprebuild.kernel.change` 中的 `AddAxiom`、`RemoveAxiom`（以及不影响投影的 `AddImport`、`RemoveImport`、`AddOntologyAnnotation`、`RemoveOntologyAnnotation`、`DeclareOntology`）
- `wprebuild.kernel.runtime.ChangePipeline#commit`：outbox 行可见时，内存模型可能还没有应用这次修订
- `bus/ProjectExistenceCheck.java`（`ACTIVE` / `TRASHED` / `MISSING`）；`ontology/ProjectRuntimeRegistry.java`

## 产出
- `projection/ProjectionRows.java`：新增 `static ProjectionSnapshot computeFor(ProjectRuntime runtime, Set<IRI> subjects)`，只为 `subjects` 中的 IRI 产生行：作为实体、作为标签主体、作为层级的子节点、作为属性的定义域 / 值域主体。约束：`computeFor(runtime, 全部 IRI)` 必须等于 `compute(runtime)`。
- `projection/ProjectionWriter.java`：新增 `boolean replaceSubjects(ProjectId projectId, Set<IRI> subjects, ProjectionSnapshot rows, long revNo)`：要求已持有该项目的锁，修订号同样只进不退；删除这些 IRI 作为 `entities.iri`、`labels.iri`、`hierarchy_edges.child_iri`、`property_domains.property_iri`、`property_ranges.property_iri` 的行，再插入新行，并更新 `project_state.rev_no` 与 `updated_at`。
- `projection/AffectedSubjects.java`：纯函数，从一组 `OntologyChange` 中收集受影响的 IRI，包括 `AddAxiom` / `RemoveAxiom` 中公理签名里的全部实体 IRI，**以及注解断言的主体 IRI**（`OWLAnnotationAssertionAxiom` 的主体是 IRI，不在签名中，容易漏掉）。其余变更类型不影响投影。
- `projection/ProjectionOutboxConsumer.java`（实现 `OutboxConsumer`，`name()` 为 `"projection"`，`startFrom()` 为 `EARLIEST`）。按 `id` 顺序遍历本批中 `type = REVISION_COMMITTED` 的消息（其它类型直接视为已处理）；第一次遇到某个项目时，一次处理该项目在本批中的全部消息：
  1. 用 `ProjectExistenceCheck.state` 判断项目：`MISSING` 或 `TRASHED` 时跳过这些消息，**不推进** `project_state`。项目恢复后的下一个修订会从 `state.rev_no + 1` 读起，补上被跳过的变更。
  2. `writer.lockProject`；读取 `project_state`。该项目在本批中的最大 `revNo` 不超过 `state.rev_no` 时跳过（这会快速消化 P3 以来积压的历史行）。
  3. 在 `handle.gate().read(...)` 内先检查 `runtime.head()`：**小于本批最大 `revNo` 时抛出 `OutboxRetryLater`**，因为内存模型还没应用这次修订，现在确认会让这次编辑永远进不了投影。
  4. 没有 `project_state` 行，或受影响的 IRI 超过 `wp.projection.full-rebuild-threshold`（默认 5,000）时，调用 `ProjectionRebuilder.rebuild`。**一批最多做一次全量重建**：做完之后返回当前消息的 `id`（部分确认，此前的消息都已处理），剩余消息留给下一批，避免积压时在一个事务里加载并重建大量项目。
  5. 否则用 `RevisionLog.read(projectId, state.rev_no + 1, maxRevNo)` 读取变更，用 `AffectedSubjects` 计算 IRI，在读锁内调用 `computeFor`，释放读锁后调用 `replaceSubjects(…, maxRevNo)`。
- `projection/ProjectionProperties.java`：`wp.projection.enabled`（**默认 `false`**；为 `false` 时不注册消费者，避免所有测试上下文与未使用投影的部署都写投影表）、`wp.projection.full-rebuild-threshold`。
- `projection/package-info.java`：`allowedDependencies` 增加 `"revision"`、`"bus"`；同步修改 `docs/02-architecture/overview.md` 第 2 节中 `projection` 行的“允许依赖”。
- 测试：
  - `projection/AffectedSubjectsTest.java`：子类公理、注解断言（主体是 IRI）、定义域 / 值域公理、导入变更各一例；
  - `projection/ProjectionIncrementalTest.java`（Testcontainers，`wp.projection.enabled=true`；导入 `testdata/ontologies/warehouse-sample.ttl`，通过命令总线编辑）：新建类、加父类、改标签、给属性加定义域、删除实体、回滚一个修订，每一步之后等待 `project_state.rev_no` 等于 `runtime.head()`（最多 10 秒），再断言数据库中的行等于 `ProjectionRows.compute(runtime)` 的结果；
  - 同一测试中：受影响的 IRI 超过阈值（测试中把阈值设为 3）时走全量重建，结果仍然相等；先有一批历史 outbox 行、后启用投影时，先全量重建一次，再跳过全部历史行；两个项目都需要全量重建时，分两批完成（部分确认生效）；
  - 内存模型落后：直接调用消费者，传入一条修订号大于 `runtime.head()` 的消息，断言抛出 `OutboxRetryLater` 且投影不变；
  - 回收站：项目移入回收站后编辑不了，恢复后再编辑一次，投影追上全部修订。

## 实现要点
- 等价性是唯一的正确性标准：增量结果必须与全量计算一致。多算几个 IRI 无害，漏算会留下脏数据，所以宁可多算。
- `state.rev_no` 记的是本批的最大 `revNo`。第 3 步保证此时内存模型已经应用到该修订；模型可能已经更新到更后的修订，那些修订自己的 outbox 行之后还会再来，重算一遍不影响正确性。
- 锁顺序：投影咨询锁 → 内核读锁；编辑线程不取投影咨询锁，不会死锁。写库期间不持有内核的锁。整个处理在中继的事务内完成，投影行与 `outbox_consumers.last_id` 一起提交或一起回滚。
- 显示语言等项目设置的修改不产生修订，也不影响投影（P6-09 不存显示名称）。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-12` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AffectedSubjectsTest,ProjectionIncrementalTest,ProjectionRebuildTest,OutboxRelayTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 重建接口、只读数据库角色与查询说明（P6-13）
- 推理得到的层级（投影只含层级视图给出的父子关系）
- 投影到图数据库、Kafka；实例库
