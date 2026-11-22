---
id: P6-64
title: 实例库服务：写入幂等键（Idempotency-Key）与 instances OpenAPI 初稿
phase: P6
module: wp-instances-app
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P6-60]
---

# P6-64 写入幂等键与 OpenAPI

## 目标
为批量写入提供可选 `Idempotency-Key`（按 API Key 隔离），并新增实例服务自己的 OpenAPI 快照（不改编辑器契约）。

## 参考
- P6-34 / EPIC；P6-59：`POST .../batch`
- EPIC：应用 Flyway `db/instances-app` **V2**；`docs/api/instances.md` 串行至本卡

## 产出
- `backend/wp-instances-app/src/main/resources/db/instances-app/V2__idempotency.sql`：`idempotency_keys(api_key_id uuid, idem_key text, request_hash text not null, response_status int not null, response_body jsonb, created_at timestamptz not null, primary key (api_key_id, idem_key))`；保留策略（例如 24h 惰性删除）写进实现要点与文档。
- 批量写入支持头 `Idempotency-Key`：同键同哈希 → 重放首次响应；同键不同哈希 → 409；无键 → 行为与 P6-59 相同。
- `docs/api/instances-openapi.json` 初稿（至少含 ping、batch、读/追溯若已存在则一并收录）；更新 `docs/api/instances.md` 幂等语义。
- 测试：重放、冲突 409、无键路径回归。

## 实现要点
- 不要往内核 `db/instances` 加脚本。
- 不改编辑器 `docs/api/openapi.json` / `@wp/api-types`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-64` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstancesIdempotencyTest,InstancesWriteApiTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 新的写入操作类型；多租户表改造；网络基准（P6-62）
