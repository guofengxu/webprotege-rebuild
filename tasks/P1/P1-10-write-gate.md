---
id: P1-10
title: ProjectWriteGate：生成锁 + 读写锁
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: [concurrency]
status: todo
depends_on: [P1-01]
---

# P1-10 ProjectWriteGate：生成锁 + 读写锁

## 目标
每个项目有一个并发门：生成过程串行，应用内存时排斥读操作，读操作之间并发。

## 参考
- ADR-006
- WP-1、WP-2
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/chg/ChangeManager.java`（`changeProcesssingLock`、`projectChangeWriteLock`）

## 产出
- `wprebuild.kernel.runtime.ProjectWriteGate`：`<T> T read(Supplier<T>)`（持读锁）；`<T> T generate(Supplier<T>)`（持生成锁，可重入）；`void applyExclusive(Runnable)`（持写锁；调用线程必须已持有生成锁，否则抛 `IllegalStateException`）。
- 测试 `ProjectWriteGateTest`：WP-1（两个线程同时 `generate`，用计数器断言同一时刻至多一个在临界区）；WP-2（`applyExclusive` 执行期间，`read` 阻塞；两个 `read` 可以同时处于临界区）；不持生成锁直接调用 `applyExclusive` 抛异常。用 `CountDownLatch` 控制时序，不要用 `Thread.sleep` 作为同步手段。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-10` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=ProjectWriteGateTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 流水线逻辑（P1-11）
