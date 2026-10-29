---
id: P3-03
title: ProjectRuntime 注册表（Caffeine）与快照策略
phase: P3
module: wp-app/ontology
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency]
status: todo
depends_on: [P3-02, P1-21]
---

# P3-03 ProjectRuntime 注册表（Caffeine）与快照策略

## 目标
按需加载项目运行时，空闲时自动释放内存，定期生成快照。

## 参考
- ADR-005、ADR-006
- WP-8
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/ProjectCache.java`（休眠项目清理）

## 产出
- `wprebuild.app.ontology.RuntimeProperties`（`@ConfigurationProperties("wp.runtime")`）：`idleTimeout`（默认 30 分钟）、`maxLoaded`（默认 20）、`snapshotEvery`（默认 500 个修订）。
- `wprebuild.app.ontology.ProjectRuntimeFactory`：组装 `ProjectRuntime`、`ProjectWriteGate`、`ChangePipeline`；派生视图包括类层级、三种属性层级和显示名称；显示名称设置与新实体设置先使用默认值（项目设置的读取在 P3-22 接入，这里在 `ontology` 模块中定义 `ProjectSettingsProvider` 接口并提供返回默认值的实现）。
- `wprebuild.app.ontology.ProjectHandle`：`record ProjectHandle(ProjectRuntime runtime, ProjectWriteGate gate, ChangePipeline pipeline)`。
- `wprebuild.app.ontology.ProjectRuntimeRegistry`：`ProjectHandle get(ProjectId)`，基于 Caffeine `LoadingCache`，`expireAfterAccess(idleTimeout)`、`maximumSize(maxLoaded)`；同一项目并发访问时只加载一次；运行时被标记为损坏时自动失效并重新加载（WP-8）；`void evict(ProjectId)`。
- `wprebuild.app.ontology.RevisionCommittedEvent`：`record RevisionCommittedEvent(ProjectId projectId, Revision revision, boolean silent)`；`ChangePipeline` 的 `PostCommitListener` 实现把它同步发布为 Spring 事件（`ApplicationEventPublisher`），供 search、events 模块订阅。
- 快照策略：修订号能被 `snapshotEvery` 整除时，在独立的执行器中持读锁调用 `ProjectSnapshotter`。
- Micrometer 指标：`wp.runtime.loaded`（gauge）、`wp.runtime.load`（timer，按项目打标签不可取，只记总体）。
- 测试 `ProjectRuntimeRegistryTest`：两次 `get` 返回同一实例；10 个线程并发 `get` 时加载器只被调用一次；注入假的 `Ticker`，超过空闲时间后被驱逐；损坏后自动重新加载；第 `snapshotEvery` 个修订后 `snapshots` 表中出现一行。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-03` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectRuntimeRegistryTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 多实例之间的项目路由（P6 以后）
