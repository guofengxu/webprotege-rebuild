---
id: P1-11
title: ChangePipeline：先持久化后应用的写入流水线
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [concurrency]
status: todo
depends_on: [P1-09, P1-10]
---

# P1-11 ChangePipeline：先持久化后应用的写入流水线

## 目标
所有写操作走同一条流水线，满足写路径的全部不变量。

## 参考
- `docs/01-rea/write-path.md` 第 2 节（时序与 WP-3、WP-4、WP-6、WP-8）
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/chg/ChangeManager.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/dispatch/AbstractProjectChangeHandler.java`

## 产出
- `wprebuild.kernel.runtime.ChangeContext`：`record ChangeContext(UserId user, ProjectOntologies ontologies)`。
- `wprebuild.kernel.runtime.GeneratedChanges<R>`：`record GeneratedChanges<R>(List<OntologyChange> changes, R result)`。
- `wprebuild.kernel.runtime.ChangeListGenerator<R>`：`GeneratedChanges<R> generate(ChangeContext)`；`default String describe(GeneratedChanges<R> generated, List<OntologyChange> effective)`，默认返回 `Edited <n> axioms`；`default R renameResult(R result, Map<IRI, IRI> renameMap)`，默认原样返回；`default boolean silent()`，默认 `false`。
- `wprebuild.kernel.runtime.FreshIriMinter`：`MintResult mint(UserId, List<OntologyChange>, ProjectOntologies)`；`record MintResult(List<OntologyChange> changes, Map<IRI, IRI> renameMap)`；常量 `NONE`（原样返回，映射为空）。真正的实现在 P1-12。
- `wprebuild.kernel.runtime.ChangeApplicationResult<R>`：`record ChangeApplicationResult<R>(R result, List<OntologyChange> effectiveChanges, Map<IRI, IRI> renameMap, Optional<Revision> revision)`。
- `wprebuild.kernel.runtime.PostCommitListener`：`void onCommitted(ProjectRuntime runtime, Revision revision, boolean silent)`。
- `wprebuild.kernel.runtime.ChangePipeline`：构造参数为 `ProjectRuntime`、`ProjectWriteGate`、`RevisionLog`、`FreshIriMinter`、`List<PostCommitListener>`、`Clock`；方法 `<R> ChangeApplicationResult<R> apply(UserId, ChangeListGenerator<R>)`，步骤严格按 write-path.md 第 2 节的时序。
- `wprebuild.kernel.runtime.ProjectRuntimeCorruptedException`；`ProjectRuntime` 增加 `boolean isCorrupted()`。
- 测试 `ChangePipelineTest`：WP-3（`RevisionLog.append` 抛异常时，ontologies 与 head 不变，视图未被通知）；WP-4（有效变更为空时不追加修订、不调用监听器，`revision` 为空）；WP-6（第一个监听器抛异常，第二个仍被调用，修订已提交）；WP-8（让某个视图在 `onApplied` 中抛异常，此后 runtime 标记为损坏，再次 `apply` 抛 `ProjectRuntimeCorruptedException`）；修订号连续；描述文本来自生成器；`silent` 传给监听器。

## 实现要点
- 监听器在**释放生成锁之前**调用，这样监听器读取视图的增量（例如层级变化）时，不会被下一次写入覆盖。
- 持久化发生在 `applyExclusive` 之外：数据库提交时只持有生成锁，读请求不受影响。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-11` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=ChangePipelineTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 临时 IRI 处理（P1-12）
- 具体的生成器（P1-15、P1-16）
