---
id: P6-59
title: 实例库服务：REST 批量写入（调用方时间戳、规则校验、单租户）
phase: P6
module: wp-instances-app
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: done
depends_on: [P6-56, P6-58]
---

# P6-59 实例库写入 API

## 目标
业务系统经认证后，可对配置的唯一本体项目批量 upsert / link / unlink / retire；写入走 `ValidatingInstanceWriter` + `CachedRuleSource`；`at` 由调用方给出。幂等键留给 P6-64。

## 参考
- P6-34 / EPIC 决定第 2、3 条；ADR-013 第 4 节
- P6-31：`InstanceStore`、`InstanceRecord`、`LinkRecord`
- P6-33 / P6-56：`ValidatingInstanceWriter`、`CachedRuleSource`、`Violation`
- P6-58：API Key、单租户 `project-id`、`docs/api/instances.md`

## 产出
- `POST /api/v1/projects/{projectId}/batch`：
  - `{projectId}` ≠ `wp.instances.project-id` → 403；
  - 体：`at`（ISO-8601）、`ops` 数组（upsert / link / unlink / retire，字段对齐 `InstanceRecord` / `LinkRecord`）；
  - 成功：200 + 摘要；校验失败：422 + `Violation` 列表，**整批不写**；
  - `source` 取自 API Key 名称或请求体，不得为空。
- 接上 `CachedRuleSource`；`ontology_rev` 来自规则；契约版本失败映射为 **503**（依赖 P6-56 的 ERROR 日志）。
- 请求体上限配置（默认 1 MiB 或 1000 ops）→ 413；过远未来的 `at` 拒绝窗口写进文档与测试。
- 测试：认证；单租户 403；校验失败不落库；时钟过早拒绝。
- 更新 `docs/api/instances.md`（本卡不写 OpenAPI，见 P6-64）。

## 实现要点
- 整批一个事务；任一条 `Violation` 则全部回滚。
- 不在服务端用 `Instant.now()` 作为业务 `at`。
- **本卡不做** `Idempotency-Key`（P6-64）。
- 应用内不做细粒度 rate limit；P6-61 文档要求入口反向代理限流。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-59` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstancesWriteApiTest,InstancesApiKeyTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 幂等键与 OpenAPI（P6-64）、读取 API（P6-60）、Compose（P6-61）、必填/基数/枚举（P6-63）

## 执行记录
- 日期：2026-11-27
- 分支：feature/p6-59-instances-write-api
- 测试：2026-11-27T15:22:10+08:00 通过（3 项）
- 实际改动：Added POST /api/v1/projects/{projectId}/batch with caller-supplied at, ValidatingInstanceWriter + CachedRuleSource (single JDBC TX), single-tenant 403, 422/413/503 mapping, request size stream limit, and docs/api/instances.md updates.
- 偏差：无
- 审查：java-reviewer, code-reviewer, security-reviewer
