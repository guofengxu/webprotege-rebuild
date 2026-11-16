---
id: P6-49
title: 后端：项目 webhook 的管理接口（增删改查、停用时取消待投递记录、最近投递记录）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: todo
depends_on: [P6-20]
---

# P6-49 后端：项目 webhook 的管理接口

## 目标
项目管理者可以通过 REST 接口为项目登记、修改、启用 / 停用、删除 webhook（回调地址、订阅的事件类型、可选的签名密钥），并查看最近的投递记录。（P6-19 评审后从原 P6-20 拆出。）

## 参考
- BS-15：事件类型只有 `PROJECT_CHANGED`；修改需要 `EDIT_PROJECT_SETTINGS`
- P6-20：`integration` 模块、`project_webhooks` / `webhook_deliveries`、`integration/JdbcWebhookRepository`、`integration/WebhookUrlPolicy`（结果 `ALLOWED` / `INVALID` / `UNRESOLVABLE` / `NOT_ALLOWED`）、`integration/HostResolver`、`integration/WebhookProperties`、`Webhook`、`DeliverySummary`、`WebhookDelivery`
- P3-07：命令总线（`project()`、`requiredAction()`，回收站中的项目默认 409）；P3-22：`web/ProjectSettingsController.java` 的写法
- P6-13：上一张修改 OpenAPI 生成物的卡

## 产出
- 命令与处理器（都在 `integration` 中，`project()` 为该项目，`requiredAction()` 为 `EDIT_PROJECT_SETTINGS`）：`ListWebhooks`、`CreateWebhook(projectId, payloadUrl, eventTypes, secret, enabled)`、`UpdateWebhook(projectId, webhookId, payloadUrl, eventTypes, secretChange, enabled)`（`secretChange` 区分“保持不变”“替换”“清除”）、`DeleteWebhook`、`ListWebhookDeliveries(projectId, webhookId, limit)`（`limit` 1 ~ 50）。webhook 不属于该项目时一律 404。`UpdateWebhook` 把 `enabled` 从 `true` 改为 `false` 时，在同一事务中把该 webhook 的 `PENDING` 投递改为 `CANCELLED`（重新启用后不补发）。
- `web/WebhookController.java`：
  - `GET /api/projects/{projectId}/webhooks`（operationId `listWebhooks`）；`POST`（`createWebhook`，201）；`PUT /{webhookId}`（`updateWebhook`）；`DELETE /{webhookId}`（`deleteWebhook`，204）；`GET /{webhookId}/deliveries?limit=`（`listWebhookDeliveries`）；
  - 响应体 `WebhookResponse` **不含密钥**，只有 `hasSecret`，以及 `lastDelivery`（`DeliverySummary`，可为空）；请求体中的 `secret` 至少 16 个字符；
  - 错误：`WebhookUrlPolicy` 结果为 `INVALID` → 400 `INVALID_WEBHOOK_URL`，`UNRESOLVABLE` → 400 `WEBHOOK_URL_UNRESOLVABLE`，`NOT_ALLOWED` → 400 `WEBHOOK_TARGET_NOT_ALLOWED`；超过 `wp.webhooks.max-per-project` 409 `WEBHOOK_LIMIT_REACHED`；事件类型为空或未知 400。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 覆盖新增的 200 / 201 响应；`pnpm -C frontend gen` 重新生成 `frontend/packages/api-types/src/schema.d.ts`。
- 测试 `web/WebhookManagementTest.java`（Testcontainers；测试配置中用桩 `HostResolver`，回调地址用 IP 字面量，不访问真实 DNS）：项目管理者完成增、查、改、删；响应中从不出现密钥；替换与清除密钥后 `hasSecret` 正确；停用后该 webhook 的 `PENDING` 投递变为 `CANCELLED`；三种地址错误各得到对应的错误码；超过上限 409；`CAN_EDIT` 成员 403，未登录 401，项目不存在 404，别的项目的 webhook ID 404，回收站中的项目 409；投递记录接口在没有记录时返回空数组。

## 实现要点
- 回调地址可能带令牌，所以列表接口同样只对 `EDIT_PROJECT_SETTINGS` 开放；日志中只记录主机名，不记录完整地址与密钥。
- 本卡不注册 outbox 消费者，也不发出任何 HTTP 请求（P6-21）。
- 依赖 P6-20 使用它的表与仓储；本卡是本轮 OpenAPI 生成物串行链的第一张（P6-49 → P6-24 → P6-27 → P6-50 → P6-29 → P6-51）。本卡不新增 Flyway 迁移。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-49` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WebhookManagementTest,JdbcWebhookRepositoryTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 投递、签名、重试（P6-21）；Web 端（P6-22）
- 密钥加密存储、“发送测试请求”、手动重发
