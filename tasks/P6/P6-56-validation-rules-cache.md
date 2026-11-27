---
id: P6-56
title: 实例库内核：按 project_state.rev_no 轮询刷新的校验规则缓存，并核对 contract_version
phase: P6
module: wp-instances
type: impl
size: M
work_type: db
ecc_extra: []
status: done
depends_on: [P6-33, P6-55]
---

# P6-56 校验规则缓存与契约版本

## 目标
`wp-instances` 能为每个项目缓存 `ValidationRules`：写路径上核对投影库 `project_state.rev_no`，仅在修订号前进时重载；加载时核对 `contract_version`，与内核期望不一致则拒绝提供规则（从而挡住写入），避免读到不兼容的投影形状。

## 参考
- P6-34 / EPIC「实例库服务化」决定第 3、7 条
- P6-33：`ProjectionRuleSource`、`ValidationRules`、`ValidatingInstanceWriter`、`docs/api/projection.md`「How the instance store uses projection tables」
- P6-55：`project_state.contract_version` 与文档中的版本语义
- ADR-013 第 4 节（校验规则由 TBox / 投影派生）

## 产出
- 包 `wprebuild.instances.rules`：
  - 常量 `ProjectionContract.EXPECTED_VERSION`（与编辑器当前版本相同，起步为 `1`）。
  - `CachedRuleSource`（构造：投影 `DataSource`）：`ValidationRules rulesFor(UUID projectId)`；内部按项目缓存 `(revNo, rules)`；调用时先读 `project_state` 的 `rev_no` / `contract_version`，若 `contract_version != EXPECTED_VERSION` 打 ERROR 日志（期望 / 实际）并抛出带说明的异常且**不**返回旧缓存；若缓存未命中或 `rev_no` 已前进则通过 `ProjectionRuleSource.load` 重载。
  - `ProjectionRuleSource.load`（或并列查询）读取 `contract_version`；无投影行时行为与 P6-33 一致（`IllegalStateException`）。
- 测试 `RulesCacheTest`（Testcontainers，投影库 + 实例库）：
  - 首次加载命中投影；同 `rev_no` 再次调用不重跑完整 `load`（可用计数包装断言只读了 `project_state` 或未再次扫描实体表）；
  - `rev_no` 前进后加载到新规则；
  - `contract_version` 被改成其它值时，`rulesFor` 失败且经 `ValidatingInstanceWriter` 的写入不会落库；
  - 项目无 `project_state` 行时失败信息与 P6-33 一致。
- `docs/api/projection.md`：在实例库小节补一句——服务化后由 `CachedRuleSource` 按 `rev_no` 刷新，并校验 `contract_version`。

## 实现要点
- 仍禁止依赖 Spring / wp-app；采用「调用 `rulesFor` 时检查 rev」而非后台定时线程，避免测试 flaky。
- 缓存必须按 `projectId` 隔离；不清空其它项目。
- 本体变严后已有实例：本卡不扫描、不改写历史行（EPIC 决定第 3 条）。
- 不引入新的第三方依赖。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-56` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RulesCacheTest,InstanceValidationTest,InstanceStoreTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- HTTP 服务、认证、连接池生产配置（P6-57 起）
- 订阅 outbox / Kafka
- 必填 / 基数 / 枚举校验（P6-63）

## 执行记录
- 日期：2026-11-26
- 分支：feature/p6-56-validation-rules-cache
- 测试：2026-11-26T10:20:51+08:00 通过（3 项）
- 实际改动：Added CachedRuleSource (per-project rev_no cache + contract_version gate), ProjectionContract.EXPECTED_VERSION=1, ProjectionRuleSource contract checks, RulesCacheTest, and projection.md note.
- 偏差：无
- 审查：java-reviewer, database-reviewer
