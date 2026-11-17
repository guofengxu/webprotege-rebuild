---
id: P6-20
title: 后端：项目 webhook 的存储、回调地址校验与仓储（新增 integration 模块，BS-15）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: done
depends_on: [P6-13]
---

# P6-20 后端：项目 webhook 的存储、回调地址校验与仓储

## 目标
为项目 webhook 建立存储：新的 `integration` 模块、两张表、仓储与回调地址校验。管理接口在 P6-49，投递在 P6-21。（P6-19 评审后从原 P6-20 拆出管理接口，避免超过 400 行。）

## 参考
- BS-15（`docs/01-rea/behavior-specs.md`）：事件类型只有 `PROJECT_CHANGED`；修改需要 `EDIT_PROJECT_SETTINGS`；旧系统没有签名与重试
- 旧工程（只读）：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/webhook/ProjectWebhook.java`、`ProjectWebhookEventType.java`；设置界面 `../webprotege/webprotege-client/src/main/java/edu/stanford/bmir/protege/web/client/projectsettings/WebhookSettingsViewImpl.java`
- P3-07：命令总线（`Command`、`CommandHandler`、`project()`、`requiredAction()`，回收站中的项目默认 409）；P3-22：`web/ProjectSettingsController.java` 的写法
- P6-08：outbox 中继（P6-21 的输入）；P6-13：上一张修改 OpenAPI 生成物的卡
- `docs/02-architecture/overview.md` 第 2 节模块表；`backend/wp-app/src/test/java/wprebuild/app/ModularityTest.java`

## 产出
- 新 Modulith 模块 `wprebuild.app.integration`（对外集成：webhook，以及之后的 Kafka 发布器 P6-35）：
  - `integration/package-info.java`：`allowedDependencies = {"bus", "access", "revision", "shared"}`；
  - `web/package-info.java` 的 `allowedDependencies` 增加 `"integration"`；`ModularityTest` 的 `EXPECTED` 同步；
  - `docs/02-architecture/overview.md` 第 2 节表格增加 `integration` 一行（职责：“webhook 与消息发布等对外集成，消费 outbox”；允许依赖如上）。
- `db/migration/V6__webhooks.sql`：
  - `project_webhooks(webhook_id uuid primary key, project_id uuid not null references projects, payload_url text not null, event_types text[] not null, secret text, enabled boolean not null default true, created_by text not null references users, created_at timestamptz not null default now(), updated_at timestamptz not null default now())`，`project_id` 上建索引；
  - `webhook_deliveries(delivery_id bigint generated always as identity primary key, webhook_id uuid not null references project_webhooks on delete cascade, outbox_id bigint not null, event_type text not null, payload jsonb not null, status text not null check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED')), attempts integer not null default 0, next_attempt_at timestamptz not null default now(), claim_token uuid, last_status_code integer, last_error text, created_at timestamptz not null default now(), finished_at timestamptz, unique (webhook_id, outbox_id))`，部分索引 `(next_attempt_at) where status = 'PENDING'` 与 `(webhook_id, delivery_id) where status = 'PENDING'`（后者供 P6-21 判断“同一 webhook 没有更早的待投递记录”）；`claim_token` 是 P6-21 认领投递时写入的防重令牌；
  - `CoreSchemaTest` 的表清单增加这两张表。
- `integration/WebhookEventType.java`（枚举，只有 `PROJECT_CHANGED`）、`integration/Webhook.java`（`record Webhook(UUID webhookId, ProjectId projectId, URI payloadUrl, Set<WebhookEventType> eventTypes, boolean hasSecret, boolean enabled, UserId createdBy, Instant createdAt, Instant updatedAt, DeliverySummary lastDelivery)`；`lastDelivery` 取该 webhook 最新的一条投递，没有时为 `null`）、`integration/DeliverySummary.java`（`record DeliverySummary(String status, Integer statusCode, Instant finishedAt, String error)`）、`integration/WebhookDelivery.java`（投递记录的只读视图：`deliveryId`、`status`、`attempts`、`lastStatusCode`、`lastError`、`createdAt`、`finishedAt`，不含载荷）。
- `integration/JdbcWebhookRepository.java`：按项目列出、按 ID 读取、创建、更新、删除、统计项目的 webhook 数量；列出某 webhook 最近 N 条投递（按 `delivery_id` 倒序）；把某 webhook 的全部 `PENDING` 投递改为 `CANCELLED`（P6-49 停用 webhook 时调用）。
- `integration/WebhookUrlPolicy.java`：只接受 `http` / `https` 的绝对地址，拒绝带用户信息（`user:pass@`）的地址；通过可注入的 `integration/HostResolver`（默认实现调用 `InetAddress.getAllByName`）解析主机名；任一地址属于回环（`127.0.0.0/8`、`::1`）、私有（RFC 1918、`fc00::/7`）、运营商级 NAT（`100.64.0.0/10`）、`0.0.0.0/8`、基准测试网段（`198.18.0.0/15`）、链路本地（`169.254.0.0/16`、`fe80::/10`）、站点本地（`fec0::/10`）、NAT64（`64:ff9b::/96`）、组播或未指定地址时拒绝（IPv4 映射的 IPv6 地址按其 IPv4 地址判断），除非 `wp.webhooks.allow-private-targets=true`。结果为 `ALLOWED`、`INVALID`、`UNRESOLVABLE`、`NOT_ALLOWED` 四种之一，由 P6-49 映射为错误码；P6-21 在每次投递前复用它再检查一次。
- `integration/WebhookProperties.java`：`wp.webhooks.allow-private-targets`（默认 `false`）、`wp.webhooks.max-per-project`（默认 10）；P6-21 的投递参数也放在这里，由 P6-21 添加。
- 测试：
  - `integration/WebhookUrlPolicyTest.java`（用桩 `HostResolver` 与 IP 字面量，不访问真实 DNS）：`ftp://`、带用户信息、上面每个网段各一例被拒绝（含 `::ffff:127.0.0.1`）；公网 IP 通过；主机名解析失败得到 `UNRESOLVABLE`；开关打开后私有地址通过；
  - `integration/JdbcWebhookRepositoryTest.java`（Testcontainers）：增删改查与计数；删除 webhook 时其投递记录一并删除；改为 `CANCELLED` 只影响该 webhook 的 `PENDING` 记录；最近投递按倒序返回。

## 实现要点
- 密钥以明文保存在数据库中（HMAC 需要原始密钥），但 API 只写不读、日志不记录；在 `Webhook` 的 Javadoc 中写明（P6-49 的响应体同样不含密钥）。加密存储不在本卡范围。
- 回调地址本身也可能带令牌（例如 Slack 风格的地址），所以列表接口同样只对 `EDIT_PROJECT_SETTINGS` 开放，日志中只记录主机名。
- 本卡不注册任何 outbox 消费者，也不发出任何 HTTP 请求。
- 本卡不改 OpenAPI。Flyway 本轮 P6-20 用 `V6`、P6-29 用 `V7`、P6-37 用 `V8`；OpenAPI 生成物按 P6-49 → P6-24 → P6-27 → P6-50 → P6-29 → P6-51 串行修改（见 EPIC「共享文件与迁移号（第二轮）」）。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-20` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WebhookUrlPolicyTest,JdbcWebhookRepositoryTest,CoreSchemaTest,ModularityTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 管理接口、错误码与 OpenAPI（P6-49）
- 投递、签名、重试（P6-21）
- Web 端的设置标签页（P6-22）
- Slack 集成、`COMMENT_POSTED` 事件（BS-15：不移植）
- 密钥加密存储、“发送测试请求”按钮

## 执行记录
- 日期：2026-11-16
- 分支：feature/p6-20-webhook-registry
- 测试：2026-11-16T21:52:51+08:00 通过（3 项）
- 实际改动：新增 integration 模块、V6 webhook 表、JdbcWebhookRepository、WebhookUrlPolicy（拒绝私网/回环等，可配置放开），以及 Modularity/CoreSchema/overview 同步；列表查询用 has_secret 布尔表达式不读出密钥。
- 偏差：生产代码约 594 行（卡片预期 400）；overview.md 为卡片产出要求的模块表更新。审查意见：URL 策略由 P6-49 在写路径调用；update 的 secret 语义由 P6-49 处理不变/清空。
- 审查：security-reviewer, java-reviewer
