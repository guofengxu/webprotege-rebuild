---
id: P1-08
title: 修订与修订日志端口（含内存实现和契约测试）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-02]
---

# P1-08 修订与修订日志端口（含内存实现和契约测试）

## 目标
内核通过接口读写修订日志和快照；同一套契约测试将来同时验证内存实现和 PostgreSQL 实现。

## 参考
- BS-03
- WP-5
- ADR-004（乐观并发追加）

## 产出
- `wprebuild.kernel.revision.Revision`：`record Revision(RevisionNumber number, UserId user, Instant timestamp, String description, List<OntologyChange> changes)`；`number` 必须大于 0。
- `wprebuild.kernel.revision.RevisionLog`：`RevisionNumber head(ProjectId)`（无修订时为 `ZERO`）；`void append(ProjectId, RevisionNumber expectedHead, Revision)`；`Optional<Revision> get(ProjectId, RevisionNumber)`；`Stream<Revision> read(ProjectId, RevisionNumber fromInclusive, RevisionNumber toInclusive)`。
- `append` 的规则：`revision.number()` 必须等于 `expectedHead.next()`，否则抛 `IllegalArgumentException`；当前 head 不等于 `expectedHead` 时抛 `wprebuild.kernel.revision.ConcurrentAppendException`。
- `wprebuild.kernel.revision.SnapshotStore`：`void save(ProjectId, RevisionNumber, String codecId, byte[] payload)`；`Optional<StoredSnapshot> latest(ProjectId)`；`Optional<StoredSnapshot> latestAtOrBefore(ProjectId, RevisionNumber)`；`record StoredSnapshot(RevisionNumber revision, String codecId, byte[] payload)`。
- `InMemoryRevisionLog`、`InMemorySnapshotStore`：线程安全。
- 契约测试 `wp-kernel/src/test/java/wprebuild/kernel/revision/RevisionLogContract.java`（抽象类，子类提供实现）：空项目 head 为 0；连续追加；`read` 的区间语义；错误修订号；并发追加——8 个线程用同一个 `expectedHead` 追加，恰好 1 个成功、7 个抛 `ConcurrentAppendException`（WP-5）。`SnapshotStoreContract.java` 同理。
- `InMemoryRevisionLogTest extends RevisionLogContract`、`InMemorySnapshotStoreTest extends SnapshotStoreContract`。
- wp-kernel 的 POM 配置 `maven-jar-plugin` 的 `test-jar` 目标，让 wp-app（P3-02）复用契约测试。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-08` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest='InMemory*Test'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- PostgreSQL 实现（P3-02）
- 修订编码（实现类不需要编码，内存里直接存对象）
