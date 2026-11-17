---
id: P6-21
title: webhook 投递：outbox 消费者登记投递任务，独立的投递线程按 webhook 顺序发送，带签名、超时与指数退避重试
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency, security]
status: done
depends_on: [P6-49]
---

# P6-21 webhook 投递

## 目标
每个新修订提交后，订阅了 `PROJECT_CHANGED` 的 webhook 在几秒内收到与旧系统字段兼容的通知；每个 webhook 至少收到一次、按修订顺序收到；一个慢的或失败的接收方不会拖住其它 webhook，也不会拖住 outbox 中继上的其它消费者（例如投影表）。

## 参考
- BS-15：载荷字段 `projectId`、`userId`、`revisionNumber`、`timestamp`（毫秒）；新系统新增的签名、超时、重试与地址限制
- P6-08：`revision/OutboxConsumer`（`name()`、`startFrom()`、`accept(batch)` 在中继事务内执行、部分确认）、`revision/OutboxMessage`；outbox 载荷（`revision/JdbcRevisionCommitOutbox.java`）为 `{projectId, revNo, userId, ts, changeCount}`
- P6-20：`project_webhooks`、`webhook_deliveries`（含 `claim_token` 与 `(webhook_id, delivery_id)` 部分索引）、`integration/JdbcWebhookRepository`、`integration/WebhookUrlPolicy`、`integration/WebhookProperties`；P6-49：管理接口（测试中通过它登记 webhook；停用时把待投递记录改为 `CANCELLED`）
- `bus/ProjectExistenceCheck.java`（`ACTIVE` / `TRASHED` / `MISSING`）
- P6-12：`projection/ProjectionOutboxConsumer.java`（消费者的写法）

## 产出
- `integration/WebhookOutboxConsumer.java`（实现 `OutboxConsumer`，`name()` 为 `"webhooks"`，`startFrom()` 为 `LATEST`：启用时不补发历史修订）：对本批中 `type = REVISION_COMMITTED` 的消息，项目为 `ACTIVE` 时，为该项目每个已启用且订阅了 `PROJECT_CHANGED` 的 webhook 插入一条 `PENDING` 投递（`on conflict (webhook_id, outbox_id) do nothing`），载荷由 outbox 载荷转换而来；`TRASHED` / `MISSING` 的项目不登记。返回本批最后一条的 `id`。**消费者内不发 HTTP 请求**：登记与 `outbox_consumers.last_id` 在同一个事务中提交，因此每个修订对每个 webhook 恰好登记一次。事务提交后唤醒投递线程。
- `integration/WebhookDispatcher.java`（`@Component`，`DisposableBean`；一个守护调度线程 `wp-webhook-dispatcher` 按 `wp.webhooks.poll-interval` 轮询，默认 1 秒，`wake()` 不阻塞、多次调用合并；发送放在有界的虚拟线程池中，并发上限 `wp.webhooks.max-concurrency`，默认 8；**同一 webhook 任何时刻至多一条投递在发送中**，一个慢的接收方只占用一个并发名额）：
  - **认领**：一个短事务中按批（不超过空闲的并发名额）选出到期的投递——状态为 `PENDING`、`next_attempt_at <= now()`，同一 webhook 没有更早的 `PENDING` 投递（保证每个 webhook 按顺序，借助 P6-20 的 `(webhook_id, delivery_id)` 部分索引），webhook 已启用，且本实例没有该 webhook 正在发送的投递；`for update skip locked` 锁行后写入新的 `claim_token`（随机 UUID），并把 `next_attempt_at` 推后一个租期（`wp.webhooks.lease`，默认 60 秒，必须大于连接超时与请求超时之和），提交。注意 PostgreSQL 不允许 `distinct on` 与 `for update` 同时使用，“没有更早的投递”用 `not exists` 子查询表达。
  - **发送**（不在数据库事务中）：投递前用 `WebhookUrlPolicy` 重新解析并检查地址，不允许时直接记为 `FAILED`（`last_error = TARGET_NOT_ALLOWED`）。这只是尽力而为：`HttpClient` 连接时会再解析一次，无法完全防止 DNS 重绑定；`docs/api/README.md` 中写明，需要严格限制时应通过出口代理或网络策略约束出站地址。JDK `HttpClient`：连接超时 `wp.webhooks.connect-timeout`（默认 5 秒）、请求超时 `wp.webhooks.request-timeout`（默认 10 秒），不跟随重定向。请求头：`Content-Type: application/json`、`User-Agent: webprotege-rebuild-webhook`、`X-WP-Event: PROJECT_CHANGED`、`X-WP-Delivery: <delivery_id>`；webhook 设置了密钥时加 `X-WP-Signature-256: sha256=<十六进制 HMAC-SHA256(密钥, 请求体原始字节)>`。
  - **记录结果**（新的短事务，更新条件为 `delivery_id = ? and claim_token = ?`：租期过后已被重新认领的投递，旧结果不会覆盖新结果）：2xx 记为 `SUCCEEDED`；否则 `attempts + 1`，达到 `wp.webhooks.max-attempts`（默认 8）记为 `FAILED`，未达到则 `next_attempt_at = now() + min(retry-base × 2^(attempts-1), retry-max)`（默认 1 秒起、上限 1 小时）。`last_status_code`、`last_error` 记录最后一次结果；`last_error` 截断到 500 字符，响应体最多读取 1 KB。
  - **清理**：每小时删除 `finished_at` 早于 `wp.webhooks.retention`（默认 30 天）的已完成记录。
  - 进程崩溃时，已认领未完成的投递在租期过后被重新认领（至少一次）。
