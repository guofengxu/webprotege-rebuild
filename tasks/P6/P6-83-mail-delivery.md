---
id: P6-83
title: 邮件投递登记：mail_deliveries（V12）、MailEnqueue 与 MailProperties
phase: P6
module: wp-app/integration
type: impl
size: S
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-45, P6-74]
---

# P6-83 邮件投递登记

## 目标
其它模块可以在自己的事务里登记一封待发邮件（至少一次语义的投递队列）；邮件关闭或未配置 SMTP 主机时登记为 `SKIPPED`，不阻塞调用方。真正的 SMTP 发送由 P6-87 完成，本卡不引入新依赖。

## 参考
- BS-22（投递、邮件内容）；EPIC「关注（P6-45）」决定第 2 条与「审查修订（2026-11-24）」
- 表结构与状态机对齐 `V6__webhooks.sql` 的 `webhook_deliveries`（P6-20 / P6-21）
- 旧出处：`LEG/mail/SendMailImpl.java`（行为参照，不复制）

## 产出
- `backend/wp-app/src/main/resources/db/migration/V12__mail_deliveries.sql`：`mail_deliveries` 表，至少含 `delivery_id`（identity 主键）、`idempotency_key text not null unique`、`to_address`、`subject`、`body_html`、`status`（check in `PENDING`/`SUCCEEDED`/`FAILED`/`SKIPPED`）、`attempts`、`next_attempt_at`、`claim_token`、`last_error`、`created_at`、`finished_at`；`status = 'PENDING'` 上建 `next_attempt_at` 部分索引（供 P6-87 认领）。
- `integration` 模块：
  - `MailProperties`（`@ConfigurationProperties("wp.mail")`）：本卡只定义 `enabled`（默认 false）、`host`、`from`、`link-base-url`（可空，供业务邮件拼链接；去掉末尾 `/`）。端口、认证、轮询与重试参数由 P6-87 增补。
  - `MailEnqueue`（模块公开 API）：`enqueue(idempotencyKey, toAddress, subject, bodyHtml)`。`enabled` 且 `host` 非空时登记 `PENDING`，否则登记 `SKIPPED`（同时写 `finished_at`）；加入调用方**当前**事务（不开 `REQUIRES_NEW`）；幂等键冲突视为成功、不抛异常，**必须**用 `insert … on conflict (idempotency_key) do nothing`（同 `JdbcWebhookRepository` 的写法），**不得**捕获 `DuplicateKeyException` 了事（PostgreSQL 出错后整个外层事务失效，中继随后的 `updateLastId` 会失败）；返回登记结果（新登记 / 已存在）。
  - `MailDeliveryRepository`：JDBC 登记与按幂等键查询（认领、写回由 P6-87 增补）。
- `CoreSchemaTest` 表清单增加 `mail_deliveries`。
- 测试 `MailEnqueueTest`：开启且有主机 → `PENDING`；`enabled=false` → `SKIPPED`；主机为空 → `SKIPPED`；同一幂等键二次登记不新增行、不抛异常；**同一外层事务内**同键登记两次后再执行一条 SQL 并提交，事务仍成功；调用方事务回滚后无行；`toAddress` 含 CR/LF 时拒绝（`IllegalArgumentException`）。

## 实现要点
- 依赖 P6-74 **只为** Flyway V11→V12 串行。
- 收件人字段按单个地址存储；“一人一封”由业务侧保证（BS-22）。
- 本卡不新增第三方依赖，不实现投递线程、不连接 SMTP。
- 本卡不做历史行清理（BS-22「首期不做」）。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-83` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MailEnqueueTest,CoreSchemaTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- SMTP 发送、`spring-boot-starter-mail`、认领与重试（P6-87）；关注表、匹配、REST、Web；讨论邮件正文
