---
id: P1-09
title: ProjectRuntime 与加载器（快照 + 重放）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-05, P1-07, P1-08]
---

# P1-09 ProjectRuntime 与加载器（快照 + 重放）

## 目标
给定修订日志和快照存储，可以把任意项目加载到内存，结果与从头重放完全一致。

## 参考
- BS-03
- ADR-005

## 产出
- `wprebuild.kernel.runtime.DerivedView`：`void rebuild(ProjectOntologies)`；`void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after)`。层级、显示名称等派生视图实现这个接口（P1-17 ~ P1-19）。
- `wprebuild.kernel.runtime.ProjectRuntime`：持有 `ProjectId`、`MutableProjectOntologies`、当前 head、`List<DerivedView>`；对外只暴露只读的 `ProjectOntologies ontologies()`、`RevisionNumber head()`、`<V extends DerivedView> V view(Class<V>)`；包内方法 `void applyCommitted(Revision)`：应用变更、推进 head、依次通知各视图。
- `wprebuild.kernel.runtime.ProjectRuntimeLoader`：构造参数为 `RevisionLog`、`SnapshotStore`、`SnapshotCodec` 和 `List<Supplier<DerivedView>>`；`ProjectRuntime load(ProjectId)`：取最新快照（没有就从空本体开始）→ 重放快照之后到 head 的全部修订 → 对每个视图调用 `rebuild`。快照的 codecId 与传入的 codec 不一致时抛异常。
- `wprebuild.kernel.runtime.ProjectSnapshotter`：`void snapshot(ProjectRuntime, SnapshotStore, SnapshotCodec)`，以当前 head 保存一份快照。（何时调用由 P3-03 决定。）
- 测试 `ProjectRuntimeLoaderTest`：随机生成 100 个修订写入内存日志，加载结果与逐个应用的结果结构相等；第 50 个修订时打快照，再加载，结果仍然相等；空项目 head 为 0；视图的 `rebuild` 在加载结束时被调用一次（用计数视图验证）。

## 实现要点
- 日志里的变更都已经是有效变更，重放时直接 `apply`，不要再过滤。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-09` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=ProjectRuntimeLoaderTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 写入流水线（P1-11）
- 并发控制（P1-10）