- `integration/WebhookSigner.java`：计算签名的纯函数。
- `integration/WebhookProperties.java`：增加上面列出的配置项，另加 `wp.webhooks.enabled`（默认 `true`：项目管理者配置了 webhook 就应当投递；为 `false` 时不注册消费者与投递线程，管理接口照常可用，P6-22 不需要感知）。
- `docs/api/README.md` 增加 webhook 小节：载荷示例、请求头、签名的验证方法、接收方需要按 `X-WP-Delivery` 去重（至少一次语义）、重试与放弃的规则。
- 测试（Testcontainers，`wp.webhooks.allow-private-targets=true`，接收方用 JDK `com.sun.net.httpserver.HttpServer` 起在本地）：
  - `integration/WebhookSignerTest.java`：一组已知密钥、请求体与签名的对照；
  - `integration/WebhookDeliveryTest.java`：
    - 编辑一次：接收方收到 `POST`，载荷正好是 `projectId`、`userId`、`revisionNumber`、`timestamp` 四个字段且取值正确，请求头齐全，签名可以用密钥验证；
    - 连续 5 个修订：同一 webhook 按修订号顺序收到；
    - 接收方先返回两次 500 再返回 200：第 3 次成功，三次请求的 `X-WP-Delivery` 相同；一直返回 500：达到最大次数后为 `FAILED`（测试中把次数与退避调小）；
    - 一个 webhook 的接收方超时：另一个 webhook 照常及时收到；投影表消费者（`wp.projection.enabled=true`）照常前进；
    - 返回 302：不跟随，按失败处理；
    - 停用的 webhook、回收站中的项目：不登记投递；启用投递之前已有的修订不补发；
    - 两个 `WebhookDispatcher` 实例同时运行：每条投递只成功发送一次；租期过后被重新认领时，旧认领者写回的结果被忽略（`claim_token` 不匹配）；
    - 停用 webhook 后其待投递记录变为 `CANCELLED`，重新启用后不补发；`wp.webhooks.enabled=false` 时没有消费者与投递线程；
    - 日志中不出现密钥与完整回调地址。

## 实现要点
- 为什么拆成“登记 + 投递”两段：outbox 中继按消费者整体退避（P6-08），如果在 `accept` 里直接发 HTTP，一个坏掉的接收方会让所有 webhook 停住。登记在中继事务内完成，投递按 webhook 各自重试，互不影响。
- 顺序：同一 webhook 的下一条投递要等前一条成功或最终失败。某条投递最终失败之后，后面的投递继续发送，在 `last_error` 中可以看到失败原因。
- 锁与事务：发送 HTTP 时不持有任何数据库锁与事务；不取内核的任何锁（本卡不读内存模型）。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`、`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-21` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WebhookSignerTest,WebhookDeliveryTest,WebhookManagementTest,OutboxRelayTest,ModularityTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 管理接口与表结构（P6-20）；Web 端（P6-22）
- 连续失败后自动停用 webhook、手动重发、“发送测试请求”
- 其它事件类型、Slack 格式

## 执行记录
- 日期：2026-11-17
- 分支：feature/p6-21-webhook-delivery
- 测试：2026-11-17T15:11:13+08:00 通过（3 项）
- 实际改动：Added WebhookOutboxConsumer (LATEST, enqueue PENDING only), WebhookDispatcher (claim/lease/HMAC/backoff/HTTP/1.1), WebhookSigner, delivery knobs on WebhookProperties, docs/api/webhook.md, and WebhookDeliveryTest/WebhookSignerTest.
- 偏差：无
- 审查：java-reviewer, code-reviewer, silent-failure-hunter, security-reviewer
